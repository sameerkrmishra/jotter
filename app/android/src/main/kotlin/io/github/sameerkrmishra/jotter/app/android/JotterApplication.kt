package io.github.sameerkrmishra.jotter.app.android

import android.app.Application
import io.github.sameerkrmishra.jotter.core.logging.JavaLogging
import io.github.sameerkrmishra.jotter.core.logging.Logger
import io.github.sameerkrmishra.jotter.core.logging.Logging
import io.github.sameerkrmishra.jotter.core.logging.RotatingFileSink
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * The app's process. It holds the one [AppGraph] that the Activity, the share target and
 * [SyncWorker] share, made on first use, so that a process started by WorkManager opens nothing it
 * does not need.
 */
public class JotterApplication : Application() {
    private var current: AppGraph? = null

    override fun onCreate() {
        super.onCreate()
        // Before anything that may log; the file is opened at the first line (ADR-0020).
        Logging.install(RotatingFileSink(File(noBackupFilesDir, LOG_FOLDER).toOkioPath(), LOG_NAME))
        // The hub client's OkHttp logs through java.util.logging.
        JavaLogging.route()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            Logger("android").error(failure) { "Uncaught failure on thread ${thread.name}" }
            before?.uncaughtException(thread, failure)
        }
    }

    /** The composition root of this process. */
    internal val graph: AppGraph
        get() = synchronized(this) { current ?: AppGraph(this).also { current = it } }

    /** For tests: [graph] is [replacement] from now on. The caller closes the old one, if any. */
    internal fun replaceGraph(replacement: AppGraph) {
        synchronized(this) { current = replacement }
    }
}
