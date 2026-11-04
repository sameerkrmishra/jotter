package io.github.sameerkrmishra.jotter.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.sameerkrmishra.jotter.app.sharedui.AppViewModel
import io.github.sameerkrmishra.jotter.app.sharedui.CannotOpenNotes
import io.github.sameerkrmishra.jotter.app.sharedui.HubSettings
import io.github.sameerkrmishra.jotter.app.sharedui.JotterApp
import io.github.sameerkrmishra.jotter.app.sharedui.PeerHubControls
import io.github.sameerkrmishra.jotter.app.sharedui.SyncControls
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.StandardBackendFactory
import io.github.sameerkrmishra.jotter.app.sync.SyncScheduler
import io.github.sameerkrmishra.jotter.app.sync.installLinuxLogging
import io.github.sameerkrmishra.jotter.app.sync.linuxSecretStores
import io.github.sameerkrmishra.jotter.backend.peer.PeerProtocol
import io.github.sameerkrmishra.jotter.core.logging.Logger
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import kotlin.system.exitProcess

private const val AUTOCLOSE_ARG = "--autoclose"
private const val AUTOCLOSE_DELAY_MS = 4_000L
private const val VERSION_ARG = "--version"

/**
 * The desktop app and its composition root (ADR-0005). With `--autoclose` it closes itself a few
 * seconds after the window appears, to prove that a build starts. `--version` prints the version
 * and exits without a window or the database, so that a package can be checked headless.
 */
public fun main(args: Array<String>) {
    if (VERSION_ARG in args) {
        // jpackage's launcher sets it from packageVersion; `gradlew run` does not.
        println("jotter ${System.getProperty("jpackage.app-version") ?: "dev"}")
        exitProcess(0)
    }
    val autoclose = AUTOCLOSE_ARG in args
    val shutdown = Shutdown()
    // Before the store opens, so that a signal at any time finds a way to close it.
    val signals =
        SignalClose {
            shutdown.closed()
            shutdown.afterSignal()
        }
    signals.install()
    val logs = installLinuxLogging("desktop", System::getenv)
    logUncaughtFailures()
    log.info { "Jotter ${desktopVersion()} starts on ${desktopPlatform()}" }
    val store =
        try {
            openStore()
        } catch (e: DatabaseUnavailableException) {
            showFailure(e.message.orEmpty(), autoclose, logs)
            // Nothing was opened.
            shutdown.closed()
            shutdown.exit(1)
            return
        }
    signals.storeOpened(store::close)
    if (!runApp(store, autoclose, logs, signals)) {
        // SQLite undoes the write that did not finish when the process ends.
        log.warn { "Jotter exits with the store open, as sync did not stop" }
    }
    log.info { "Jotter exits" }
    // The editor was saved before the window closed, and runApp stopped sync and the hub and
    // closed the store, or left it to SQLite if sync would not stop; only now may the process
    // end, if need be by a halt.
    shutdown.closed()
    shutdown.exit(0)
}

private val log = Logger("desktop")

/** A failure no code caught, on any thread, goes to the log as well as where it went before. */
private fun logUncaughtFailures() {
    val before = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        log.error(failure) { "Uncaught failure on thread ${thread.name}" }
        before?.uncaughtException(thread, failure) ?: failure.printStackTrace()
    }
}

/** Asks where to save the debug log, with the desktop's own file dialog. */
private suspend fun chooseDebugLogFile(): Path? =
    withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, "Save the debug log", FileDialog.SAVE)
        dialog.file = DEBUG_LOG_FILE_NAME
        dialog.isVisible = true
        dialog.file?.let { Path.of(dialog.directory, it) }
    }

@Suppress("InjectDispatcher") // This is where dispatchers are chosen, to inject them elsewhere.
private val debugLogFile = DebugLogFile(::chooseDebugLogFile, Dispatchers.IO)

@Suppress("InjectDispatcher") // This is where dispatchers are chosen, to inject them elsewhere.
private fun openStore() =
    openNoteStore(System::getenv, System.getProperty("user.home"), Dispatchers.IO)

