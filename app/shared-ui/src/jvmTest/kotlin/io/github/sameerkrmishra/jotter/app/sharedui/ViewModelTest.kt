package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest

/**
 * Runs view models against a real temporary store. Everything, the "UI thread" and the "IO
 * dispatcher" alike, runs on the test's dispatcher, on virtual time, so [Fixture.settle] waits for
 * all of it and for an autosave.
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class ViewModelTest {
    protected val database = TestDatabase()

    @AfterTest
    fun deleteDatabase() {
        database.delete()
    }

    /** What a test works with. */
    internal inner class Fixture(
        private val test: TestScope,
    ) {
        val testScheduler get() = test.testScheduler

        private val dispatcher = UnconfinedTestDispatcher(test.testScheduler)

        val scope = CoroutineScope(dispatcher + Job())
        val store: NoteStore = database.open(dispatcher)
        val notes: NoteService = store.notes
        val app = AppViewModel(notes, scope, dispatcher)
        internal val list get() = app.list

        // Not advanceUntilIdle: while a draft cannot be saved, the autosave never stops trying.
        fun settle() {
            testScheduler.advanceTimeBy(AUTOSAVE_IDLE_MS)
            testScheduler.runCurrent()
        }

        fun close() {
            scope.cancel()
            store.close()
        }
    }

    internal fun test(block: suspend Fixture.() -> Unit) =
        runTest {
            val fixture = Fixture(this)
            try {
                fixture.settle()
                fixture.block()
            } finally {
                fixture.close()
            }
        }
}
