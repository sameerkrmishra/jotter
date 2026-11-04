package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.core.logging.LogFiles
import io.github.sameerkrmishra.jotter.core.logging.LogLevel
import io.github.sameerkrmishra.jotter.core.logging.Logger
import io.github.sameerkrmishra.jotter.core.logging.Logging
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LinuxLogsTest {
    @AfterTest
    fun uninstall() {
        Logging.uninstall()
    }

    @Test
    fun logsAreInTheStateDirectory() {
        val env = mapOf("XDG_STATE_HOME" to "/state", "HOME" to "/home/me")
        assertEquals("/state/jotter/logs".toPath(), linuxLogDirectory(env::get))
        assertEquals(
            "/home/me/.local/state/jotter/logs".toPath(),
            linuxLogDirectory(mapOf("HOME" to "/home/me", "XDG_STATE_HOME" to "rel")::get),
        )
        assertNull(linuxLogDirectory(mapOf("HOME" to "relative")::get))
        assertNull(linuxLogDirectory { null })
    }

    @Test
    fun withoutAHomeNothingIsInstalled() {
        assertNull(installLinuxLogging("cli") { null })
    }

    @Test
    fun theLevelComesFromTheEnvironment() {
        TempDir().use { dir ->
            val env = mapOf("XDG_STATE_HOME" to dir.path.toString(), LOG_LEVEL_ENVVAR to "warn")
            val logs = installLinuxLogging("cli", env::get)
            assertEquals(LogLevel.WARN, Logging.level)
            Logger("test").info { "not written" }
            Logger("test").warn { "written" }
            val text = LogFiles(checkNotNull(logs)).recent("cli")
            assertContains(text, "WARN test: written")
            assertFalse("not written" in text)
            installLinuxLogging("cli", mapOf("XDG_STATE_HOME" to dir.path.toString())::get)
            assertEquals(LogLevel.INFO, Logging.level)
        }
    }
}
