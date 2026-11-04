package io.github.sameerkrmishra.jotter.app.desktop

import io.github.sameerkrmishra.jotter.core.logging.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val KEEP_TIMEOUT_MS = 5_000L
private const val STEP_TIMEOUT_MS = 5_000L

private val log = Logger("exit")

/**
 * Closes what holds the user's data, in order and once, whichever asks first: the window's close,
 * after the editor was saved, or a signal (SIGTERM at logout or shutdown, SIGINT, SIGHUP), through
 * a JVM shutdown hook, which saves first. The order: [keep] what the user typed (the signal path
 * only), [finish] the app's scope (sync and the scheduler), [stopHub] (its port, keeping the
 * user's setting), then [closeStore].
 *
 * Each step is bounded, as the system kills a process that ignores SIGTERM for long (systemd
 * after 90 s by default): at most about 15 s in all. The store is closed only once sync has
 * stopped, as no query may run on a closed store; if it does not stop in time, the store is left
 * open and the process ends with it, and SQLite undoes the write that did not finish.
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
            log.info { if (saveFirst) "Closing on a signal" else "Closing after the window" }
            if (saveFirst) step("Saving", keepTimeoutMs) { keep() }
            val finished = step("Stopping sync", stepTimeoutMs) { finish() }
            step("Stopping the hub", stepTimeoutMs) { stopHub() }
            if (finished) {
                closeStore()
                storeClosed = true
                log.info { "Closed the store" }
            } else {
                log.warn { "Left the store open, as sync did not stop" }
            }
            // Once: a second attempt would find the same scope that did not finish.
            closed = true
            storeClosed
        }

    private suspend fun step(
        name: String,
        timeoutMs: Long,
        block: suspend () -> Unit,
    ): Boolean {
        val done = withTimeoutOrNull(timeoutMs) { block() } != null
        if (!done) log.warn { "$name took longer than $timeoutMs ms, and was given up" }
        return done
    }
}
