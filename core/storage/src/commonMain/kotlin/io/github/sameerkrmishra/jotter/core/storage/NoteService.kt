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
 * [importNote] is the one write that keeps source times instead of reading the clock (ADR-0009).
 * The transaction also reads and advances the device's hybrid logical clock, which is stored in
 * the database, so timestamps stay strictly increasing across restarts and processes. A change
 * that would change nothing, such as pinning a pinned note, creates no revision and returns the
 * note as it is.
 *
 * Pinning and the state are last-writer-wins fields (ADR-0003): a revision that changes one
 * stamps it with its own [Revision.stamp], and every other revision carries the stamp over
 * unchanged.
 *
 * Text is stored as given, except that the characters SQLite cannot store unchanged become
 * U+FFFD: unpaired surrogates, NUL, U+FFFE, U+FFFF, and U+FEFF as the first character.
 *
 * All functions block on database I/O: call them off the UI thread. They may be called from any
 * thread. The observation flows re-run their query after every change made through this
 * [NoteStore], but not after changes made by another process, such as the CLI.
 *
 * A write waits up to 5 s for another process's write to finish, then fails with
 * [StorageBusyException].
 */
@Suppress("TooManyFunctions") // The note operations of PLAN.md §3.3, kept together on purpose.
public class NoteService internal constructor(
    private val database: JotterDatabase,
    private val deviceId: DeviceId,
    private val clock: Clock,
    private val queryContext: CoroutineContext,
    private val ids: IdSource,
) {
    private val revisions = RevisionStore(database.revisionQueries)
    private val materialiser = NoteMaterialiser(database.noteQueries)

    /**
     * Creates an active note and returns it.
     *
     * @throws NoteTooLargeException if the text is over [MAX_NOTE_CHARS].
     * @throws StorageBusyException if another process kept the database locked.
     */
    public fun create(
        title: String,
        body: String,
        tags: Set<TagName> = emptySet(),
        pinned: Boolean = false,
    ): Note =
        write { hlc ->
            requireNoteSize(title, body)
            val stamp = nextStamp(hlc, after = null)
            val content =
                RevisionContent.Snapshot(
                    title = title.toStorableText(),
                    body = body.toStorableText(),
                    tags = tags,
                    pinned = Stamped(pinned, stamp),
                    state = Stamped(NoteState.ACTIVE, stamp),
                )
            val revision = userRevision(stamp, ids.newNoteId(), parent = null, content)
            store(revision)
            noteOf(revision, content, Instant.fromEpochMilliseconds(stamp.timestamp.physicalMillis))
        }

    /**
     * Stores [note], taken from another app, with the source's times as its revisions' timestamps
     * (ADR-0009), in one write transaction.
     *
     * If the note [ImportedNote.id] has any history, even one that ends in deletion forever,
     * nothing is written and the result is [ImportResult.SKIPPED]: a repeated import duplicates
     * nothing, changes nothing and brings nothing back.
     *
     * Otherwise this writes a root revision at [ImportedNote.createdAt], and a second revision
     * with the same content at [ImportedNote.editedAt] if that is strictly later in whole
     * milliseconds, so a child is always above its parent. Both have counter 0 and this device's
     * id. The pin and state keep the root's stamp, so any real later change beats them. The
     * device's clock is not moved; [edit] and the other changes go above the imported revisions
     * with `now(after = parent)`, even if a source time is in the future.
     *
     * Text is made storable as by [create].
     *
     * @throws IllegalArgumentException if [ImportedNote.createdAt] is before the Unix epoch.
     * @throws StorageBusyException if another process kept the database locked.
     */
    public fun importNote(note: ImportedNote): ImportResult =
        write { _ ->
            if (revisions.forNote(note.id).isNotEmpty()) {
                ImportResult.SKIPPED
            } else if (note.title.length.toLong() + note.body.length > MAX_NOTE_CHARS) {
                ImportResult.TOO_LARGE
            } else {
                val createdMillis = note.createdAt.toEpochMilliseconds()
                val editedMillis = note.editedAt.toEpochMilliseconds()
                val rootStamp = importStamp(createdMillis)
                val content =
                    RevisionContent.Snapshot(
                        title = note.title.toStorableText(),
                        body = note.body.toStorableText(),
                        tags = note.tags,
                        pinned = Stamped(note.pinned, rootStamp),
                        state = Stamped(note.state, rootStamp),
                    )
                store(userRevision(rootStamp, note.id, parent = null, content))
                if (editedMillis > createdMillis) {
                    val parent = rootStamp.revisionId
                    store(userRevision(importStamp(editedMillis), note.id, parent, content))
                }
                ImportResult.IMPORTED
            }
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
     * the meantime are fine and are kept. Saving the text the note already has changes nothing, so
     * it is never rejected: a retried save finds its own edit and returns the note.
     *
     * @param basedOn The [Note.revisionId] the editor showed when the user started editing.
     * @throws StaleEditException if the text changes and [basedOn] is not a revision of this note,
     *   or its title or body differ from the current ones.
     * @throws NoteTooLargeException if the new text is over [MAX_NOTE_CHARS].
     * @throws NoSuchNoteException if there is no note [id].
     * @throws StorageBusyException if another process kept the database locked.
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
            val isSame = newTitle == current.title && newBody == current.body
            if (!isSame) requireNoteSize(newTitle, newBody)
            if (isSame) null else { _ -> current.copy(title = newTitle, body = newBody) }
        }

    /**
     * Adds [tag] to the note [id].
     *
     * Tag, pin and state changes apply to the current revision, whichever it is: unlike an edit,
     * they touch one item each and cannot lose someone else's change.
     *
     * @throws NoSuchNoteException if there is no note [id].
     * @throws StorageBusyException if another process kept the database locked.
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
     * @throws StorageBusyException if another process kept the database locked.
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
     * @throws StorageBusyException if another process kept the database locked.
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
     * @throws StorageBusyException if another process kept the database locked.
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
     * @throws StorageBusyException if another process kept the database locked.
     */
    public fun deleteForever(id: NoteId) {
        write { hlc ->
            val current = currentNote(id)
            check(current.state == NoteState.TRASHED) {
                "Only a note in the trash can be deleted forever, but note $id is ${current.state}"
            }
            val stamp = nextStamp(hlc, after = current.updatedAt)
            store(userRevision(stamp, current.id, current.revisionId, RevisionContent.Tombstone))
            // The only head is now a tombstone, which closes what is behind it. With other heads
            // the next sync decides, and until then the note's rows stand.
            if (id !in revisions.notesWithSeveralHeads()) {
                database.syncQueries.deleteConflictsOfNote(id.toString())
            }
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
     * [query] is plain text, never search syntax. It is split into words at whitespace and NUL
     * only; the words must all occur, in any order, and the last one may be the start of a word,
     * so results follow typing. A word with punctuation inside, such as `e-mail` or
     * `bread,milk`, matches as a phrase: its parts must occur next to each other and in that
     * order. Matching ignores case and accents (`cafe` finds `Café`), with SQLite's `unicode61`
     * tokenizer. A query without words gives no hits. No query makes this throw.
     *
     * Exactly [limit] hits may mean that more were left out. A last word of a single letter is
     * the slowest query, as no prefix index covers it (about 110 ms over 10,000 notes, see
     * README.md); search as you type may want to wait for a second letter.
     *
     * The snippet marks matches with private-use characters, so a note that itself contains
     * U+E000 or U+E001 may get a wrong highlight or its body instead of its title as snippet, and
     * such a character inside a word is part of that word for matching.
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
                titleSnippet,
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
                SearchHit(note, snippetOf(titleSnippet, bodySnippet))
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
     * the new content from the new revision's stamp, for the fields it writes. [basedOn] is only
     * checked for a real change: a save of the current text cannot lose anything.
     */
    private fun change(
        id: NoteId,
        basedOn: RevisionId? = null,
        change: (RevisionContent.Snapshot) -> ((Stamp) -> RevisionContent.Snapshot)?,
    ): Note =
        write { hlc ->
            val current = currentNote(id)
            val snapshot = snapshotOf(current)
            val build = change(snapshot) ?: return@write current
            if (basedOn != null && !hasTextOf(id, basedOn, snapshot)) {
                throw StaleEditException(id, basedOn, current.revisionId)
            }
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
        reportingBusy {
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
    ): Stamp = Stamp(hlc.now(after), deviceId, ids.newRevisionId())

    /** The stamp of an imported revision, at a source time instead of a clock reading. */
    private fun importStamp(physicalMillis: Long): Stamp =
        Stamp(HybridTimestamp(physicalMillis, counter = 0), deviceId, ids.newRevisionId())

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
