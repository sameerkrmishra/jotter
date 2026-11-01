package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesViewModelTest : ViewModelTest() {
    private val work = TagName.parse("work")

    private fun Fixture.titles() = list.items.map { it.note.title }

    @Test
    fun pinnedNotesComeFirst() =
        test {
            notes.create("old pinned", "", pinned = true)
            notes.create("newer", "")
            notes.create("newest", "")
            settle()

            assertEquals(listOf("old pinned", "newest", "newer"), titles())
        }

    @Test
    fun eachViewShowsItsOwnNotes() =
        test {
            notes.create("active", "")
            notes.setState(notes.create("archived", "").id, NoteState.ARCHIVED)
            notes.setState(notes.create("trashed", "").id, NoteState.TRASHED)

            list.showView(NoteState.ARCHIVED)
            settle()
            assertEquals(listOf("archived"), titles())

            list.showView(NoteState.TRASHED)
            settle()
            assertEquals(listOf("trashed"), titles())
        }

    @Test
    fun aTagFiltersUntilItIsToggledOff() =
        test {
            notes.create("tagged", "", setOf(work))
            notes.create("plain", "")
            settle()
            assertEquals(1, list.tags.single().count)

            list.toggleTag(work)
            settle()
            assertEquals(listOf("tagged"), titles())

            list.toggleTag(work)
            settle()
            assertEquals(2, titles().size)
        }

    @Test
    fun searchStartsAtTwoCharacters() =
        test {
            notes.create("alpha", "first")
            notes.create("beta", "second")
            settle()

            list.search("a")
            settle()
            assertEquals(2, titles().size, "one character still shows the list")

            list.search(" a ")
            settle()
            assertEquals(2, titles().size, "spaces around it do not count")

            list.search("al")
            settle()
            assertEquals(listOf("alpha"), titles())
            assertEquals(
                "alpha",
                list.items
                    .single()
                    .snippet
                    ?.text,
            )
        }

    @Test
    fun aSearchRespectsTheViewAndTheTag() =
        test {
            notes.create("milk tagged", "", setOf(work))
            notes.create("milk plain", "")
            notes.setState(notes.create("milk archived", "").id, NoteState.ARCHIVED)

            list.search("milk")
            list.toggleTag(work)
            settle()
            assertEquals(listOf("milk tagged"), titles())

            list.toggleTag(work)
            list.showView(NoteState.ARCHIVED)
            settle()
            assertEquals(listOf("milk archived"), titles())
        }

    @Test
    fun aSearchFollowsChangesToTheNotes() =
        test {
            list.search("milk")
            settle()
            assertEquals(emptyList(), titles())

            notes.create("milk", "")
            settle()

            assertEquals(listOf("milk"), titles())
        }

    @Test
    fun aWriteByAnotherProcessShowsAfterARefresh() =
        test {
            settle()
            database.fromOutside { it.notes.create("from the CLI", "", setOf(work)) }
            settle()
            assertEquals(emptyList(), titles(), "flows do not see other processes")

            app.refresh()
            settle()

            assertEquals(listOf("from the CLI"), titles())
            assertEquals(work, list.tags.single().tag)
        }

    @Test
    fun theNoteActionsChangeTheNote() =
        test {
            val note = notes.create("note", "")
            settle()

            list.togglePinned(note)
            settle()
            assertTrue(
                list.items
                    .single()
                    .note.isPinned,
            )

            list.togglePinned(list.items.single().note)
            settle()
            assertTrue(
                !list.items
                    .single()
                    .note.isPinned,
            )

            list.move(note, NoteState.ARCHIVED)
            settle()
            assertEquals(emptyList(), titles())
            assertEquals(NoteState.ARCHIVED, notes.get(note.id)?.state)

            list.move(note, NoteState.TRASHED)
            settle()
            list.deleteForever(note)
            settle()
            assertNull(notes.get(note.id))
            assertNull(list.message)
        }

    @Test
    fun aLockedDatabaseShowsAMessageUntilAWriteSucceeds() =
        test {
            val note = notes.create("note", "")
            settle()

            database.whileLocked {
                list.togglePinned(note)
                settle()
            }

            assertEquals(BUSY_MESSAGE, list.message)
            assertTrue(!notes.get(note.id)!!.isPinned)

            list.togglePinned(note)
            settle()
            assertNull(list.message)
        }

    @Test
    fun aMessageCanBeDismissed() =
        test {
            val note = notes.create("note", "")
            database.fromOutside { other ->
                other.notes.setState(note.id, NoteState.TRASHED)
                other.notes.deleteForever(note.id)
            }
            list.togglePinned(note)
            settle()
            assertEquals(GONE_MESSAGE, list.message)

            list.dismissMessage()

            assertNull(list.message)
        }

    @Test
    fun aNoteDeletedByAnotherProcessLeavesTheListWithAMessage() =
        test {
            val note = notes.create("note", "")
            settle()
            database.fromOutside { other ->
                other.notes.setState(note.id, NoteState.TRASHED)
                other.notes.deleteForever(note.id)
            }

            list.togglePinned(note)
            settle()

            assertEquals(GONE_MESSAGE, list.message)
            assertEquals(emptyList(), titles())
        }

    @Test
    fun aFailedReadShowsAMessage() =
        test {
            notes.create("note", "")
            settle()
            store.close()

            app.refresh()
            settle()

            assertEquals(READ_FAILED_MESSAGE, list.message)
            assertEquals(listOf("note"), titles(), "the list keeps what it showed")
        }

    @Test
    fun deletingANoteThatLeftTheTrashIsRefused() =
        test {
            val note = notes.create("note", "")
            notes.setState(note.id, NoteState.TRASHED)
            list.showView(NoteState.TRASHED)
            settle()
            database.fromOutside { it.notes.setState(note.id, NoteState.ACTIVE) }

            list.deleteForever(note)
            settle()

            assertEquals(MOVED_MESSAGE, list.message)
            assertEquals(emptyList(), titles(), "the trash view has refreshed")
            assertEquals(NoteState.ACTIVE, notes.get(note.id)?.state)
        }
}
