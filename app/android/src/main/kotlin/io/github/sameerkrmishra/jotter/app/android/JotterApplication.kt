package io.github.sameerkrmishra.jotter.app.android

import android.app.Application

/**
 * The app's process. It holds the one [AppGraph] that the Activity, the share target and
 * [SyncWorker] share, made on first use, so that a process started by WorkManager opens nothing it
 * does not need.
 */
public class JotterApplication : Application() {
    private var current: AppGraph? = null

    /** The composition root of this process. */
    internal val graph: AppGraph
        get() = synchronized(this) { current ?: AppGraph(this).also { current = it } }

    /** For tests: [graph] is [replacement] from now on. The caller closes the old one, if any. */
    internal fun replaceGraph(replacement: AppGraph) {
        synchronized(this) { current = replacement }
    }
}
