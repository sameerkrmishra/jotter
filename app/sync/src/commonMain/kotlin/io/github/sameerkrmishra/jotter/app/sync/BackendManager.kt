package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.localfolder.LocalFolderBlobStore
import io.github.sameerkrmishra.jotter.core.storage.BackendRecord
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.SyncAttempt
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import io.github.sameerkrmishra.jotter.core.sync.SyncReport
import io.github.sameerkrmishra.jotter.core.sync.SyncResult
import io.github.sameerkrmishra.jotter.core.sync.SyncTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What [BackendManager.addLocalFolder] did. */
public sealed interface AddResult {
    /** The folder is now a backend. */
    public data class Added(
        /** The new backend. */
        val backend: Backend,
    ) : AddResult

    /**
     * The folder exists but is not a Jotter store yet, so nothing was done. Setting it up is for
     * the user to decide, as the folder could be the bare mount point of an unplugged drive
     * (ADR-0012). Ask, and call again with `setUpNewStore` if they say yes.
     */
    public data class NotAStoreYet(
        /** The folder, normalised. */
        val path: String,
    ) : AddResult

    /** The folder is a backend already. */
    public data class AlreadyAdded(
        /** That backend. */
        val backend: Backend,
    ) : AddResult

    /** The folder cannot be added. [reason] says why, in plain words. */
    public data class Refused(
        /** Why. */
        val reason: String,
    ) : AddResult
}

/**
 * Adds, lists, removes and syncs the backends of one database (PLAN.md §6.5). The desktop app and
 * the CLI each make one for their store. It never decides when to sync: [SyncScheduler] or the
 * user does.
 *
 * One operation runs at a time: a removal waits for a running sync, so that the sync cannot
 * record anything for the removed backend afterwards. Across processes, such as the CLI and the
 * app, `SyncEngine` keeps the sync tables consistent on its own.
 *
 * @param store The database.
 * @param engine The sync engine of [store].
 * @param factory Opens a backend.
 * @param secrets Where the backends' secrets are, to delete them with a backend.
 * @param io Where blocking database calls run.
 * @param clock The time of a sync and of adding a backend.
 */
