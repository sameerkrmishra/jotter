package io.github.sameerkrmishra.jotter.app.desktop

import io.github.sameerkrmishra.jotter.app.sharedui.DebugLogControls
import io.github.sameerkrmishra.jotter.app.sharedui.DebugLogExport
import io.github.sameerkrmishra.jotter.app.sync.AppInfo
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.DebugReport
import io.github.sameerkrmishra.jotter.core.logging.LogFiles
import kotlinx.coroutines.withContext
import okio.Path
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.coroutines.CoroutineContext
import java.nio.file.Path as JavaPath

internal const val SAVE_DEBUG_LOG = "Save to a file…"

/** The suggested name of a saved debug log. */
internal const val DEBUG_LOG_FILE_NAME = "jotter-debug-log.txt"

/**
 * Saves a debug log to the file the user chooses with [choose], which returns null if they
 * cancelled. An existing file is never overwritten: the user is asked to choose another name.
 *
 * @param io Where the file is written.
 */
internal class DebugLogFile(
    private val choose: suspend () -> JavaPath?,
    private val io: CoroutineContext,
) : DebugLogExport {
    override suspend fun export(text: String): String? {
        val file = choose() ?: return null
        return withContext(io) {
            try {
                Files.writeString(file, text, StandardOpenOption.CREATE_NEW)
                "Saved the debug log to $file. Attach that file to your issue."
            } catch (_: FileAlreadyExistsException) {
                "$file exists already, so Jotter left it alone. Choose another name."
            }
        }
    }
}

/** The system, for a debug log: `Linux 6.18.5 (amd64), Java 21.0.9`. */
internal fun desktopPlatform(property: (String) -> String? = System::getProperty): String =
    "${property("os.name")} ${property("os.version")} (${property("os.arch")}), " +
        "Java ${property("java.version")}"

/** The version in the jar's manifest, or `unknown` when run from classes, as in tests. */
internal fun desktopVersion(): String =
    DebugLogFile::class.java.`package`?.implementationVersion ?: "unknown"

/**
 * The debug log of the desktop app: its own log and the CLI's, which share [logs], and the
 * backends of [manager] if the notes are open, or [openProblem] if they could not be.
 */
internal fun desktopDebugLog(
    logs: Path?,
    manager: BackendManager?,
    openProblem: String?,
    export: DebugLogExport,
): DebugLogControls {
    val report =
        DebugReport(AppInfo("desktop", desktopVersion(), desktopPlatform()), logs?.let(::LogFiles))
    return DebugLogControls({ report.build(manager, openProblem) }, export, SAVE_DEBUG_LOG)
}
