package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.Stamped
import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ImportNoteTest {
    private val clock = FakeClock(millis = 1_000_000)
    private val id = NoteId.parse("2693d05a-07d9-5fa2-a27f-8aa58b71f569")
    private val created = Instant.fromEpochMilliseconds(5_000)
    private val tag = TagName.parse("Imported")
    private val note =
        ImportedNote(
            id = id,
            title = "Title",
            body = "Body",
            tags = setOf(tag),
            pinned = true,
            state = NoteState.ARCHIVED,
            createdAt = created,
            editedAt = created + 2_000.milliseconds,
        )

    private fun Revision.snapshot() = assertIs<RevisionContent.Snapshot>(content)

    @Test
    fun laterEditWritesARootAndAChildAtTheSourceTimes() =
        withStore(clock) { store ->
            assertEquals(ImportResult.IMPORTED, store.notes.importNote(note))

            val (root, child) = store.notes.history(id)
            assertEquals(emptySet(), root.parents)
            assertEquals(setOf(root.id), child.parents)
            assertEquals(HybridTimestamp(5_000, 0), root.timestamp)
            assertEquals(HybridTimestamp(7_000, 0), child.timestamp)
            listOf(root, child).forEach { revision ->
                assertEquals(store.deviceId, revision.deviceId)
                assertEquals(RevisionKind.User, revision.kind)
                assertEquals('7', revision.id.toString()[UUID_VERSION_INDEX])
            }
            assertEquals(root.content, child.content)
        }

    @Test
    fun theNoteShowsTheSourceTimesAndContent() =
        withStore(clock) { store ->
            store.notes.importNote(note)

            val stored = assertNotNull(store.notes.get(id))
            assertEquals("Title", stored.title)
            assertEquals("Body", stored.body)
            assertEquals(setOf(tag), stored.tags)
            assertEquals(true, stored.isPinned)
            assertEquals(NoteState.ARCHIVED, stored.state)
            assertEquals(created, stored.createdAt)
            assertEquals(HybridTimestamp(7_000, 0), stored.updatedAt)
            assertEquals(
                store.notes
                    .history(id)
                    .last()
                    .id,
                stored.revisionId,
            )
        }

    @Test
    fun pinAndStateCarryTheRootStamp() =
        withStore(clock) { store ->
            store.notes.importNote(note)

            val (root, child) = store.notes.history(id)
            assertEquals(Stamped(true, root.stamp), child.snapshot().pinned)
            assertEquals(Stamped(NoteState.ARCHIVED, root.stamp), child.snapshot().state)
        }

    @Test
    fun editedAtOrBeforeCreatedWritesOnlyTheRoot() =
        withStore(clock) { store ->
            listOf(created, created - 1.milliseconds).forEach { edited ->
                val noteId = NoteId.generate()
                store.notes.importNote(note.copy(id = noteId, editedAt = edited))

                val root = store.notes.history(noteId).single()
                assertEquals(HybridTimestamp(5_000, 0), root.timestamp)
            }
        }

    @Test
    fun editedLaterOnlyWithinTheMillisecondWritesOnlyTheRoot() =
        withStore(clock) { store ->
            val inside =
                note.copy(
                    createdAt = created + 100.microseconds,
                    editedAt = created + 900.microseconds,
                )

            store.notes.importNote(inside)

            val root = store.notes.history(id).single()
            assertEquals(HybridTimestamp(5_000, 0), root.timestamp)
        }

    @Test
    fun editedOneMillisecondLaterWritesTheChild() =
        withStore(clock) { store ->
            store.notes.importNote(note.copy(editedAt = created + 1.milliseconds))

            assertEquals(2, store.notes.history(id).size)
        }

    @Test
    fun importingDoesNotMoveTheClock() =
        withStore(clock) { store ->
            assertEquals(HybridTimestamp(1_000_000, 0), store.notes.create("", "first").updatedAt)

            store.notes.importNote(note)

            assertEquals(HybridTimestamp(1_000_000, 1), store.notes.create("", "second").updatedAt)
        }

    @Test
    fun aNoteWithHistoryIsSkippedAndKeepsItsChanges() =
        withStore(clock) { store ->
            store.notes.importNote(note)
            val imported = assertNotNull(store.notes.get(id))
            val edited = store.notes.edit(id, imported.revisionId, "Mine", "Mine")

            val again = note.copy(title = "Newer in the source", tags = emptySet())

            assertEquals(ImportResult.SKIPPED, store.notes.importNote(again))
            assertEquals(edited, store.notes.get(id))
            assertEquals(3, store.notes.history(id).size)
        }

    @Test
    fun aNoteDeletedForeverStaysDeleted() =
        withStore(clock) { store ->
            store.notes.importNote(note)
            store.notes.setState(id, NoteState.TRASHED)
            store.notes.deleteForever(id)
            val history = store.notes.history(id)

            assertEquals(ImportResult.SKIPPED, store.notes.importNote(note))

            assertNull(store.notes.get(id))
            assertEquals(history, store.notes.history(id))
        }

    @Test
    fun editBuildsOnAnImportedNote() =
        withStore(clock) { store ->
            store.notes.importNote(note)
            val imported = assertNotNull(store.notes.get(id))

            val edited = store.notes.edit(id, imported.revisionId, "New", "Text")

            assertEquals(HybridTimestamp(1_000_000, 0), edited.updatedAt)
            assertEquals(created, edited.createdAt)
            val history = store.notes.history(id)
            assertEquals(setOf(imported.revisionId), history.last().parents)
            assertEquals(Stamped(true, history.first().stamp), history.last().snapshot().pinned)
        }

    @Test
    fun editGoesAboveASourceTimeInTheFarFuture() =
        withStore(clock) { store ->
            val future = Instant.fromEpochMilliseconds(9_000_000_000_000)
            store.notes.importNote(note.copy(createdAt = future, editedAt = future))
            val imported = assertNotNull(store.notes.get(id))

            val edited = store.notes.edit(id, imported.revisionId, "New", "Text")

            assertEquals(HybridTimestamp(9_000_000_000_000, 1), edited.updatedAt)
            assertEquals(HybridTimestamp(1_000_001, 0), nextTimestamp(store))
        }

    @Test
    fun aStaleEditOfAnImportedNoteIsRejected() =
        withStore(clock) { store ->
            store.notes.importNote(note)
            val root = store.notes.history(id).first()
            val current = assertNotNull(store.notes.get(id))
            store.notes.edit(id, current.revisionId, "Other", "Other")

            val stale =
                assertFailsWith<StaleEditException> {
                    store.notes.edit(id, root.id, "Mine", "Mine")
                }
            assertEquals(root.id, stale.basedOn)
        }

    @Test
    fun unstorableTextIsReplaced() =
        withStore(clock) { store ->
            store.notes.importNote(note.copy(title = "a\uD83Db", body = "c\u0000d"))

            val stored = assertNotNull(store.notes.get(id))
            assertEquals("a�b", stored.title)
            assertEquals("c�d", stored.body)
        }

    @Test
    fun aCreatedTimeBeforeTheEpochIsRejectedAndWritesNothing() =
        withStore(clock) { store ->
            val before = Instant.fromEpochMilliseconds(-1)

            assertFailsWith<IllegalArgumentException> {
                store.notes.importNote(note.copy(createdAt = before))
            }

            assertTrue(store.notes.history(id).isEmpty())
        }

    @Test
    fun importedNotesAreSearchableAndListedByTheirSourceTimes() =
        withStore(clock) { store ->
            val older = note.copy(id = NoteId.generate(), title = "older", pinned = false)
            val newer =
                older.copy(
                    id = NoteId.generate(),
                    title = "newer",
                    editedAt = created + 3_000.milliseconds,
                )
            store.notes.importNote(older)
            store.notes.importNote(newer)
            val archived = NoteFilter(NoteState.ARCHIVED)

            assertEquals(listOf("newer", "older"), store.notes.list(archived).map { it.title })
            assertEquals(
                newer.id,
                store.notes
                    .search("newer", archived)
                    .single()
                    .note.id,
            )
        }

    /** The timestamp the store's clock issues next. */
    private fun nextTimestamp(store: NoteStore): HybridTimestamp {
        clock.millis++
        return store.notes.create("", "other note").updatedAt
    }

    private companion object {
        const val UUID_VERSION_INDEX = 12
    }
}
