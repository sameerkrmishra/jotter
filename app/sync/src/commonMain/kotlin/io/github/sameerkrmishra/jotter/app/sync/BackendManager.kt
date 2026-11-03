package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.localfolder.LocalFolderBlobStore
import io.github.sameerkrmishra.jotter.backend.peer.PeerAddress
import io.github.sameerkrmishra.jotter.core.storage.BackendRecord
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.SyncAttempt
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import io.github.sameerkrmishra.jotter.core.sync.SyncReport
import io.github.sameerkrmishra.jotter.core.sync.SyncResult
import io.github.sameerkrmishra.jotter.core.sync.SyncTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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

    /**
     * No keyring answers, so the password was not stored and nothing was added. The user may
     * choose to keep it in a file instead, in plain text (ADR-0016): ask, and call again with
     * [SecretPlace.FILE] if they say yes.
     */
    public data class NoKeyring(
        /** Why no keyring answers, in plain words. */
        val reason: String,
    ) : AddResult
}

/** What [BackendManager.changePassword] did. */
public sealed interface PasswordChange {
    /** The new password works and is stored. */
    public data object Changed : PasswordChange

    /** Nothing was changed. [reason] says why, in plain words. */
    public data class Refused(
        /** Why. */
        val reason: String,
    ) : PasswordChange
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
 * @param secrets Where the backends' secrets are kept, to store them when a backend is added and
 *   delete them with it.
 * @param io Where blocking database calls run.
 * @param clock The time of a sync and of adding a backend.
 */
@Suppress(
    "TooManyFunctions", // One function per operation, and the steps of adding a backend.
    "LongParameterList", // The internal constructor takes the WebDAV client tests replace.
)
public class BackendManager internal constructor(
    private val store: NoteStore,
    private val engine: SyncEngine,
    private val factory: BackendFactory,
    private val secrets: SecretStores,
    private val io: CoroutineContext,
    private val clock: Clock,
    private val webDav: WebDavOpener,
) {
    public constructor(
        store: NoteStore,
        engine: SyncEngine,
        factory: BackendFactory,
        secrets: SecretStores,
        io: CoroutineContext,
        clock: Clock = Clock.System,
    ) : this(store, engine, factory, secrets, io, clock, ::openWebDav)

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
        return AddResult.Added(insert(newId(), config, name))
    }

    private suspend fun insert(
        id: String,
        config: BackendConfig,
        name: String,
    ): Backend {
        val record =
            BackendRecord(
                id = id,
                kind = BackendKinds.kindOf(config),
                name = name,
                settings = BackendKinds.settingsOf(config),
                addedAt = clock.now(),
            )
        withContext(io) { store.backends.add(record) }
        return backendOf(record)
    }

    /**
     * Adds the collection at [url] on a WebDAV server as a backend called [name], or the server's
     * name, signing in as [username] with [password], an app password made for Jotter.
     *
     * It first asks the server, with that password, whether the collection is a Jotter store.
     * One that another device set up is simply added. One that is not yet is set up only if
     * [setUpNewStore], which only the user may decide (ADR-0012): that checks that the server
     * keeps files (ADR-0017) and refuses a server that would let one write replace another.
     * Plain `http` is refused, except to this machine.
     *
     * The password goes to the [secretPlace] the user chose, never to the database. If no keyring
     * answers, nothing is added and the result is [AddResult.NoKeyring].
     */
    @Suppress("LongParameterList") // What the user enters in the form.
    public suspend fun addWebDav(
        url: String,
        username: String,
        password: Secret,
        name: String? = null,
        setUpNewStore: Boolean = false,
        secretPlace: SecretPlace = SecretPlace.KEYRING,
    ): AddResult =
        operations.withLock {
            val config = WebDavConfig(withSlash(url.trim()), username.trim(), secretPlace)
            webDavProblem(config, password)?.let { return@withLock AddResult.Refused(it) }
            list()
                .firstOrNull { it.config is WebDavConfig && sameAccount(it.config, config) }
                ?.let { return@withLock AddResult.AlreadyAdded(it) }
            val blobs =
                try {
                    webDav.open(config, password)
                } catch (e: IllegalArgumentException) {
                    return@withLock AddResult.Refused("Cannot use ${config.url}: ${e.message}.")
                }
            blobs.use { added(it, config, password, name ?: hostOf(config.url), setUpNewStore) }
        }

    private fun webDavProblem(
        config: WebDavConfig,
        password: Secret,
    ): String? =
        when {
            config.username.isEmpty() -> "Enter the user name of the account."
            password.reveal().isEmpty() -> "Enter the app password."
            else -> null
        }

