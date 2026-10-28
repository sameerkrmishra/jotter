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
 * Text is stored as given, except that an unpaired surrogate (invalid UTF-16, which SQLite
 * cannot store) becomes U+FFFD.
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
                    title = title.withoutLoneSurrogates(),
                    body = body.withoutLoneSurrogates(),
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

    /** Emits [list] for [filter] now and after every change. */
    public fun observeList(filter: NoteFilter = NoteFilter()): Flow<List<Note>> =
        listQuery(filter).asFlow().mapToList(queryContext)

    /** Emits [get] for [id] now and after every change; null while there is no such note. */
    public fun observe(id: NoteId): Flow<Note?> =
        noteQuery(id).asFlow().mapToOneOrNull(queryContext)

    /**
     * Returns the tags of the notes that are not in the trash, ordered by tag (by UTF-16 code
     * unit, so case-sensitively), each with the number of such notes that have it.
     */
    public fun tags(): List<TagCount> = tagsQuery().executeAsList()

    /** Emits [tags] now and after every change. */
    public fun observeTags(): Flow<List<TagCount>> = tagsQuery().asFlow().mapToList(queryContext)

    /**
     * Replaces the title and body of the note [id], keeping its tags, pinning and state.
     *
     * @param basedOn The [Note.revisionId] the editor showed when the user started editing.
     * @throws StaleEditException if the note's current revision is not [basedOn]: someone else
     *   changed it in the meantime, and saving would silently drop that change.
     * @throws NoSuchNoteException if there is no note [id].
     */
    public fun edit(
        id: NoteId,
        basedOn: RevisionId,
        title: String,
        body: String,
    ): Note =
        change(id, basedOn) { current ->
            val newTitle = title.withoutLoneSurrogates()
            val newBody = body.withoutLoneSurrogates()
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
            if (basedOn != null && basedOn != current.revisionId) {
                throw StaleEditException(id, basedOn, current.revisionId)
            }
            val build = change(snapshotOf(current)) ?: return@write current
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
}
