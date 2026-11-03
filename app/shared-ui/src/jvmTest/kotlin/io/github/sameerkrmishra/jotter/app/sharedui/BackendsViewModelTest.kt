@file:OptIn(ExperimentalPathApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.app.sync.Backend
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.NoSecretStore
import io.github.sameerkrmishra.jotter.app.sync.Outcome
import io.github.sameerkrmishra.jotter.app.sync.StandardBackendFactory
import io.github.sameerkrmishra.jotter.app.sync.SyncScheduler
import io.github.sameerkrmishra.jotter.core.sync.DeviceInfo
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L

/** The backend screen's view model where things fail, with real threads and no window. */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class BackendsViewModelTest {
    private val folders: Path = createTempDirectory("jotter-ui-vm")
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        folders.deleteRecursively()
    }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun aFailedStatusIsShownAndTheScreenGoesOn() {
        val database = TestDatabase()
        try {
            val store = database.open(Dispatchers.IO)
            val engine = SyncEngine(store, DeviceInfo("x", "test"), Dispatchers.IO)
            val manager =
                BackendManager(store, engine, StandardBackendFactory, NoSecretStore, Dispatchers.IO)
            // A database that went away under the screen.
            store.close()
            val screen = BackendsViewModel(manager, SyncScheduler(manager::syncAll), scope)
            waitFor("a message") { screen.message != null }
            screen.dismissMessage()
            screen.checkReachability()
            waitFor("the message again") { screen.message?.startsWith("That did not work") == true }
            screen.close()
        } finally {
            database.delete()
        }
    }

    @Test
    fun whatStopsTheSchedulerIsShown() {
        SyncedDevice("here").use { device ->
            val failing = SyncScheduler({ _ -> error("disk gone") })
            failing.start(scope, flowOf(0L))
            val screen = BackendsViewModel(device.manager, failing, scope)
            waitFor("the problem") {
                screen.problem ==
                    "Jotter could not sync: disk gone. It tries again."
            }
            screen.close()
        }
    }

    @Test
    fun aFolderThatIsBackSyncsAtOnce() {
        SyncedDevice("here").use { device ->
            val stick = folders.resolve("stick").createDirectories()
            runBlocking {
                val added = device.manager.addLocalFolder(stick.toString(), setUpNewStore = true)
                assertTrue(added is AddResult.Added)
            }
            stick.deleteRecursively()
            val rounds = AtomicInteger()
            val counting =
                SyncScheduler({ due: (Backend) -> Boolean ->
                    rounds.incrementAndGet()
                    device.manager.syncAll(due)
                })
            counting.start(scope, flowOf(0L))
            waitFor("the first round") { rounds.get() == 1 && !counting.syncing.value }
            assertEquals(Outcome.UNREACHABLE, lastOutcome(device))

            // The stick is plugged in again. The screen sees it, and syncs without waiting.
            stick.createDirectories()
            stick.resolve(".jotter-store").toFile().createNewFile()
            val screen = BackendsViewModel(device.manager, counting, scope)
            waitFor("the sync") { lastOutcome(device) == Outcome.SYNCED }
            assertEquals(2, rounds.get())
            screen.close()
        }
    }

    private fun lastOutcome(device: SyncedDevice) =
        device.store.backends
            .list()
            .single()
            .lastAttempt
            ?.outcome
            ?.let(Outcome::valueOf)
}
