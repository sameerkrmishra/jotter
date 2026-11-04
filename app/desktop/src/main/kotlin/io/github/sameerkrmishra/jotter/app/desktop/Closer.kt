package io.github.sameerkrmishra.jotter.app.desktop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val KEEP_TIMEOUT_MS = 3_000L
private const val STEP_TIMEOUT_MS = 2_000L

/**
 * Closes what holds the user's data, in order and once, whichever asks first: the window's close,
 * after the editor was saved, or a signal (SIGTERM at logout or shutdown, SIGINT, SIGHUP), through
 * a JVM shutdown hook, which saves first. The order: [keep] what the user typed (the signal path
 * only), [finish] the app's scope (sync and the scheduler), [stopHub] (its port, keeping the
 * user's setting), then [closeStore].
 *
 * Each step is bounded, as the system kills a process that ignores SIGTERM for long: at most
 * about 7 s in all. The store is closed only if the scope finished, as no query may run on a
 * closed store; otherwise the process ends with it open, which SQLite survives.
 *
 * Both paths take the same lock. A signal during the window's close waits for it, then finds
 * everything closed; the window's path then exits, which waits for the hook, which returns at once.
 */
internal class Closer(
    private val keep: suspend () -> Unit,
    private val finish: suspend () -> Unit,
    private val stopHub: suspend () -> Unit,
    private val closeStore: () -> Unit,
    private val keepTimeoutMs: Long = KEEP_TIMEOUT_MS,
    // For each of finish and stopHub.
    private val stepTimeoutMs: Long = STEP_TIMEOUT_MS,
) {
    private val lock = Mutex()
    private var closed = false
    private var storeClosed = false

    /**
     * Returns whether the store is closed. [saveFirst] when the editor has not saved yet. Runs
     * once: a later call waits for the first and returns its result.
     */
    suspend fun close(saveFirst: Boolean): Boolean =
        lock.withLock {
            if (closed) return@withLock storeClosed
            if (saveFirst) withTimeoutOrNull(keepTimeoutMs) { keep() }
            val finished = withTimeoutOrNull(stepTimeoutMs) { finish() } != null
            withTimeoutOrNull(stepTimeoutMs) { stopHub() }
            if (finished) {
                closeStore()
                storeClosed = true
            }
            // Once: a second attempt would find the same scope that did not finish.
            closed = true
            storeClosed
        }
}
