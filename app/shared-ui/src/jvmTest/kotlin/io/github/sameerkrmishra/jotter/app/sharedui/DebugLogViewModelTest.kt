package io.github.sameerkrmishra.jotter.app.sharedui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the debug log does with a save asked for too early or twice. */
class DebugLogViewModelTest {
    @Test
    fun aSaveBeforeTheLogIsReadyOrWhileOneRunsDoesNothing() =
        runTest {
            val report = CompletableDeferred<String>()
            val answer = CompletableDeferred<String?>()
            val saved = mutableListOf<String>()
            val controls =
                DebugLogControls(
                    { report.await() },
                    DebugLogExport {
                        saved += it
                        answer.await()
                    },
                    "Save",
                )
            val view = DebugLogViewModel(controls, backgroundScope)
            testScheduler.runCurrent()
            view.export()
            testScheduler.runCurrent()
            assertTrue(saved.isEmpty())

            report.complete("log")
            testScheduler.runCurrent()
            view.export()
            view.export()
            testScheduler.runCurrent()
            assertTrue(view.isExporting)
            assertEquals(listOf("log"), saved)

            answer.complete("Saved.")
            testScheduler.runCurrent()
            assertEquals("Saved.", view.message)
            view.close()
        }
}
