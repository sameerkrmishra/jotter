package io.github.sameerkrmishra.jotter.app.desktop

import java.awt.EventQueue
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val AWT_WAIT_MS = 2_000L
private const val HALT_AFTER_MS = 5_000L
private const val POLL_MS = 20L

// The status of a process that a halt ended after a signal: its exit did not finish.
private const val HALTED_AFTER_SIGNAL = 1

/**
 * Ends the process once the app has shut down. Exiting at once after the window is disposed can
 * deadlock: the exiting VM runs libGLX's destructor, which waits for the Xlib display lock, while
 * AWT's toolkit thread holds it and is itself stopped at the exit's safepoint. It happened in about
 * one run in five on Xvfb, and kept the process, and the hub's port, alive.
 *
 * So [exit] first waits, at most [awtWaitMs], for AWT to shut itself down, and starts a daemon
 * watchdog that halts the process if it is still alive [haltAfterMs] later. A halt skips shutdown
 * hooks and finalization, which is safe only because [Closer] ran before: the editor was saved,
 * sync stopped, the hub stopped and the store closed (or, if sync would not stop, left to SQLite,
 * which rolls back a write the process did not finish). [closed] records that, and [exit] and
 * [afterSignal] refuse to run without it.
 *
 * A signal ends the process through the shutdown hooks instead, which then end the VM in the same
 * way; [afterSignal] does for that path what [exit] does: it disposes the windows, so that AWT
 * shuts down, waits for it, and starts the same watchdog.
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
    private val disposeWindows: () -> Unit = {
        EventQueue.invokeLater { Window.getWindows().forEach(Window::dispose) }
    },
) {
    private val isClosed = AtomicBoolean(false)

    /** Called once everything that holds data is saved and closed. */
    fun closed() {
        isClosed.set(true)
    }

    fun exit(status: Int) {
        check(isClosed.get()) { "The process may only exit after the store is closed" }
        startWatchdog(status)
        awaitAwt()
        terminate(status)
    }

    /** In the shutdown hook, after [Closer]: the VM ends when the hook returns. */
    fun afterSignal() {
        check(isClosed.get()) { "The process may only exit after the store is closed" }
        startWatchdog(HALTED_AFTER_SIGNAL)
        disposeWindows()
        awaitAwt()
    }

    private fun startWatchdog(status: Int) {
        val watchdog =
            Thread({
                Thread.sleep(haltAfterMs)
                halt(status)
            }, "jotter-exit-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
    }

    private fun awaitAwt() {
        val deadline = System.nanoTime() + awtWaitMs * 1_000_000
        while (awtAlive() && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
        }
    }
}

/** The threads AWT ends by itself once no window is left and its event queue is empty. */
internal fun awtThreadsAlive(): Boolean =
    Thread.getAllStackTraces().keys.any {
        it.isAlive && (it.name.startsWith("AWT-EventQueue") || it.name == "AWT-Shutdown")
    }
