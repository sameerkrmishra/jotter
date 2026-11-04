package io.github.sameerkrmishra.jotter.app.desktop

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val AWT_WAIT_MS = 2_000L
private const val HALT_AFTER_MS = 5_000L
private const val POLL_MS = 20L

/**
 * Ends the process once the app has shut down. Exiting at once after the window is disposed can
 * deadlock: the exiting VM runs libGLX's destructor, which waits for the Xlib display lock, while
 * AWT's toolkit thread holds it and is itself stopped at the exit's safepoint. It happened in about
 * one run in five on Xvfb, and kept the process, and the hub's port, alive.
 *
 * So [exit] first waits, at most [awtWaitMs], for AWT to shut itself down, and starts a daemon
 * watchdog that halts the process if it is still alive [haltAfterMs] later. A halt skips shutdown
 * hooks and finalization, which is safe only because the editor was saved, the sync scope (with
 * the scheduler and the hub) finished and the store closed before: [closed] records that, and
 * [exit] refuses to run without it.
 */
internal class Shutdown(
    private val awtAlive: () -> Boolean = ::awtThreadsAlive,
    private val terminate: (Int) -> Unit = { exitProcess(it) },
    private val halt: (Int) -> Unit = {
        // On stderr, so that a log, or the smoke test, can tell a halt from a clean exit.
        System.err.println("jotter: exit did not finish, halting")
        Runtime.getRuntime().halt(it)
    },
    private val awtWaitMs: Long = AWT_WAIT_MS,
    private val haltAfterMs: Long = HALT_AFTER_MS,
) {
    private val isClosed = AtomicBoolean(false)

    /** Called once everything that holds data is saved and closed. */
    fun closed() {
        isClosed.set(true)
    }

    fun exit(status: Int) {
        check(isClosed.get()) { "The process may only exit after the store is closed" }
        val watchdog =
            Thread({
                Thread.sleep(haltAfterMs)
                halt(status)
            }, "jotter-exit-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
        val deadline = System.nanoTime() + awtWaitMs * 1_000_000
        while (awtAlive() && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
        }
        terminate(status)
    }
}

/** The threads AWT ends by itself once no window is left and its event queue is empty. */
internal fun awtThreadsAlive(): Boolean =
    Thread.getAllStackTraces().keys.any {
        it.isAlive && (it.name.startsWith("AWT-EventQueue") || it.name == "AWT-Shutdown")
    }
