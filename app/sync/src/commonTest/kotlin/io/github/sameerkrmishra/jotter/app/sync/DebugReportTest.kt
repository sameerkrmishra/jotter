package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.core.logging.LogFiles
import io.github.sameerkrmishra.jotter.core.logging.LogLevel
import io.github.sameerkrmishra.jotter.core.logging.LogRecord
import io.github.sameerkrmishra.jotter.core.logging.RotatingFileSink
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.SyncAttempt
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DebugReportTest {
    private val app = AppInfo("desktop", "1.2.3", "Linux 6.18 (amd64)")

    private fun test(block: suspend TempDir.() -> Unit) = runTest { TempDir().use { it.block() } }

    @Test
    fun itSaysWhatItIsAndWhatRuns() =
        test {
            val text = DebugReport(app, null, FakeClock(0)).build(null)
            assertContains(text, DEBUG_LOG_PREAMBLE)
            assertContains(text, "Created: 1970-01-01T00:00:00Z")
            assertContains(text, "App: desktop 1.2.3")
            assertContains(text, "Platform: Linux 6.18 (amd64)")
            assertContains(text, "Database schema: ${NoteStore.SCHEMA_VERSION}")
            assertContains(text, "Sync format: ${SyncEngine.FORMAT}")
            assertContains(text, "Logs: none yet")
            assertFalse("Backends" in text)
        }

    @Test
    fun backendsShowTheirKindAndLastSyncButNotWhereTheyAre() =
        test {
            val folder = folder("private-folder-name")
            Device(this, "a").use { device ->
                device.store.notes.create("Secret title", "Secret body")
                device.manager.addLocalFolder(folder.toString(), setUpNewStore = true)
                device.manager.addLocalFolder(folder("never").toString(), setUpNewStore = true)
                val synced = device.manager.list().first()
                device.manager.sync(synced.id)
                val text = DebugReport(app, null).build(device.manager)
                assertContains(text, "Backends: 2")
                assertContains(text, "- ${synced.id} (local-folder), added ")
                assertContains(text, "  last sync: SYNCED at ")
                assertContains(text, "  last sync: never")
                assertContains(text, "  last success: never")
                assertFalse("private-folder-name" in text)
                assertFalse("Secret" in text)
            }
        }

    @Test
    fun issuesAreListed() =
        test {
            Device(this, "a").use { device ->
                device.manager.addLocalFolder(folder("usb").toString(), setUpNewStore = true)
                val backend = device.manager.list().single()
                device.store.backends.recordAttempt(
                    backend.id,
                    SyncAttempt(
                        Instant.fromEpochMilliseconds(0),
                        Outcome.SYNCED.name,
                        "Synced.",
                        listOf("File 3 of device 12345678 is damaged."),
                    ),
                    true,
                )
                val text = DebugReport(app, null).build(device.manager)
                assertContains(text, "  issue: File 3 of device 12345678 is damaged.")
                assertContains(text, "  last success: 1970-01-01T00:00:00Z")
            }
        }

    @Test
    fun theLastSyncIsScrubbedOfUrlPathsAndTheHomeDirectory() =
        test {
            Device(this, "a").use { device ->
                device.manager.addLocalFolder(folder("usb").toString(), setUpNewStore = true)
                val backend = device.manager.list().single()
                device.store.backends.recordAttempt(
                    backend.id,
                    SyncAttempt(
                        Instant.fromEpochMilliseconds(0),
                        Outcome.UNREACHABLE.name,
                        "Cannot be reached: GET https://me:pw@dav.example.org/files/me/jotter/ " +
                            "failed. /home/me/usb is missing.",
                        listOf("token: abc def"),
                    ),
                    false,
                )
                val text = DebugReport(app, null, home = "/home/me").build(device.manager)
                assertContains(
                    text,
                    "Cannot be reached: GET https://dav.example.org failed. ~/usb is missing.",
                )
                assertContains(text, "  issue: token: <redacted>")
                assertFalse("/files/me" in text)
                assertFalse("abc def" in text)
            }
        }

    @Test
    fun backendsThatCannotBeReadAreSaidSo() =
        test {
            val device = Device(this, "a")
            device.close()
            val text = DebugReport(app, null).build(device.manager)
            assertContains(text, "Backends: cannot be read.")
        }

    @Test
    fun whyTheNotesCouldNotBeOpenedIsIncluded() =
        test {
            val text = DebugReport(app, null).build(null, "The disk is full.")
            assertContains(text, "The notes could not be opened: The disk is full.")
        }

    @Test
    fun theNewestLogLinesOfEachAppAreIncludedAndRedacted() =
        test {
            val logs = path / "logs"
            listOf("cli", "desktop").forEach { name ->
                RotatingFileSink(logs, name).use { sink ->
                    repeat(3) {
                        sink.write(
                            LogRecord(
                                Instant.fromEpochMilliseconds(0),
                                LogLevel.INFO,
                                name,
                                "line $it password=hunter2",
                                null,
                            ),
                        )
                    }
                }
            }
            val text = DebugReport(app, LogFiles(logs), recentBytes = 80).build(null)
            assertContains(text, "Log: cli (the newest 0 KiB)")
            assertContains(text, "Log: desktop")
            assertContains(text, "desktop: line 2")
            assertFalse("desktop: line 0" in text)
            assertFalse("hunter2" in text)
            assertTrue(text.indexOf("Log: cli") < text.indexOf("Log: desktop"))
        }
}
