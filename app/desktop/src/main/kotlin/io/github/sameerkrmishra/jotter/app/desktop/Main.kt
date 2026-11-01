package io.github.sameerkrmishra.jotter.app.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.sameerkrmishra.jotter.app.sharedui.AppViewModel
import io.github.sameerkrmishra.jotter.app.sharedui.JotterApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private const val AUTOCLOSE_ARG = "--autoclose"
private const val AUTOCLOSE_DELAY_MS = 4_000L

/**
 * The desktop app and its composition root (ADR-0005). With `--autoclose` it closes itself a few
 * seconds after the window appears, to prove that a build starts.
 *
 * The store is closed after the scope is finished, as no query may still be running. `application`
 * must not exit the process itself, or that would never happen; [exitProcess] then ends the process
 * even if an AWT thread hangs, which spike 0002 saw once.
 */
@Suppress("InjectDispatcher") // This is where dispatchers are chosen, to inject them elsewhere.
public fun main(args: Array<String>) {
    val store =
        try {
            openNoteStore(System::getenv, System.getProperty("user.home"), Dispatchers.IO)
        } catch (e: DatabaseUnavailableException) {
            System.err.println("jotter: ${e.message}")
            exitProcess(1)
        }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val app = AppViewModel(store.notes, scope, Dispatchers.IO)
    application(exitProcessOnExit = false) {
        val close = { app.requestExit(::exitApplication) }
        Window(onCloseRequest = close, title = "Jotter") {
            if (AUTOCLOSE_ARG in args) {
                LaunchedEffect(Unit) {
                    delay(AUTOCLOSE_DELAY_MS)
                    println("jotter: window shown, closing")
                    close()
                }
            }
            JotterApp(app)
        }
    }
    runBlocking { scope.coroutineContext.job.cancelAndJoin() }
    store.close()
    exitProcess(0)
}
