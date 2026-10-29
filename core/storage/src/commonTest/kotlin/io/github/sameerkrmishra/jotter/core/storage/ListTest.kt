package io.github.sameerkrmishra.jotter.core.storage

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
import kotlin.test.Test
import kotlin.test.assertEquals

class ListTest {
    private val clock = FakeClock(millis = 1_000)
    private val shopping = TagName.parse("Shopping")
    private val work = TagName.parse("work")
    private val workUpperCase = TagName.parse("Work")

    @Test
    fun pinnedNotesComeFirstThenTheMostRecentlyUpdated() =
        withStore(clock) { store ->
            val notes = store.notes
            val oldest = notes.create("", "oldest")
            clock.millis = 2_000
            val pinnedOld = notes.create("", "pinned old", pinned = true)
            clock.millis = 3_000
            val newest = notes.create("", "newest")
            clock.millis = 4_000
            val pinnedNew = notes.create("", "pinned new", pinned = true)
            // Updating a note moves it up; the wall clock stands still, the counter grows.
            val oldestUpdated = notes.addTag(oldest.id, shopping)

            assertEquals(
                listOf(pinnedNew.id, pinnedOld.id, oldestUpdated.id, newest.id),
                notes.list().map { it.id },
            )
        }

    @Test
    fun notesUpdatedAtTheSameTimeAreOrderedById() =
        withStore(clock) { store ->
            // Only sync (M3) can give two notes the same timestamp, so write them directly.
            val revisions = RevisionStore(store.database.revisionQueries)
            val materialiser = NoteMaterialiser(store.database.noteQueries)
            val ids =
                listOf(
                    "0192e6f0-8b4a-7c3e-9d2f-1a2b3c4d5e6f",
                    "0192e6f0-8b4a-7c3e-9d2f-1a2b3c4d5e70",
                    "00000000-8b4a-7c3e-9d2f-1a2b3c4d5e6f",
                ).map(NoteId::parse)
            ids.forEachIndexed { index, noteId ->
                val revision =
                    sameTimeRevision(
                        noteId,
                        RevisionId.parse("0192e6f08b4a7c3e9d2f1a2b3c4d5e0$index"),
                    )
                revisions.insert(revision)
                materialiser.materialise(revision)
            }

            assertEquals(ids.sortedBy(NoteId::toString), store.notes.list().map { it.id })
        }

    @Test
    fun theFilterSelectsAStateAndOptionallyATag() =
        withStore(clock) { store ->
            val notes = store.notes
            val activeWork = notes.create("", "a", setOf(work))
            val activeShopping = notes.create("", "b", setOf(shopping))
            val activeUntagged = notes.create("", "c")
            val archivedWork = notes.create("", "d", setOf(work, shopping))
            notes.setState(archivedWork.id, NoteState.ARCHIVED)
            val trashedWork = notes.create("", "e", setOf(work))
            notes.setState(trashedWork.id, NoteState.TRASHED)

            fun idsOf(filter: NoteFilter) = notes.list(filter).map { it.id }.toSet()
            assertEquals(
                setOf(activeWork.id, activeShopping.id, activeUntagged.id),
                idsOf(NoteFilter()),
            )
            assertEquals(setOf(activeWork.id), idsOf(NoteFilter(tag = work)))
            assertEquals(emptySet(), idsOf(NoteFilter(tag = workUpperCase)))
            assertEquals(setOf(archivedWork.id), idsOf(NoteFilter(NoteState.ARCHIVED, shopping)))
            assertEquals(setOf(trashedWork.id), idsOf(NoteFilter(NoteState.TRASHED, work)))
        }

    @Test
    fun tagCountsLeaveOutTheTrash() =
        withStore(clock) { store ->
            val notes = store.notes
            notes.create("", "a", setOf(work, workUpperCase))
            val archived = notes.create("", "b", setOf(work, shopping))
            notes.setState(archived.id, NoteState.ARCHIVED)
            val trashed = notes.create("", "c", setOf(work, TagName.parse("old")))
            notes.setState(trashed.id, NoteState.TRASHED)

            // U+FF21 (fullwidth A) sorts before U+1F6D2 (a surrogate pair in UTF-16): code
            // point order, not UTF-16 order.
            val fullwidth = TagName.parse("\uFF21")
            val trolley = TagName.parse("\uD83D\uDED2")
            notes.create("", "d", setOf(trolley, fullwidth))

            assertEquals(
                listOf(
                    TagCount(shopping, 1),
                    TagCount(workUpperCase, 1),
                    TagCount(work, 2),
                    TagCount(fullwidth, 1),
                    TagCount(trolley, 1),
                ),
                notes.tags(),
            )
        }

    private fun sameTimeRevision(
        noteId: NoteId,
        id: RevisionId,
    ): Revision {
        val timestamp = HybridTimestamp(1_000, 0)
        val stamp = Stamp(timestamp, sampleDevice, id)
        val content =
            RevisionContent.Snapshot(
                title = "",
                body = noteId.toString(),
                tags = emptySet(),
                pinned = Stamped(false, stamp),
                state = Stamped(NoteState.ACTIVE, stamp),
            )
        return Revision(id, noteId, emptySet(), timestamp, sampleDevice, RevisionKind.User, content)
    }
}
