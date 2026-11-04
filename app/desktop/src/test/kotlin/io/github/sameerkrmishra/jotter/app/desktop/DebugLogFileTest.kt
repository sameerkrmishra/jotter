package io.github.sameerkrmishra.jotter.app.desktop

import io.github.sameerkrmishra.jotter.app.sharedui.DebugLogExport
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalPathApi::class)
class DebugLogFileTest {
    private val directory = createTempDirectory("jotter-desktop-debug")
    private val file = directory.resolve(DEBUG_LOG_FILE_NAME)

    @AfterTest
    fun deleteEverything() {
        directory.deleteRecursively()
    }

    @Test
    fun theChosenFileGetsTheText() =
        runBlocking {
            val said = DebugLogFile({ file }, EmptyCoroutineContext).export("the log")
            assertEquals("the log", file.readText())
            assertContains(said.orEmpty(), "Saved the debug log to $file")
        }

    @Test
    fun aCancelledChoiceWritesNothing() =
        runBlocking {
            assertNull(DebugLogFile({ null }, EmptyCoroutineContext).export("the log"))
        }

    @Test
    fun anExistingFileIsLeftAlone() =
        runBlocking {
            file.writeText("keep")
            val said = DebugLogFile({ file }, EmptyCoroutineContext).export("the log")
            assertEquals("keep", file.readText())
            assertContains(said.orEmpty(), "Choose another name")
        }

    @Test
    fun theDebugLogNamesTheDesktopAppAndWhyTheNotesDidNotOpen() =
        runBlocking {
            val controls =
                desktopDebugLog(
                    directory.toOkioPath(),
                    null,
                    "The disk is full.",
                    DebugLogExport { null },
                )
            val text = controls.report()
            assertContains(text, "App: desktop ${desktopVersion()}")
            assertContains(text, "The notes could not be opened: The disk is full.")
            assertEquals(SAVE_DEBUG_LOG, controls.exportLabel)
        }

    @Test
    fun thePlatformNamesTheSystemAndJava() {
        val properties =
            mapOf(
                "os.name" to "Linux",
                "os.version" to "6",
                "os.arch" to "x",
                "java.version" to "21",
            )
        assertEquals("Linux 6 (x), Java 21", desktopPlatform(properties::get))
    }
}