    private fun sameAccount(
        a: WebDavConfig,
        b: WebDavConfig,
    ) = a.url == b.url && a.username == b.username

    // Each return is one way adding can end, in the order the user meets them.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun added(
        blobs: NewStore,
        config: WebDavConfig,
        password: Secret,
        name: String,
        setUpNewStore: Boolean,
    ): AddResult {
        val reachability = blobs.ping()
        val setUp =
            reachability is Reachability.Unreachable &&
                reachability.reason == UnreachableReason.NOT_INITIALIZED
        if (reachability is Reachability.Unreachable && !(setUp && setUpNewStore)) {
            return unusable(config, reachability)
        }
        val id = newId()
        val key = SecretKey(id, SecretKind.APP_PASSWORD)
        val place = secrets.of(config.secretPlace)
        try {
            place.write(key, password)
        } catch (e: SecretStoreUnavailableException) {
            return AddResult.NoKeyring(e.message.orEmpty())
        } catch (e: SecretStoreException) {
            return AddResult.Refused("Cannot store the app password: ${e.message}")
        }
        return try {
            if (setUp) blobs.initialize()
            AddResult.Added(insert(id, config, name))
        } catch (e: BlobStoreException) {
            forgetSecret(place, key)
            AddResult.Refused(cannotSetUp(config, e))
        } catch (e: Exception) {
            forgetSecret(place, key)
            throw e
        }
    }

    /**
     * Deletes the secret of a backend that was not added, also when adding was cancelled: a
     * cancelled coroutine would otherwise fail the delete at once and leave the secret behind.
     */
    private suspend fun forgetSecret(
        place: SecretStore,
        key: SecretKey,
    ) {
        withContext(NonCancellable) { place.delete(key) }
    }

    private fun unusable(
        config: WebDavConfig,
        reachability: Reachability.Unreachable,
    ): AddResult =
        when (reachability.reason) {
            UnreachableReason.NOT_INITIALIZED -> AddResult.NotAStoreYet(config.url)
            UnreachableReason.MISSING ->
                AddResult.Refused(
                    "There is no folder at ${config.url} on the server. Create it first, for " +
                        "example in the server's web interface, then add it again.",
                )
            UnreachableReason.UNAUTHORIZED ->
                AddResult.Refused(
                    "The server refused the user name or app password. Check both, or make a " +
                        "new app password for Jotter in your account's settings.",
                )
            UnreachableReason.FAILED ->
                AddResult.Refused("Cannot reach ${config.url}: ${reachability.detail}.")
        }

    private fun cannotSetUp(
        config: WebDavConfig,
        failure: BlobStoreException,
    ): String =
        if (failure.message.orEmpty().contains("If-None-Match")) {
            "Jotter cannot sync through ${config.url}: the server replaces a file when it is " +
                "written again, where it must refuse. A repeated upload could then overwrite " +
                "another device's changes. Use another server. (${failure.message})"
        } else {
            "Cannot set up ${config.url}: ${failure.message}"
        }

    /**
     * Where the secrets of [backend], removed with `keepSecrets`, may still be, and how to delete
     * them, in plain words; or null if it has none.
     */
    public fun secretsLeftBehind(backend: Backend): String? {
        if (backend.config.secrets.isEmpty()) return null
        val what = if (backend.config is PeerConfig) "pairing token" else "password"
        return if (backend.config.secretPlace == SecretPlace.KEYRING) {
            "Its $what may still be in your keyring, as the item \"Jotter: ... of backend " +
                "${backend.id}\". Delete it later in your keyring program, such as Seahorse or " +
                "KWalletManager, or with: secret-tool clear application jotter backend ${backend.id}"
        } else {
            val file = secrets.fileLocation
            if (file != null) {
                "Its $what may still be in $file, under ${backend.id}. Delete that entry, or " +
                    "the whole file if no other backend uses it."
            } else {
                "Its $what may still be in a secrets file, under ${backend.id}, though Jotter " +
                    "cannot tell where on this device, as no home folder is set. Look for " +
                    "jotter/secrets.json in your config folder, such as ~/.config, and delete " +
                    "that entry."
            }
        }
    }

    /** The file a user may choose for passwords, for people, or null if there is none. */
    public val secretsFile: String? get() = secrets.fileLocation

    /** Where the secrets of [backend] are kept, in plain words, or null if it needs none. */
    public fun whereSecretsAre(backend: Backend): String? {
        val what = if (backend.config is PeerConfig) "pairing token" else "password"
        return when {
            backend.config.secrets.isEmpty() -> null
            backend.config.secretPlace == SecretPlace.KEYRING ->
                "Its $what is kept in the keyring."
            secrets.fileLocation == null ->
                "Its $what is kept in a plain-text secrets file, as chosen when it was added, " +
                    "but Jotter cannot tell where on this device, as no home folder is set."
            else ->
                "Its $what is kept in plain text in ${secrets.fileLocation}, as chosen when " +
                    "it was added."
        }
    }

    /**
     * Replaces the app password of the WebDAV backend [id] with [password], after checking that the
     * server takes it. Use it when the server refused the old one.
     */
    public suspend fun changePassword(
        id: String,
        password: Secret,
    ): PasswordChange =
        operations.withLock {
            val config = list().firstOrNull { it.id == id }?.config
            val (kind, words) =
                when (config) {
                    is WebDavConfig -> SecretKind.APP_PASSWORD to "app password"
                    is PeerConfig -> SecretKind.PAIRING_SECRET to "pairing token"
                    else -> return@withLock PasswordChange.Refused("$id needs no password.")
                }
            if (password.reveal().isEmpty()) {
                return@withLock PasswordChange.Refused("Enter the $words.")
            }
            val reachability =
                try {
                    openWithSecret(config, password).closing { it.ping() }
                } catch (e: IllegalArgumentException) {
                    return@withLock PasswordChange.Refused("Cannot use this $words: ${e.message}.")
                }
            if (reachability is Reachability.Unreachable &&
                reachability.reason == UnreachableReason.UNAUTHORIZED
            ) {
                return@withLock PasswordChange.Refused(
                    "The ${if (config is PeerConfig) "hub" else "server"} refused this $words " +
                        "too. Nothing was changed.",
                )
            }
            try {
                secrets.of(config.secretPlace).write(SecretKey(id, kind), password)
            } catch (e: SecretStoreException) {
                return@withLock PasswordChange.Refused("Cannot store the $words: ${e.message}")
            }
            PasswordChange.Changed
        }

    private fun openWithSecret(
        config: BackendConfig,
        secret: Secret,
    ): BlobStore =
        when (config) {
            is PeerConfig -> openPeer(config, secret)
            is WebDavConfig -> webDav.open(config, secret)
            else -> throw IllegalArgumentException("${config.location} needs no secret")
        }

    /**
     * Adds the hub on the user's PC at [address], `host` or `host:port`, as a backend called
     * [name], or "PC hub", with the [token] that pairing this device on the PC showed (ADR-0015).
     *
     * The hub speaks plain HTTP inside Tailscale's encrypted tunnel, so the token goes only to a
     * Tailscale address (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`) or to this machine: an IPv4
     * address outside those is refused here, and a name that resolves outside them is refused by
     * the client before anything is sent. The hub must answer with this token, and its store must
     * be set up on the PC; this device never sets it up. The token goes to [secretPlace].
     */
    public suspend fun addPeer(
        address: String,
        token: Secret,
        name: String? = null,
        secretPlace: SecretPlace = SecretPlace.KEYRING,
    ): AddResult =
        operations.withLock {
            val config =
                try {
                    val parsed = PeerAddress.parse(address)
                    PeerConfig(parsed.host, parsed.port, secretPlace)
                } catch (e: IllegalArgumentException) {
                    return@withLock AddResult.Refused("Not a hub address: ${e.message}.")
                }
            outsideTailnet(config.host)?.let { return@withLock AddResult.Refused(it) }
            if (token.reveal().isBlank()) return@withLock AddResult.Refused(ENTER_TOKEN)
            list()
                .firstOrNull { it.config is PeerConfig && it.config.location == config.location }
                ?.let { return@withLock AddResult.AlreadyAdded(it) }
            val blobs =
                try {
                    openPeer(config, token)
                } catch (e: IllegalArgumentException) {
                    return@withLock AddResult.Refused(
                        "Cannot use this pairing token: ${e.message}.",
                    )
                }
            val reachability = blobs.closing { it.ping() }
            if (reachability is Reachability.Unreachable) {
                return@withLock AddResult.Refused(peerUnusable(config, reachability))
            }
            val id = newId()
            val place = secrets.of(secretPlace)
            try {
                place.write(SecretKey(id, SecretKind.PAIRING_SECRET), token)
            } catch (e: SecretStoreUnavailableException) {
                return@withLock AddResult.NoKeyring(e.message.orEmpty())
            } catch (e: SecretStoreException) {
                return@withLock AddResult.Refused("Cannot store the pairing token: ${e.message}")
            }
            try {
                AddResult.Added(insert(id, config, name ?: "PC hub"))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Cancellation too: the token must not stay behind for a backend not added.
                forgetSecret(place, SecretKey(id, SecretKind.PAIRING_SECRET))
                throw e
            }
        }

    private fun peerUnusable(
        config: PeerConfig,
        reachability: Reachability.Unreachable,
    ): String =
        when (reachability.reason) {
            UnreachableReason.UNAUTHORIZED ->
                "The hub refused this pairing token. Pair this device again on the PC, and enter " +
                    "the token it shows."
            UnreachableReason.NOT_INITIALIZED, UnreachableReason.MISSING ->
                "The hub at ${config.host}:${config.port} answers, but its folder is not set up. " +
                    "Set the hub up on the PC first."
            UnreachableReason.FAILED ->
                "Cannot reach the hub at ${config.host}:${config.port}: ${reachability.detail}. " +
                    "Check that the hub is on and that Tailscale runs on both devices."
        }

    /**
     * Stops syncing with the backend [id] and forgets what this device knew of it, with its
     * secrets. Its files stay where they are (PLAN.md §6.5): other devices can go on syncing
     * there, and adding it again copies this device's whole history again. Returns whether it was
     * there.
     *
     * Its secrets go first: if they cannot be deleted, as when no keyring answers, nothing is
     * removed and [SecretStoreException] says why, so that no password is left behind unseen.
     * The user may then remove it with [keepSecrets], which leaves its secrets where they are;
     * [secretsLeftBehind] says where, and how to delete them later. Then the backend leaves the
     * list. If the process dies before the rest, what is left are sync records under an id that
     * is never used again.
     */
    public suspend fun remove(
        id: String,
        keepSecrets: Boolean = false,
    ): Boolean =
        operations.withLock {
            val backend = list().firstOrNull { it.id == id } ?: return@withLock false
            val place = secrets.of(backend.config.secretPlace)
            if (!keepSecrets) backend.config.secrets.forEach { place.delete(SecretKey(id, it)) }
            withContext(io) { store.backends.remove(id) }
            engine.forget(id)
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
                val result =
                    factory
                        .open(
                            backend,
                        ).closing { engine.sync(SyncTarget(backend.id, it)) }
                val (outcome, said) = describe(result)
                // A hub's token, not a password: the words say what to do about it.
                val detail =
                    if (backend.config is PeerConfig) {
                        said.replace(
                            WRONG_PASSWORD,
                            UNPAIRED,
                        )
                    } else {
                        said
                    }
                val report =
                    when (result) {
                        is SyncResult.Completed -> result.report
                        is SyncResult.Interrupted -> result.report
                        else -> null
                    }
                backend.ended(outcome, detail, report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MissingSecretException) {
                backend.ended(Outcome.UNAUTHORIZED, e.message.orEmpty())
            } catch (e: IllegalArgumentException) {
                backend.ended(Outcome.FAILED, "Cannot open it: ${e.message}.")
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
                    factory.open(backend).closing { it.ping() }
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
        /** Runs [block] on this store, then closes it if it holds anything, as WebDAV's does. */
        suspend fun <T> BlobStore.closing(block: suspend (BlobStore) -> T): T =
            try {
                block(this)
            } finally {
                (this as? AutoCloseable)?.close()
            }

        const val ENTER_TOKEN = "Enter the pairing token that the PC showed."

        /**
         * Why the hub at [host] must not get a token, or null. An IPv4 address must be loopback or
         * Tailscale's; a name is checked by the client when it resolves it, before each request.
         */
        fun outsideTailnet(host: String): String? {
            val parts = host.split('.')
            val octets =
                parts.mapNotNull { part ->
                    part.toIntOrNull()?.takeIf { it in 0..MAX_OCTET }
                }
            if (parts.size != IPV4_PARTS || octets.size != IPV4_PARTS) return null
            val (first, second) = octets
            val allowed =
                first == LOOPBACK_FIRST ||
                    (first == TAILSCALE_FIRST && second and TAILSCALE_MASK == TAILSCALE_SECOND)
            return if (allowed) {
                null
            } else {
                "$host is not on your tailnet or this machine. The hub speaks plain HTTP inside " +
                    "Tailscale's encrypted tunnel, so Jotter sends its pairing token only to the " +
                    "PC's Tailscale address (100.64.0.0/10) or name, or to this machine."
            }
        }

        const val IPV4_PARTS = 4
        const val MAX_OCTET = 255
        const val LOOPBACK_FIRST = 127
        const val TAILSCALE_FIRST = 100
        const val TAILSCALE_MASK = 0xC0
        const val TAILSCALE_SECOND = 0x40

        fun withSlash(url: String) = if (url.endsWith('/')) url else "$url/"

        /** The host of [url], as the default name of its backend. */
        fun hostOf(url: String): String =
            url
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore(':')
                .ifEmpty { url }

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
