package io.github.sameerkrmishra.jotter.app.android

import io.github.sameerkrmishra.jotter.app.sharedui.DebugLogControls
import io.github.sameerkrmishra.jotter.app.sharedui.DebugLogExport
import io.github.sameerkrmishra.jotter.app.sync.AppInfo
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.DebugReport
import io.github.sameerkrmishra.jotter.core.logging.LogFiles
import okio.Path
import kotlin.concurrent.Volatile

/** The log's folder under `noBackupFilesDir`: no backup or device transfer copies it. */
internal const val LOG_FOLDER = "logs"

/** The log's name: `android.log`. */
internal const val LOG_NAME = "android"

/** The suggested name of a saved debug log. */
internal const val DEBUG_LOG_FILE_NAME = "jotter-debug-log.txt"

internal const val SAVE_DEBUG_LOG = "Save…"

/** What the button says while no screen can show Android's document picker. */
internal const val NO_SCREEN_TO_SAVE =
    "Jotter is not on the screen, so it cannot ask where to save the debug log. Open it and try " +
        "again."

/** The system, for a debug log: `Android 16 (API 36), Google Pixel 8`. */
internal fun androidPlatform(
    release: String?,
    sdk: Int,
    manufacturer: String?,
    model: String?,
): String =
    "Android ${release ?: "?"} (API $sdk), ${manufacturer.orEmpty()} ${model.orEmpty()}".trim()

/**
 * The export of the screen on top: the debug log is shown in a view model that outlives an
 * Activity, which turning the phone replaces, but only an Activity can show Android's document
 * picker. Each Activity sets itself as [screen] while it exists.
 */
internal class ScreenExport : DebugLogExport {
    /** The Activity's export, or null while there is none. */
    @Volatile
    var screen: DebugLogExport? = null

    override suspend fun export(text: String): String? {
        val current = screen ?: return NO_SCREEN_TO_SAVE
        return current.export(text)
    }
}

/**
 * The debug log of the Android app: its log in [logs], and the backends of [manager] if the notes
 * are open, or [openProblem] if they could not be.
 */
internal fun androidDebugLog(
    app: AppInfo,
    logs: Path,
    manager: BackendManager?,
    openProblem: String?,
    export: DebugLogExport,
): DebugLogControls {
    val report = DebugReport(app, LogFiles(logs))
    return DebugLogControls({ report.build(manager, openProblem) }, export, SAVE_DEBUG_LOG)
}
