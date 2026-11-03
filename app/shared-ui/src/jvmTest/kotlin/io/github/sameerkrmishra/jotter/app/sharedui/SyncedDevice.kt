package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.Secret
import io.github.sameerkrmishra.jotter.app.sync.SecretKey
import io.github.sameerkrmishra.jotter.app.sync.SecretStore
import io.github.sameerkrmishra.jotter.app.sync.SecretStores
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
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
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
    val secrets: TestSecrets = TestSecrets(),
    hubFolder: Path? = null,
) : AutoCloseable {
    private val database = TestDatabase()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store: NoteStore = database.open(Dispatchers.IO)
    val file: String get() = database.file
    private val engine = SyncEngine(store, DeviceInfo(name, "test"), Dispatchers.IO)
    val manager =
        BackendManager(
            store,
            engine,
            StandardBackendFactory(secrets.stores),
            secrets.stores,
            Dispatchers.IO,
        )
    val scheduler =
        SyncScheduler(
            manager::syncAll,
            SyncTiming(afterEdit = AFTER_EDIT_MS.milliseconds, period = 10.minutes),
        )

    /** The hub of this device, if [hubFolder] was given: on 127.0.0.1 and a free port. */
    val hub: PeerHubControls? =
        hubFolder?.let {
            PeerHubControls(
                manager,
                it.resolve("hub.properties"),
                it.resolve("hub-devices.json"),
                scope,
                Dispatchers.IO,
                HubSettings(it.resolve("store").toString(), "127.0.0.1", 0),
            )
        }
    val controls = SyncControls(manager, scheduler, hub)

    fun start() {
        scheduler.start(scope, store.notes.observeRevisionMark())
    }

    override fun close() {
        runBlocking {
            hub?.turnOff()
            scope.coroutineContext.job.cancelAndJoin()
        }
        store.close()
        database.delete()
    }

    companion object {
        const val AFTER_EDIT_MS = 200L
    }
}

/** Secrets in memory, in place of this user's keyring and of the file a user may choose. */
class TestSecrets(
    val keyring: MemorySecrets = MemorySecrets(),
    val file: MemorySecrets = MemorySecrets(),
    stores: SecretStores? = null,
) {
    val stores: SecretStores = stores ?: SecretStores(keyring, file, "/config/jotter/secrets.json")
}

/** A [SecretStore] in memory. */
class MemorySecrets : SecretStore {
    val secrets =
        ConcurrentHashMap<
            SecretKey,
            Secret,
        >()

    override suspend fun read(key: SecretKey) = secrets[key]

    override suspend fun write(
        key: SecretKey,
        secret: Secret,
    ) {
        secrets[key] = secret
    }

    override suspend fun delete(key: SecretKey) {
        secrets.remove(key)
    }
}