@Suppress("TooManyFunctions") // One function per operation, and the steps of adding a folder.
public class BackendManager(
    private val store: NoteStore,
    private val engine: SyncEngine,
    private val factory: BackendFactory,
    private val secrets: SecretStore,
    private val io: CoroutineContext,
    private val clock: Clock = Clock.System,
) {
    private val operations = Mutex()

    /** Emits the backends now and whenever they or their last sync change through [store]. */
    public fun observe(): Flow<List<Backend>> = store.backends.observe().map { it.map(::backendOf) }

    /** Returns the backends, oldest first. */
    public suspend fun list(): List<Backend> =
        withContext(io) {
            store.backends.list().map(::backendOf)
        }

    /**
     * Adds the folder [path] as a backend called [name], or the folder's own name.
     *
     * A folder that is a Jotter store already, made by another device, is simply added. One that
     * is not is set up as a store only if [setUpNewStore], which only the user may decide: then it
     * gets a hidden marker file, and the next sync copies all of this device's notes into it.
     * Sync never sets up a folder by itself (ADR-0012).
     *
     * @param path An absolute path.
     */
    public suspend fun addLocalFolder(
        path: String,
        name: String? = null,
        setUpNewStore: Boolean = false,
    ): AddResult =
        operations.withLock {
            val folder = normalised(path) ?: return@withLock AddResult.Refused(notAbsolute(path))
            val config = LocalFolderConfig(folder.toString())
            list().firstOrNull { it.config == config }?.let {
                return@withLock AddResult
                    .AlreadyAdded(
                        it,
                    )
            }
            overlap(folder)?.let { return@withLock AddResult.Refused(it) }
            val blobs = LocalFolderBlobStore(folder)
            when (val reachability = blobs.ping()) {
                Reachability.Reachable -> add(blobs, config, name ?: nameOf(folder))
                is Reachability.Unreachable ->
                    when {
                        reachability.reason == UnreachableReason.NOT_INITIALIZED && setUpNewStore ->
                            add(blobs, config, name ?: nameOf(folder))
                        reachability.reason == UnreachableReason.NOT_INITIALIZED ->
                            AddResult.NotAStoreYet(config.path)
                        reachability.reason == UnreachableReason.MISSING ->
                            AddResult.Refused("There is no folder at ${config.path}.")
                        else ->
                            AddResult.Refused(
                                "Cannot use ${config.path}: ${reachability.detail}",
                            )
                    }
            }
        }

    /**
     * Why [folder] cannot be a store, or null if it can. A store must not hold this device's
     * database, or sit in its folder, and two stores must not nest: a store's files would end up
     * in another store, or next to the database. Paths are compared after resolving symbolic
     * links, so another name for the same place is caught too.
     */
    private suspend fun overlap(folder: Path): String? =
        withContext(io) {
            val here = canonical(folder)
            val database = canonical(store.path.toPath(normalize = true)).parent
            val stores =
                list().mapNotNull { it.config as? LocalFolderConfig }.map { it.path.toPath() }
            val enclosing = generateSequence(here.parent) { it.parent }
            when {
                database != null && related(here, database) ->
                    "$folder cannot be a backend: it holds this device's notes database, or is " +
                        "in its folder ($database). Choose a folder apart from it."
                stores.any { related(here, canonical(it)) } -> {
                    val other = stores.first { related(here, canonical(it)) }
                    "$folder cannot be a backend: it is inside the backend folder $other, or " +
                        "holds it. Stores must not nest."
                }
                enclosing.any { files.exists(it / MARKER) } -> {
                    val other = enclosing.first { files.exists(it / MARKER) }
                    "$folder is inside the Jotter store $other. Add $other itself instead."
                }
                else -> null
            }
        }

    private suspend fun add(
        blobs: LocalFolderBlobStore,
        config: LocalFolderConfig,
        name: String,
    ): AddResult {
        try {
            blobs.initialize()
        } catch (e: BlobStoreException) {
            return AddResult.Refused("Cannot set up ${config.path}: ${e.message}")
        }
        val record =
            BackendRecord(
                id = newId(),
                kind = BackendKinds.kindOf(config),
                name = name,
                settings = BackendKinds.settingsOf(config),
                addedAt = clock.now(),
            )
        withContext(io) { store.backends.add(record) }
        return AddResult.Added(backendOf(record))
    }

    /**
     * Stops syncing with the backend [id] and forgets what this device knew of it, with its
     * secrets. Its files stay where they are (PLAN.md §6.5): other devices can go on syncing
     * there, and adding it again copies this device's whole history again. Returns whether it was
     * there.
     *
     * The backend leaves the list first. If the process dies before the rest, what is left are
     * sync records under an id that is never used again.
     */
    public suspend fun remove(id: String): Boolean =
        operations.withLock {
            val backend = list().firstOrNull { it.id == id } ?: return@withLock false
            withContext(io) { store.backends.remove(id) }
            engine.forget(id)
            backend.config.secrets.forEach { secrets.delete(SecretKey(id, it)) }
            true
        }

    /**
     * Syncs with every backend that [due] accepts, one after the other, and records how each
     * went. A backend that fails does not stop the others.
     */
    public suspend fun syncAll(due: (Backend) -> Boolean = { true }): List<BackendSync> {
        forgetRemoved()
        return list().filter(due).mapNotNull { sync(it.id) }
    }

    /**
     * Drops the sync records of backends that were removed while another process synced with
     * them, or by a process that died before forgetting them. Under the lock, so that no add of
     * this process runs meanwhile; another process's add is safe, as `SyncEngine` decides in one
     * transaction, and a new backend has its row before any sync records.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Housekeeping, retried each time.
    private suspend fun forgetRemoved() {
        try {
            operations.withLock { engine.forgetUnlisted() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A locked database: the records stay until the next syncAll, and harm nothing.
        }
    }

    /**
     * Runs one sync cycle with the backend [id] and records how it went. Returns null if there is
     * no such backend, for example because it was just removed.
     *
     * Only cancellation is thrown: any other failure, of the backend, the database or a secret
     * store, ends as an [Outcome] that the next sync may change.
     */
    public suspend fun sync(id: String): BackendSync? =
        operations.withLock {
            val backend = list().firstOrNull { it.id == id } ?: return@withLock null
            val sync = cycle(backend)
            record(sync)
            sync
        }

    @Suppress("TooGenericExceptionCaught") // Any failure ends this sync, not the app.
    private suspend fun cycle(backend: Backend): BackendSync =
        if (backend.config is UnknownConfig) {
            backend.ended(
                Outcome.UNSUPPORTED,
                "This version of Jotter cannot use ${backend.config.location}.",
            )
        } else {
            try {
                val result = engine.sync(SyncTarget(backend.id, factory.open(backend)))
                val (outcome, detail) = describe(result)
                val report =
                    when (result) {
                        is SyncResult.Completed -> result.report
                        is SyncResult.Interrupted -> result.report
                        else -> null
                    }
                backend.ended(outcome, detail, report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backend.ended(
                    Outcome.FAILED,
                    "Failed on this device: ${e.message}. It is tried again later.",
                )
            }
        }

    private fun Backend.ended(
        outcome: Outcome,
        detail: String,
        report: SyncReport? = null,
    ) = BackendSync(
        this,
        Attempt(clock.now(), outcome, detail, report?.issues.orEmpty().map(::describe)),
        report,
    )

    /** A database that cannot record the outcome does not change it: the sync itself happened. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun record(sync: BackendSync) {
        val attempt = sync.attempt
        try {
            withContext(io) {
                store.backends.recordAttempt(
                    sync.backend.id,
                    SyncAttempt(attempt.at, attempt.outcome.name, attempt.detail, attempt.issues),
                    attempt.outcome.succeeded,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The status screen then shows the attempt before; the next sync records again.
        }
    }

    /**
     * Returns the state of every backend: how its last sync went, and whether it can be reached
     * now. A backend that cannot be opened counts as unreachable.
     */
    @Suppress("TooGenericExceptionCaught") // A backend that cannot be opened is reported.
    public suspend fun status(): List<BackendStatus> =
        list().map { backend ->
            val reachability =
                try {
                    factory.open(backend).ping()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Reachability.Unreachable(
                        UnreachableReason.FAILED,
                        e.message ?: "it cannot be opened",
                    )
                }
            BackendStatus(backend, reachability)
        }

    private companion object {
        /** The marker file of a local folder store (ADR-0012). */
        const val MARKER = ".jotter-store"

        val files: FileSystem get() = FileSystem.SYSTEM

        /** [path] with symbolic links resolved, or as it is if it does not exist (yet). */
        fun canonical(path: Path): Path =
            try {
                files.canonicalize(path)
            } catch (_: IOException) {
                path
            }

        /** Whether [a] and [b] are the same folder, or one is inside the other. */
        fun related(
            a: Path,
            b: Path,
        ): Boolean = a.within(b) || b.within(a)

        fun Path.within(other: Path): Boolean =
            root == other.root &&
                segments.size >= other.segments.size &&
                segments.subList(0, other.segments.size) == other.segments

        @OptIn(ExperimentalUuidApi::class) // generateV4() is still experimental in Kotlin 2.4.
        fun newId(): String = Uuid.generateV4().toString()

        fun normalised(path: String): Path? = path.toPath(normalize = true).takeIf { it.isAbsolute }

        fun notAbsolute(path: String) =
            "Give the folder's full path, starting with /, not \"$path\"."

        fun nameOf(folder: Path): String = folder.name.ifEmpty { folder.toString() }
    }
}
