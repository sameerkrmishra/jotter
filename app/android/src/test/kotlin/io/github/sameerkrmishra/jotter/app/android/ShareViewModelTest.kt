package io.github.sameerkrmishra.jotter.app.android

import androidx.lifecycle.SavedStateHandle
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The share window's model, with notes in memory in place of the database. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    private val main = StandardTestDispatcher()

    /** Notes that record what is stored, or fail with [failure]. */
    private class FakeNotes(
        val failure: Exception? = null,
    ) : SharedNotes {
        val created = mutableListOf<Pair<String, String>>()
        var syncs = 0

        override suspend fun create(
            title: String,
            body: String,
        ): String {
            failure?.let { throw it }
            created += title to body
            return "note-${created.size}"
        }

        override fun syncSoon() {
            syncs++
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.model(
        outcome: ShareOutcome,
        notes: SharedNotes,
        saved: SavedStateHandle = SavedStateHandle(),
    ): ShareViewModel = ShareViewModel(outcome, notes, saved).also { testScheduler.runCurrent() }

    @Test
    fun aNewNoteIsStoredOnceAndSyncedSoon() =
        runTest(main) {
            val notes = FakeNotes()
            val saved = SavedStateHandle()
            val model = model(ShareOutcome.NewNote("t", "b", "left out"), notes, saved)
            assertEquals(ShareState.Saved("left out"), model.state)
            assertEquals(listOf("t" to "b"), notes.created)
            assertEquals(1, notes.syncs)
            assertEquals("note-1", saved.get<String>(SAVED_NOTE))
        }

    @Test
    fun aWindowBroughtBackAfterTheProcessEndedDoesNotStoreItAgain() =
        runTest(main) {
            val notes = FakeNotes()
            val saved = SavedStateHandle(mapOf(SAVED_NOTE to "note-1"))
            val model = model(ShareOutcome.NewNote("t", "b", null), notes, saved)
            assertEquals(ShareState.Saved(null), model.state)
            assertEquals(emptyList(), notes.created)
            assertEquals(0, notes.syncs)
        }

    @Test
    fun aRefusedShareStoresNothing() =
        runTest(main) {
            val notes = FakeNotes()
            val model = model(ShareOutcome.Refused("no"), notes)
            assertEquals(ShareState.Refused("no"), model.state)
            assertEquals(emptyList(), notes.created)
        }

    @Test
    fun failuresAreSaidPlainly() =
        runTest(main) {
            val note = ShareOutcome.NewNote("t", "b", null)
            assertEquals(
                ShareState.Refused("cannot open the notes"),
                model(note, FakeNotes(NotesUnavailableException("cannot open the notes"))).state,
            )
            assertEquals(
                ShareState.Refused(NOTES_BUSY),
                model(note, FakeNotes(StorageBusyException(IllegalStateException()))).state,
            )
            assertEquals(
                ShareState.Refused("The note could not be saved: disk full"),
                model(note, FakeNotes(IllegalStateException("disk full"))).state,
            )
        }

    @Test
    fun itSaysSavingUntilTheNoteIsStored() =
        runTest(main) {
            val notes =
                object : SharedNotes {
                    val gate = CompletableDeferred<Unit>()

                    override suspend fun create(
                        title: String,
                        body: String,
                    ): String {
                        gate.await()
                        return "n"
                    }

                    override fun syncSoon() = Unit
                }
            val model = model(ShareOutcome.NewNote("t", "b", null), notes)
            assertEquals(ShareState.Saving, model.state)
            notes.gate.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(ShareState.Saved(null), model.state)
        }

    @Test
    fun aShareThatCannotBeReadIsRefused() {
        assertEquals(
            ShareOutcome.Refused(SHARE_UNREADABLE),
            readShare { throw IllegalStateException("BadParcelableException: no such class") },
        )
        assertEquals(
            ShareOutcome.NewNote("", "milk", null),
            readShare { Share(true, "text/plain", "milk", null, false) },
        )
    }
}
