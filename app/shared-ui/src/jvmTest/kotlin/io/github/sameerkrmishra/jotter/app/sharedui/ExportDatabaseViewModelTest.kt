package io.github.sameerkrmishra.jotter.app.sharedui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The steps of the "Export database" dialog, and what it ignores while a copy is written. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
class ExportDatabaseViewModelTest {
    @Test
    fun aCopyBeingWrittenIsNeitherStartedTwiceNorClosed() =
        runTest {
            val answer = CompletableDeferred<ExportStep>()
            val replaced = CompletableDeferred<ExportStep>()
            var asked = 0
            var closed = 0
            val export =
                ExportDatabaseViewModel(
                    {
                        asked++
                        answer.await()
                    },
                    this,
                ) { closed++ }
            assertEquals(ExportDatabaseViewModel.Stage.Explaining, export.stage)
            // Nothing to replace yet.
            export.replace()
            assertEquals(ExportDatabaseViewModel.Stage.Explaining, export.stage)

            export.choose()
            runCurrent()
            export.choose()
            export.close()
            runCurrent()
            assertEquals(ExportDatabaseViewModel.Stage.Running, export.stage)
            assertEquals(1, asked)
            assertEquals(0, closed)

            val exists = ExportStep.Exists("jotter-notes.db") { replaced.await() }
            answer.complete(exists)
            runCurrent()
            assertEquals(ExportDatabaseViewModel.Stage.Confirming(exists), export.stage)

            export.replace()
            runCurrent()
            assertEquals(ExportDatabaseViewModel.Stage.Running, export.stage)
            replaced.complete(ExportStep.Failed("The disk is full."))
            runCurrent()
            assertEquals(ExportDatabaseViewModel.Stage.Finished("The disk is full."), export.stage)
            export.close()
            assertEquals(1, closed)
        }
}
