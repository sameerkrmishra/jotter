package io.github.sameerkrmishra.jotter.app.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.app.sync.Outcome
import io.github.sameerkrmishra.jotter.app.sync.PeerConfig
import io.github.sameerkrmishra.jotter.app.sync.Secret
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The phone's half of the end-to-end test (scripts/hub-e2e.sh): this app, with its real Keystore
 * secrets and WorkManager job, syncs with the PC hub of `app:sync`'s HubForAndroidTest, which runs
 * on the host. The script forwards the emulator's 127.0.0.1 to the host with `adb reverse`, so the
 * token goes to loopback as the address rule wants (ADR-0015), and passes the port and token as
 * instrumentation arguments. Without them, as in a plain device test run, it is skipped.
 */
@RunWith(AndroidJUnit4::class)
class HubEndToEndTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val port = arguments.getString("hubPort")
    private val token = arguments.getString("hubToken")
    private val test = TestGraph()

    @After
    fun cleanUp() {
        test.close()
    }

    private fun backgroundSync() =
        runBlocking { TestListenableWorkerBuilder<SyncWorker>(test.context).build().doWork() }

    @Test
    fun thePhoneAndThePcConvergeThroughTheHub() =
        runBlocking {
            assumeTrue("Run by scripts/hub-e2e.sh", port != null && token != null)
            val app = test.ready
            val added = app.manager.addPeer("127.0.0.1:$port", Secret(checkNotNull(token)))
            val backend = assertIs<AddResult.Added>(added).backend
            assertEquals(PeerConfig("127.0.0.1", checkNotNull(port).toInt()), backend.config)

            assertEquals(ListenableWorker.Result.success(), backgroundSync())
            assertEquals(
                "written on the PC",
                app.store.notes
                    .list()
                    .single()
                    .body,
            )

            app.store.notes.create("From the phone", "written on the phone")
            assertEquals(ListenableWorker.Result.success(), backgroundSync())
            val synced =
                app.manager
                    .list()
                    .single()
            assertEquals(Outcome.SYNCED, synced.lastAttempt?.outcome, synced.lastAttempt?.detail)
        }
}
