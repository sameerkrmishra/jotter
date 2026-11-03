package io.github.sameerkrmishra.jotter.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.sameerkrmishra.jotter.app.sharedui.AppViewModel
import io.github.sameerkrmishra.jotter.app.sharedui.CannotOpenNotes
import io.github.sameerkrmishra.jotter.app.sharedui.JotterApp
import io.github.sameerkrmishra.jotter.app.sharedui.SyncControls
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.NoSecretStore
import io.github.sameerkrmishra.jotter.app.sync.StandardBackendFactory
import io.github.sameerkrmishra.jotter.app.sync.SyncScheduler
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import io.github.sameerkrmishra.jotter.core.sync.DeviceInfo
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
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
 */
public fun main(args: Array<String>) {
    val autoclose = AUTOCLOSE_ARG in args
    val store =
        try {
            openStore()
        } catch (e: DatabaseUnavailableException) {
            showFailure(e.message.orEmpty(), autoclose)
            exitProcess(1)
        }
    runApp(store, autoclose)
    exitProcess(0)
}

@Suppress("InjectDispatcher") // This is where dispatchers are chosen, to inject them elsewhere.
private fun openStore() =
    openNoteStore(System::getenv, System.getProperty("user.home"), Dispatchers.IO)

// Also on stderr, for a terminal or a log. If there is no display, this at least was printed.
private fun showFailure(
    reason: String,
    autoclose: Boolean,
) {
    System.err.println("jotter: $reason")
    application(exitProcessOnExit = false) {
        Window(onCloseRequest = ::exitApplication, title = "Jotter") {
            AutoClose(autoclose, ::exitApplication)
            CannotOpenNotes(reason, ::exitApplication)
        }
    }
}

/**
 * The store is closed after the scope is finished, as no query may still be running. `application`
 * must not exit the process itself, or that would never happen; `main()` does, which also ends it
 * if an AWT thread hangs, as spike 0002 saw once.
 */
@Suppress("InjectDispatcher") // This is where dispatchers are chosen, to inject them elsewhere.
private fun runApp(
    store: NoteStore,
    autoclose: Boolean,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val engine = SyncEngine(store, DeviceInfo(DEVICE_NAME, "linux"), Dispatchers.IO)
    val manager =
        BackendManager(store, engine, StandardBackendFactory, NoSecretStore, Dispatchers.IO)
    val scheduler = SyncScheduler(manager::syncAll)
    // In the app's scope, so that exit cancels a running sync before the store closes.
    scheduler.start(scope, store.notes.observeRevisionMark())
    val app =
        AppViewModel(
            store.notes,
            ConflictResolver(store),
            scope,
            Dispatchers.IO,
            SyncControls(manager, scheduler),
        )
    application(exitProcessOnExit = false) {
        val close = { app.requestExit(::exitApplication) }
        Window(onCloseRequest = close, title = "Jotter") {
            AutoClose(autoclose, close)
            JotterApp(app)
        }
    }
    runBlocking { scope.coroutineContext.job.cancelAndJoin() }
    store.close()
}

@Composable
private fun AutoClose(
    enabled: Boolean,
    close: () -> Unit,
) {
    if (enabled) {
        LaunchedEffect(Unit) {
            delay(AUTOCLOSE_DELAY_MS)
            println("jotter: window shown, closing")
            close()
        }
    }
}