// Also on stderr, for a terminal or a log. If there is no display, this at least was printed.
private fun showFailure(
    reason: String,
    autoclose: Boolean,
    logs: okio.Path?,
) {
    System.err.println("jotter: $reason")
    val debugLog = desktopDebugLog(logs, null, reason, debugLogFile)
    application(exitProcessOnExit = false) {
        Window(onCloseRequest = ::exitApplication, title = "Jotter") {
            AutoClose(autoclose, ::exitApplication)
            CannotOpenNotes(reason, debugLog, ::exitApplication)
        }
    }
}

/**
 * The store is closed after the scope is finished, as no query may still be running, and after the
 * hub is stopped, by [Closer]: when the window closes, or first, on SIGTERM, SIGINT or SIGHUP,
 * through [signals]' shutdown hook, which also saves what the user typed. The [Closer] is attached
 * before sync, the hub or the window start. `application` must not exit the process itself, or
 * that would never happen; `main()` does, through [Shutdown], which also ends it if exiting hangs,
 * as spike 0002 saw once and Xvfb runs showed in one in five. Returns whether the store is closed.
 */
@Suppress("InjectDispatcher") // This is where dispatchers are chosen, to inject them elsewhere.
private fun runApp(
    store: NoteStore,
    autoclose: Boolean,
    logs: okio.Path?,
    signals: SignalClose,
): Boolean {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val engine = SyncEngine(store, DeviceInfo(DEVICE_NAME, "linux"), Dispatchers.IO)
    // The keyring through the Secret Service, or the file the user chose (ADR-0016).
    val secrets = linuxSecretStores(System::getenv, System.getProperty("user.home"), Dispatchers.IO)
    val manager =
        BackendManager(
            store,
            engine,
            StandardBackendFactory(secrets),
            secrets,
            Dispatchers.IO,
        )
    val scheduler = SyncScheduler(manager::syncAll)
    val hub = hubControls(manager, scope)
    var app: AppViewModel? = null
    val closer =
        Closer(
            keep = { app?.keep()?.join() },
            finish = { scope.coroutineContext.job.cancelAndJoin() },
            // Stops it, but keeps the user's setting, so that it starts again with the app.
            stopHub = { hub.stop() },
            closeStore = store::close,
        )
    signals.attach(closer)
    // A hub the user left on starts again with the app.
    scope.launch { hub.resume() }
    // In the app's scope, so that exit cancels a running sync before the store closes.
    scheduler.start(scope, store.notes.observeRevisionMark())
    val viewModel =
        AppViewModel(
            store.notes,
            ConflictResolver(store),
            scope,
            Dispatchers.IO,
            SyncControls(manager, scheduler, hub),
            desktopDebugLog(logs, manager, null, debugLogFile),
        )
    app = viewModel
    application(exitProcessOnExit = false) {
        val close = { viewModel.requestExit(::exitApplication) }
        Window(onCloseRequest = close, title = "Jotter") {
            AutoClose(autoclose, close)
            JotterApp(viewModel)
        }
    }
    return runBlocking { closer.close(saveFirst = false) }
}

/**
 * The hub on this PC (ADR-0015). Its settings and its paired devices, as hashes, are in
 * `$XDG_CONFIG_HOME/jotter`; its store is by default `$XDG_DATA_HOME/jotter-hub`, outside the
 * database's folder, which may hold no backend.
 */
@Suppress("InjectDispatcher") // This is where dispatchers are chosen, to inject them elsewhere.
private fun hubControls(
    manager: BackendManager,
    scope: CoroutineScope,
): PeerHubControls {
    val home =
        System.getenv("HOME")?.takeIf { it.startsWith('/') } ?: System.getProperty("user.home")
    val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith('/') } ?: "$home/.config"
    val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith('/') } ?: "$home/.local/share"
    return PeerHubControls(
        manager,
        Path.of(config, "jotter", "hub.properties"),
        Path.of(config, "jotter", "hub-devices.json"),
        scope,
        Dispatchers.IO,
        HubSettings("$data/jotter-hub", "127.0.0.1", PeerProtocol.DEFAULT_PORT),
    )
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
