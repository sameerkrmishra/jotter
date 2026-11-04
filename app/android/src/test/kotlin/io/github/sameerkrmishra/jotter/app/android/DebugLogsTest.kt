package io.github.sameerkrmishra.jotter.app.android

import io.github.sameerkrmishra.jotter.app.sharedui.DebugLogExport
import io.github.sameerkrmishra.jotter.app.sync.AppInfo
import io.github.sameerkrmishra.jotter.core.logging.LogLevel
import io.github.sameerkrmishra.jotter.core.logging.LogRecord
import io.github.sameerkrmishra.jotter.core.logging.RotatingFileSink
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Instant

class DebugLogsTest {
    @Test
    fun thePlatformNamesAndroidAndThePhone() {
        assertEquals(
            "Android 16 (API 36), Google Pixel 8",
            androidPlatform("16", 36, "Google", "Pixel 8"),
        )
        assertEquals("Android ? (API 26),", androidPlatform(null, 26, null, null))
    }

    @Test
    fun anExportGoesToTheScreenOnTop() =
        runTest {
            val export = ScreenExport()
            assertEquals(NO_SCREEN_TO_SAVE, export.export("log"))
            val saved = mutableListOf<String>()
            export.screen =
                DebugLogExport {
                    saved += it
                    "Saved."
                }
            assertEquals("Saved.", export.export("log"))
            export.screen = DebugLogExport { null }
            assertEquals(null, export.export("log"))
            assertEquals(listOf("log"), saved)
        }

    @Test
    fun theDebugLogHoldsTheAppsLog() =
        runTest {
            val logs = Files.createTempDirectory("jotter-android-logs").toOkioPath()
            RotatingFileSink(logs, LOG_NAME).use {
                it.write(
                    LogRecord(
                        Instant.fromEpochMilliseconds(0),
                        LogLevel.INFO,
                        "android",
                        "started",
                        null,
                    ),
                )
            }
            val controls =
                androidDebugLog(
                    AppInfo("android", "0.1.0", "Android 16 (API 36)"),
                    logs,
                    null,
                    "The disk is full.",
                    ScreenExport(),
                )
            val text = controls.report()
            assertContains(text, "App: android 0.1.0")
            assertContains(text, "The notes could not be opened: The disk is full.")
            assertContains(text, "INFO android: started")
            assertEquals(SAVE_DEBUG_LOG, controls.exportLabel)
        }
}
