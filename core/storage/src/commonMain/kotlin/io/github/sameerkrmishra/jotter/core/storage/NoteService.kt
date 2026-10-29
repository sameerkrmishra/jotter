package io.github.sameerkrmishra.jotter.core.storage

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.HybridLogicalClock
import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.Stamp
import io.github.sameerkrmishra.jotter.core.model.Stamped
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.db.JotterDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Creates, changes and reads this device's notes (PLAN.md §3.3). Get it from [NoteStore.notes].
 *
 * Every change creates a user [Revision] on top of the note's current one and materialises it, in
 * one write transaction (`BEGIN IMMEDIATE`), so the desktop app and the CLI never interleave.
 * The transaction also reads and advances the device's hybrid logical clock, which is stored in
 * the database, so timestamps stay strictly increasing across restarts and processes. A change
 * that would change nothing, such as pinning a pinned note, creates no revision and returns the
 * note as it is.
 *
 * Pinning and the state are last-writer-wins fields (ADR-0003): a revision that changes one
 * stamps it with its own [Revision.stamp], and every other revision carries the stamp over
 * unchanged.
 *
 * Text is stored as given, except that the characters SQLite cannot store unchanged (unpaired
 * surrogates, U+FFFE and U+FFFF) become U+FFFD.
 *
 * All functions block on database I/O: call them off the UI thread. They may be called from any
 * thread. The observation flows re-run their query after every change made through this
 * [NoteStore], but not after changes made by another process, such as the CLI.
 */
