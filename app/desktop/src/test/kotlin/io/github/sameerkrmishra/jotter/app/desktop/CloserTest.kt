package io.github.sameerkrmishra.jotter.app.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloserTest {
    private val steps: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun closer(
        keep: suspend () -> Unit = { steps += "keep" },
        finish: suspend () -> Unit = { steps += "finish" },
        stopHub: suspend () -> Unit = { steps += "hub" },
    ) = Closer(keep, finish, stopHub, { steps += "store" }, 200, 200)

    @Test
    fun aSignalSavesFirstThenStopsSyncAndTheHubThenClosesTheStore() =
        runBlocking {
            assertTrue(closer().close(saveFirst = true))
            assertEquals(listOf("keep", "finish", "hub", "store"), steps)
        }

    @Test
    fun theWindowsCloseHasSavedAlreadySoItDoesNotSaveAgain() =
        runBlocking {
            assertTrue(closer().close(saveFirst = false))
            assertEquals(listOf("finish", "hub", "store"), steps)
        }

    @Test
    fun itClosesOnceSoTheHookAfterTheWindowsCloseDoesNothing() =
        runBlocking {
            val closer = closer()
            assertTrue(closer.close(saveFirst = false))
            assertTrue(closer.close(saveFirst = true))
            assertEquals(listOf("finish", "hub", "store"), steps)
        }

    @Test
    fun aSignalDuringTheWindowsCloseWaitsForItAndThenFindsEverythingClosed() =
        runBlocking {
            val finishing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val closer =
                closer(finish = {
                    finishing.complete(Unit)
                    release.await()
                    steps += "finish"
                })
            val window = async { closer.close(saveFirst = false) }
            finishing.await()
            val signal = async { closer.close(saveFirst = true) }
            delay(50)
            assertFalse(signal.isCompleted)
            release.complete(Unit)
            assertTrue(window.await())
            assertTrue(signal.await())
            assertEquals(listOf("finish", "hub", "store"), steps)
        }

    @Test
    fun aSaveOrHubThatHangsIsGivenUpAfterItsBound() =
        runBlocking {
            val closer =
                closer(keep = { awaitCancellation() }, stopHub = { awaitCancellation() })
            assertTrue(closer.close(saveFirst = true))
            assertEquals(listOf("finish", "store"), steps)
        }

    @Test
    fun theStoreStaysOpenWhileSyncHasNotFinished() =
        runBlocking {
            val closer = closer(finish = { awaitCancellation() })
            assertFalse(closer.close(saveFirst = true))
            assertFalse(closer.close(saveFirst = false))
            assertEquals(listOf("keep", "hub"), steps)
        }
}
