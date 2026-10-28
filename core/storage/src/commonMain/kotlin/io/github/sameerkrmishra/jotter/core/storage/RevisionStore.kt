package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.Stamp
import io.github.sameerkrmishra.jotter.core.model.Stamped
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.db.RevisionQueries
import io.github.sameerkrmishra.jotter.core.storage.db.Revision as RevisionRow

/**
 * Stores [Revision]s in the `revision`, `revision_parent` and `revision_tag` tables and reads them
 * back unchanged: every shape [Revision] allows round-trips, user revisions and automatic merges,
 * snapshots and tombstones. Revisions are never updated or deleted.
 */
internal class RevisionStore(
    private val queries: RevisionQueries,
) {
    /**
     * Stores [revision], with its parents and tags, in one transaction (or as part of the
     * caller's). Idempotent by id, as sync requires (PLAN.md §6.3): storing a revision that is
     * already stored does nothing.
     *
     * @throws IllegalArgumentException if a different revision is stored under the same id, or
     *   if the title or body has an unpaired surrogate, which cannot be stored unchanged (see
     *   [withoutLoneSurrogates]).
     */
    fun insert(revision: Revision) {
        val content = revision.content
        if (content is RevisionContent.Snapshot) {
            require(content.title.isValidUtf16() && content.body.isValidUtf16()) {
                "Revision ${revision.id} has an unpaired surrogate in its text"
            }
        }
        queries.transaction {
            val stored = get(revision.id)
            if (stored != null) {
                require(stored == revision) {
                    "Revision ${revision.id} is already stored with different content"
                }
                return@transaction
            }
            insertRows(revision)
        }
    }

    /** Returns the revision with [id], or null if it is not stored. */
    fun get(id: RevisionId): Revision? {
        val row = queries.selectRevision(id.toString()).executeAsOneOrNull() ?: return null
        val parents = queries.selectParents(row.id).executeAsList().map(::revisionId)
        val tags = queries.selectTags(row.id).executeAsList().map(::tagName)
        return row.toRevision(parents.toSet(), tags.toSet())
    }

    /** Returns every stored revision of the note [noteId], in [Stamp] order (oldest first). */
    fun forNote(noteId: NoteId): List<Revision> {
        val id = noteId.toString()
        // Three reads without a transaction (see core/sqlite's README). The rows come first: a
        // revision's parents and tags are committed with it and never change, so every revision
        // read here finds them, even if another one is stored in between.
        val rows = queries.selectRevisionsOfNote(id).executeAsList()
        val parents = mutableMapOf<String, MutableSet<RevisionId>>()
        queries.selectParentsOfNote(id).executeAsList().forEach { edge ->
            parents.getOrPut(edge.revision_id) { mutableSetOf() }.add(revisionId(edge.parent_id))
        }
        val tags = mutableMapOf<String, MutableSet<TagName>>()
        queries.selectTagsOfNote(id).executeAsList().forEach { row ->
            tags.getOrPut(row.revision_id) { mutableSetOf() }.add(tagName(row.tag))
        }
        return rows.map { row ->
            row.toRevision(parents[row.id].orEmpty(), tags[row.id].orEmpty())
        }
    }

    /** Inserts [revision]'s `revision` row and its `revision_parent` and `revision_tag` rows. */
    private fun insertRows(revision: Revision) {
        val id = revision.id.toString()
        val noteId = revision.noteId.toString()
        val physical = revision.timestamp.physicalMillis
        val counter = revision.timestamp.counter.toLong()
        val deviceId = revision.deviceId.toString()
        val merge = revision.kind as? RevisionKind.AutomaticMerge
        val isMerge = merge != null
        val losingParent = merge?.losingParent?.toString()
        when (val content = revision.content) {
            RevisionContent.Tombstone -> {
                queries.insertTombstone(
                    id,
                    noteId,
                    physical,
                    counter,
                    deviceId,
                    isMerge,
                    losingParent,
                )
            }

            is RevisionContent.Snapshot -> {
                val pinned = content.pinned.stamp
                val state = content.state.stamp
                queries.insertSnapshot(
                    id = id,
                    noteId = noteId,
                    hlcPhysical = physical,
                    hlcCounter = counter,
                    deviceId = deviceId,
                    isMerge = isMerge,
                    losingParent = losingParent,
                    title = content.title,
                    body = content.body,
                    pinned = content.pinned.value,
                    pinnedHlcPhysical = pinned.timestamp.physicalMillis,
                    pinnedHlcCounter = pinned.timestamp.counter.toLong(),
                    pinnedDeviceId = pinned.deviceId.toString(),
                    pinnedRevisionId = pinned.revisionId.toString(),
                    state = content.state.value.name,
                    stateHlcPhysical = state.timestamp.physicalMillis,
                    stateHlcCounter = state.timestamp.counter.toLong(),
                    stateDeviceId = state.deviceId.toString(),
                    stateRevisionId = state.revisionId.toString(),
                )
                content.tags.forEach { tag -> queries.insertTag(id, tag.toString()) }
            }
        }
        revision.parents.forEach { parent -> queries.insertParent(id, parent.toString()) }
    }
}

private fun RevisionRow.toRevision(
    parents: Set<RevisionId>,
    tags: Set<TagName>,
): Revision =
    Revision(
        id = revisionId(id),
        noteId = NoteId.parse(note_id),
        parents = parents,
        timestamp = hybridTimestamp(hlc_physical, hlc_counter),
        deviceId = DeviceId.parse(device_id),
        kind =
            if (is_merge) {
                RevisionKind.AutomaticMerge(losing_parent?.let(::revisionId))
            } else {
                RevisionKind.User
            },
        content = if (is_tombstone) RevisionContent.Tombstone else snapshot(tags),
    )

// The table's CHECK constraint guarantees that a snapshot row has all of these columns.
private fun RevisionRow.snapshot(tags: Set<TagName>): RevisionContent.Snapshot =
    RevisionContent.Snapshot(
        title = snapshotColumn(title),
        body = snapshotColumn(body),
        tags = tags,
        pinned =
            Stamped(
                value = snapshotColumn(pinned),
                stamp =
                    stamp(
                        snapshotColumn(pinned_hlc_physical),
                        snapshotColumn(pinned_hlc_counter),
                        snapshotColumn(pinned_device_id),
                        snapshotColumn(pinned_revision_id),
                    ),
            ),
        state =
            Stamped(
                value = NoteState.valueOf(snapshotColumn(state)),
                stamp =
                    stamp(
                        snapshotColumn(state_hlc_physical),
                        snapshotColumn(state_hlc_counter),
                        snapshotColumn(state_device_id),
                        snapshotColumn(state_revision_id),
                    ),
            ),
    )

private fun <T : Any> snapshotColumn(value: T?): T =
    checkNotNull(value) { "A snapshot revision has a NULL column; the database is corrupt" }

private fun stamp(
    physical: Long,
    counter: Long,
    deviceId: String,
    revisionId: String,
): Stamp =
    Stamp(hybridTimestamp(physical, counter), DeviceId.parse(deviceId), revisionId(revisionId))

/** Decodes a timestamp stored as two INTEGER columns. */
internal fun hybridTimestamp(
    physical: Long,
    counter: Long,
): HybridTimestamp = HybridTimestamp(physical, counter.toInt())

private fun revisionId(text: String): RevisionId = RevisionId.parse(text)

private fun tagName(text: String): TagName = TagName.fromNormalised(text)