@Suppress("TooManyFunctions") // The note operations of PLAN.md §3.3, kept together on purpose.
public class NoteService internal constructor(
    private val database: JotterDatabase,
    private val deviceId: DeviceId,
    private val clock: Clock,
    private val queryContext: CoroutineContext,
) {
    private val revisions = RevisionStore(database.revisionQueries)
    private val materialiser = NoteMaterialiser(database.noteQueries)

    /** Creates an active note and returns it. */
    public fun create(
        title: String,
        body: String,
        tags: Set<TagName> = emptySet(),
        pinned: Boolean = false,
    ): Note =
        write { hlc ->
            val stamp = nextStamp(hlc, after = null)
            val content =
                RevisionContent.Snapshot(
                    title = title.toStorableText(),
                    body = body.toStorableText(),
                    tags = tags,
                    pinned = Stamped(pinned, stamp),
                    state = Stamped(NoteState.ACTIVE, stamp),
                )
            val revision = userRevision(stamp, NoteId.generate(), parent = null, content)
            store(revision)
            noteOf(revision, content, Instant.fromEpochMilliseconds(stamp.timestamp.physicalMillis))
        }

    /** Returns the note with [id], or null if there is none (any more). */
    public fun get(id: NoteId): Note? = noteQuery(id).executeAsOneOrNull()

    /** Returns the notes that [filter] selects, pinned first, then the most recently updated. */
    public fun list(filter: NoteFilter = NoteFilter()): List<Note> =
        listQuery(filter).executeAsList()

    /**
     * Emits [list] for [filter] now and again whenever it changes. See [observe] for when the
     * query re-runs.
     */
    public fun observeList(filter: NoteFilter = NoteFilter()): Flow<List<Note>> =
        listQuery(filter).asFlow().mapToList(queryContext).distinctUntilChanged()

    /**
     * Emits [get] for [id] now and again whenever it changes; null while there is no such note.
     *
     * The query re-runs on `queryContext` after every write transaction through this [NoteStore]
     * that touches the tables it reads, also when the write does not affect its result; equal
     * results are not emitted again. Emissions are conflated: a slow collector sees the latest
     * result, not every intermediate one. Writes by another process do not trigger it.
     */
    public fun observe(id: NoteId): Flow<Note?> =
        noteQuery(id).asFlow().mapToOneOrNull(queryContext).distinctUntilChanged()

    /**
     * Returns the tags of the notes that are not in the trash, each with the number of such notes
     * that have it, ordered by tag by Unicode code point (SQLite's binary order of UTF-8), so
     * case-sensitively.
     */
    public fun tags(): List<TagCount> = tagsQuery().executeAsList()

    /**
     * Emits [tags] now and again whenever they change. See [observe] for when the query re-runs.
     */
    public fun observeTags(): Flow<List<TagCount>> =
        tagsQuery().asFlow().mapToList(queryContext).distinctUntilChanged()

    /**
     * Replaces the title and body of the note [id] and keeps its current tags, pinning and state.
     *
     * The edit builds on the note's current revision. [basedOn] is only checked: if the title or
     * body changed since that revision, someone else edited the text in the meantime and saving
     * would silently drop their change, so the edit is rejected. Tag, pin and state changes in
     * the meantime are fine and are kept.
     *
     * @param basedOn The [Note.revisionId] the editor showed when the user started editing.
     * @throws StaleEditException if [basedOn] is not a revision of this note, or its title or body
     *   differ from the current ones.
     * @throws NoSuchNoteException if there is no note [id].
     */
    public fun edit(
        id: NoteId,
        basedOn: RevisionId,
        title: String,
        body: String,
    ): Note =
        change(id, basedOn) { current ->
            val newTitle = title.toStorableText()
            val newBody = body.toStorableText()
            if (newTitle == current.title && newBody == current.body) {
                null
            } else {
                { _ -> current.copy(title = newTitle, body = newBody) }
            }
        }

    /**
     * Adds [tag] to the note [id].
     *
     * Tag, pin and state changes apply to the current revision, whichever it is: unlike an edit,
     * they touch one item each and cannot lose someone else's change.
     *
     * @throws NoSuchNoteException if there is no note [id].
     */
    public fun addTag(
        id: NoteId,
        tag: TagName,
    ): Note =
        change(id) { current ->
            if (tag in current.tags) null else { _ -> current.copy(tags = current.tags + tag) }
        }

    /**
     * Removes [tag] from the note [id], if it has it.
     *
     * @throws NoSuchNoteException if there is no note [id].
     */
    public fun removeTag(
        id: NoteId,
        tag: TagName,
    ): Note =
        change(id) { current ->
            if (tag !in current.tags) null else { _ -> current.copy(tags = current.tags - tag) }
        }

    /**
     * Pins or unpins the note [id].
     *
     * @throws NoSuchNoteException if there is no note [id].
     */
    public fun setPinned(
        id: NoteId,
        pinned: Boolean,
    ): Note =
        change(id) { current ->
            if (current.pinned.value == pinned) {
                null
            } else {
                { stamp -> current.copy(pinned = Stamped(pinned, stamp)) }
            }
        }

    /**
     * Moves the note [id] to [state]: archives it, trashes it or restores it.
     *
     * @throws NoSuchNoteException if there is no note [id].
     */
    public fun setState(
        id: NoteId,
        state: NoteState,
    ): Note =
        change(id) { current ->
            if (current.state.value == state) {
                null
            } else {
                { stamp -> current.copy(state = Stamped(state, stamp)) }
            }
        }

    /**
     * Deletes the note [id] forever. Only a note in the trash can be deleted forever. Its history
     * stays, ending in a tombstone revision, so the deletion can sync like any other change.
     *
     * @throws NoSuchNoteException if there is no note [id].
     * @throws IllegalStateException if the note is not in the trash.
     */
    public fun deleteForever(id: NoteId) {
        write { hlc ->
            val current = currentNote(id)
            check(current.state == NoteState.TRASHED) {
                "Only a note in the trash can be deleted forever, but note $id is ${current.state}"
            }
            val stamp = nextStamp(hlc, after = current.updatedAt)
            store(userRevision(stamp, current.id, current.revisionId, RevisionContent.Tombstone))
        }
    }

    /**
     * Returns every revision of the note [id], oldest first ([Stamp] order), including the
     * tombstone of a note deleted forever. Empty if there never was such a note.
     */
    public fun history(id: NoteId): List<Revision> = revisions.forNote(id)

    /**
     * Searches the title and body of the notes that [filter] selects, and returns the best
     * [limit] hits, best first. Hits are ranked by BM25 with the title weighing ten times the
     * body; equal ranks come in list order.
     *
     * [query] is plain text, never search syntax: its words must all occur, in any order, and the
     * last one may be the start of a word, so results follow typing. Matching ignores case and
     * accents (`cafe` finds `Café`); words are split as by SQLite's `unicode61` tokenizer. A query
     * without words gives no hits. No query makes this throw.
     *
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    public fun search(
        query: String,
        filter: NoteFilter = NoteFilter(),
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<SearchHit> {
        require(limit > 0) { "limit must be positive: $limit" }
        val match = ftsQuery(query) ?: return emptyList()
        return database.noteQueries
            .search(match, filter.state.name, filter.tag?.toString(), limit.toLong()) {
                id,
                revisionId,
                title,
                body,
                isPinned,
                state,
                createdAt,
                physical,
                counter,
                tags,
                titleHighlight,
                bodySnippet,
                ->
                val note =
                    noteFromRow(
                        id,
                        revisionId,
                        title,
                        body,
                        isPinned,
                        state,
                        createdAt,
                        physical,
                        counter,
                        tags,
                    )
                SearchHit(note, snippetOf(titleHighlight, bodySnippet))
            }.executeAsList()
    }

    private fun noteQuery(id: NoteId) =
        database.noteQueries.selectNote(id.toString(), ::noteFromRow)

    private fun listQuery(filter: NoteFilter) =
        database.noteQueries.selectNotes(filter.state.name, filter.tag?.toString(), ::noteFromRow)

    private fun tagsQuery() =
        database.noteQueries.selectTagCounts { tag, count ->
            TagCount(TagName.fromNormalised(tag), count.toInt())
        }

    /**
     * Applies a change to the current content of the note [id], in one write transaction.
     * [change] returns null if the change would change nothing; otherwise a function that builds
     * the new content from the new revision's stamp, for the fields it writes.
     */
    private fun change(
        id: NoteId,
        basedOn: RevisionId? = null,
        change: (RevisionContent.Snapshot) -> ((Stamp) -> RevisionContent.Snapshot)?,
    ): Note =
        write { hlc ->
            val current = currentNote(id)
            val snapshot = snapshotOf(current)
            if (basedOn != null && !hasTextOf(id, basedOn, snapshot)) {
                throw StaleEditException(id, basedOn, current.revisionId)
            }
            val build = change(snapshot) ?: return@write current
            val stamp = nextStamp(hlc, after = current.updatedAt)
            val content = build(stamp)
            val revision = userRevision(stamp, current.id, current.revisionId, content)
            store(revision)
            noteOf(revision, content, current.createdAt)
        }

    /**
     * Runs [block] in a write transaction with the device's hybrid logical clock, and stores the
     * clock's new [HybridLogicalClock.last] before committing.
     */
    private fun <T> write(block: (HybridLogicalClock) -> T): T =
        database.transactionWithResult {
            val stored = database.deviceQueries.selectDevice().executeAsOne()
            val last = hybridTimestamp(stored.hlc_physical, stored.hlc_counter)
            val hlc = HybridLogicalClock(clock, last)
            val result = block(hlc)
            if (hlc.last != last) {
                database.deviceQueries.updateClock(
                    hlc.last.physicalMillis,
                    hlc.last.counter.toLong(),
                )
            }
            result
        }

    /** Whether [basedOn] is a revision of the note [id] with the title and body of [current]. */
    private fun hasTextOf(
        id: NoteId,
        basedOn: RevisionId,
        current: RevisionContent.Snapshot,
    ): Boolean {
        val base = revisions.get(basedOn)
        val text = if (base?.noteId == id) base.content as? RevisionContent.Snapshot else null
        return text != null && text.title == current.title && text.body == current.body
    }

    private fun currentNote(id: NoteId): Note = get(id) ?: throw NoSuchNoteException(id)

    /** The content of [note]'s current revision, which is never a tombstone. */
    private fun snapshotOf(note: Note): RevisionContent.Snapshot {
        val content = revisions.get(note.revisionId)?.content
        check(content is RevisionContent.Snapshot) {
            "Note ${note.id} is materialised from ${note.revisionId}, which is not a snapshot"
        }
        return content
    }

    /**
     * The stamp of a new user revision: a fresh timestamp from [hlc], greater than [after] (the
     * parent's timestamp) if given, this device and a new revision id.
     */
    private fun nextStamp(
        hlc: HybridLogicalClock,
        after: HybridTimestamp?,
    ): Stamp = Stamp(hlc.now(after), deviceId, RevisionId.generate())

    private fun userRevision(
        stamp: Stamp,
        noteId: NoteId,
        parent: RevisionId?,
        content: RevisionContent,
    ): Revision =
        Revision(
            id = stamp.revisionId,
            noteId = noteId,
            parents = setOfNotNull(parent),
            timestamp = stamp.timestamp,
            deviceId = deviceId,
            kind = RevisionKind.User,
            content = content,
        )

    private fun store(revision: Revision) {
        revisions.insert(revision)
        materialiser.materialise(revision)
    }

    private fun noteOf(
        revision: Revision,
        content: RevisionContent.Snapshot,
        createdAt: Instant,
    ): Note =
        Note(
            id = revision.noteId,
            revisionId = revision.id,
            title = content.title,
            body = content.body,
            tags = content.tags,
            isPinned = content.pinned.value,
            state = content.state.value,
            createdAt = createdAt,
            updatedAt = revision.timestamp,
        )

    /** Defaults for [NoteService]. */
    public companion object {
        /**
         * How many hits [search] returns by default: more than a results list shows at once, few
         * enough to make each search cheap.
         */
        public const val DEFAULT_SEARCH_LIMIT: Int = 100
    }
}
