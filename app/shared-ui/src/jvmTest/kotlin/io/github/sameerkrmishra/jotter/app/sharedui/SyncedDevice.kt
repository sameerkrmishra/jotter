package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.NoSecretStore
import io.github.sameerkrmishra.jotter.app.sync.StandardBackendFactory
import io.github.sameerkrmishra.jotter.app.sync.SyncScheduler
import io.github.sameerkrmishra.jotter.app.sync.SyncTiming
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.DeviceInfo
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * What the desktop app's composition root builds for sync, on a database of its own: the real
 * engine, backend manager and scheduler, with real dispatchers, syncing [AFTER_EDIT_MS] after an
 * edit. [start] starts the scheduler, [close] stops it as the app does on exit and closes the
 * store.
 */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class SyncedDevice(
    name: String,
) : AutoCloseable {
    private val database = TestDatabase()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store: NoteStore = database.open(Dispatchers.IO)
    val file: String get() = database.file
    private val engine = SyncEngine(store, DeviceInfo(name, "test"), Dispatchers.IO)
    val manager =
        BackendManager(store, engine, StandardBackendFactory, NoSecretStore, Dispatchers.IO)
    val scheduler =
        SyncScheduler(
            manager::syncAll,
            SyncTiming(afterEdit = AFTER_EDIT_MS.milliseconds, period = 10.minutes),
        )
    val controls = SyncControls(manager, scheduler)

    fun start() {
        scheduler.start(scope, store.notes.observeRevisionMark())
    }

    override fun close() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        store.close()
        database.delete()
    }

    companion object {
        const val AFTER_EDIT_MS = 200L
    }
}
