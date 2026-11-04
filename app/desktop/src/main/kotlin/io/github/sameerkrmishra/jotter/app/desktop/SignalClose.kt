package io.github.sameerkrmishra.jotter.app.desktop

import kotlinx.coroutines.runBlocking

/**
 * What a signal (SIGTERM at logout or shutdown, SIGINT, SIGHUP) closes, from the start of `main()`:
 * its JVM shutdown hook is registered before the store opens, so that no signal finds an open
 * store without a way to close it.
 *
 * Until [attach], a signal closes only the store, if [storeOpened] said there is one: nothing else
 * runs yet, as the app attaches its [Closer] before it starts sync, the hub or the window. After,
 * it runs the [Closer], which saves first. Then [afterClose] (the exit's watchdog and the wait for
 * AWT) runs, only once the store is closed, or left to SQLite as [Closer] explains.
 *
 * The hook runs on every exit, also after the window's close, which ran the [Closer] already: then
 * it finds everything closed and only waits for AWT.
 */
internal class SignalClose(
    private val afterClose: () -> Unit,
) {
    private val lock = Any()
    private var closeStore: (() -> Unit)? = null
    private var closer: Closer? = null

    /** The store is open, and [close] closes it while nothing else uses it. */
    fun storeOpened(close: () -> Unit) {
        synchronized(lock) { closeStore = close }
    }

    /** From now on a signal runs [closer]. */
    fun attach(closer: Closer) {
        synchronized(lock) { this.closer = closer }
    }

    /** The shutdown hook's work. */
    fun onSignal() {
        val (attached, bareStore) =
            synchronized(lock) { closer to closeStore }
        if (attached != null) {
            runBlocking { attached.close(saveFirst = true) }
        } else {
            bareStore?.invoke()
        }
        afterClose()
    }

    /** Registers [onSignal] as a JVM shutdown hook. */
    fun install() {
        Runtime.getRuntime().addShutdownHook(Thread(::onSignal, "jotter-close-on-signal"))
    }
}
