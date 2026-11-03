package io.github.sameerkrmishra.jotter.app.sync.e2e

import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.Outcome
import io.github.sameerkrmishra.jotter.app.sync.SecretStores
import io.github.sameerkrmishra.jotter.app.sync.StandardBackendFactory
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.DeviceInfo
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import io.github.sameerkrmishra.jotter.server.HubConfig
import io.github.sameerkrmishra.jotter.server.PeerHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** The note the PC writes for the phone. */
private const val FROM_THE_PC = "From the PC"

/** The note the phone writes; app/android's HubEndToEndTest writes it. */
private const val FROM_THE_PHONE = "From the phone"

/**
 * The PC's half of the end-to-end test of the Android app (app/android/scripts/hub-e2e.sh): a hub
 * on 127.0.0.1, which the emulator reaches through `adb reverse`, and the PC syncing with the
 * hub's folder as a local folder, as the desktop app does (ADR-0015). It writes a note, hands the
 * hub's port and a pairing token to the script, waits for the phone, then checks that the phone's
 * note arrived and the PC's note was not changed.
 *
 * It runs only when the script sets `JOTTER_HUB_E2E_DIR` (see build.gradle.kts), and talks to the
 * script through files in that directory: `port` and `token` when the hub is ready, `phone-done`
 * from the script, `result` at the end.
 */
@Suppress("InjectDispatcher") // The test stands in for a composition root.
class HubForAndroidTest {
    @Test
    fun aPhoneAndThePcConvergeThroughTheHub() =
        runBlocking {
            val dir = File(checkNotNull(System.getenv("JOTTER_HUB_E2E_DIR")))
            val hub =
                PeerHub(
                    HubConfig(
                        storeRoot = File(dir, "hub").path.toPath(),
                        tokenFile = File(dir, "hub-devices.json").path.toPath(),
                        host = "127.0.0.1",
                        port = 0,
                    ),
                )
            hub.setUpStore()
            val port = hub.start().port
            val pcFile = File(dir, "pc/pc.db").also { checkNotNull(it.parentFile).mkdirs() }
            val pc = NoteStore.open(pcFile.path, "PC", Dispatchers.IO)
            try {
                val manager =
                    BackendManager(
                        pc,
                        SyncEngine(pc, DeviceInfo("PC", "linux"), Dispatchers.IO),
                        StandardBackendFactory(SecretStores.NONE),
                        SecretStores.NONE,
                        Dispatchers.IO,
                    )
                assertIs<AddResult.Added>(manager.addLocalFolder(File(dir, "hub").path))
                pc.notes.create(FROM_THE_PC, "written on the PC")
                assertEquals(
                    Outcome.SYNCED,
                    manager
                        .syncAll()
                        .single()
                        .attempt.outcome,
                )

                File(dir, "token").writeText(hub.pair("Android emulator").token)
                File(dir, "port").writeText(port.toString())
                awaitFile(File(dir, "phone-done"))

                assertEquals(
                    Outcome.SYNCED,
                    manager
                        .syncAll()
                        .single()
                        .attempt.outcome,
                )
                val titles =
                    pc.notes
                        .list()
                        .map { it.title }
                        .toSet()
                assertEquals(setOf(FROM_THE_PC, FROM_THE_PHONE), titles)
                val phone = pc.notes.list().single { it.title == FROM_THE_PHONE }
                assertEquals("written on the phone", phone.body)
                File(dir, "result").writeText("ok")
            } finally {
                pc.close()
                hub.stop()
            }
        }

    private suspend fun awaitFile(file: File) {
        val start = TimeSource.Monotonic.markNow()
        while (!file.exists()) {
            check(start.elapsedNow() < WAIT) { "the phone did not finish within $WAIT" }
            delay(POLL)
        }
    }

    private companion object {
        val WAIT = 15.minutes
        val POLL = 200.milliseconds
    }
}
