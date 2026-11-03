package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.DeviceInfo
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import okio.FileSystem
import okio.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/** A fresh temporary directory, deleted again by [close]. */
internal class TempDir : AutoCloseable {
    val path: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "jotter-app-sync-test-${Random.nextLong().toULong()}"

    init {
        FileSystem.SYSTEM.createDirectories(path)
    }

    /** Creates the directory [name] in it and returns its path. */
    fun folder(name: String): Path = (path / name).also { FileSystem.SYSTEM.createDirectories(it) }

    override fun close() = FileSystem.SYSTEM.deleteRecursively(path)
}

/** A wall clock that the test sets by hand. */
internal class FakeClock(
    var millis: Long = 1_000_000,
) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
}

/** The secret store of tests: secrets in memory, and a record of what was deleted. */
internal class InMemorySecretStore : SecretStore {
    val secrets = mutableMapOf<SecretKey, Secret>()

    override suspend fun read(key: SecretKey): Secret? = secrets[key]

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

/**
 * One device: a database in [dir] and a [BackendManager] over it. Everything runs on the caller's
 * thread, or on [io] if given.
 */
internal class Device(
    dir: TempDir,
    name: String,
    val clock: FakeClock = FakeClock(),
    factory: BackendFactory = StandardBackendFactory,
    val secrets: SecretStore = InMemorySecretStore(),
    io: CoroutineContext = EmptyCoroutineContext,
) : AutoCloseable {
    // In a folder of its own: a backend folder may not be in the database's folder.
    val file = (dir.folder("db-$name") / "$name.db").toString()
    val store: NoteStore = NoteStore.open(file, name, io, clock)
    val engine = SyncEngine(store, DeviceInfo(name, "test"), io, clock)
    val manager = BackendManager(store, engine, factory, secrets, io, clock)

    override fun close() = store.close()
}

/** Opens in-memory stores by the backend's name, for tests that need no files. */
internal class NamedStores(
    private val stores: Map<String, BlobStore>,
) : BackendFactory {
    override suspend fun open(backend: Backend): BlobStore = stores.getValue(backend.name)
}
