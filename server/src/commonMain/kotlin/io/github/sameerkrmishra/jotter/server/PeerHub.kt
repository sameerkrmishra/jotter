package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.localfolder.LocalFolderBlobStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException

/** What a [PeerHub] is doing. */
public sealed interface HubStatus {
    /** Not listening. */
    public data object Stopped : HubStatus

    /**
     * Listening.
     *
     * @property host The address it listens on.
     * @property port The port it listens on; with a configured port of 0, the one it got.
     */
    public data class Running(
        val host: String,
        val port: Int,
    ) : HubStatus

    /**
     * The last start failed, and the hub is not listening.
     *
     * @property message Why, for the user. Holds no secret.
     */
    public data class Failed(
        val message: String,
    ) : HubStatus
}

/** [PeerHub.start] failed; [HubStatus.Failed] says the same. */
public class HubStartException(
    message: String,
    cause: Throwable?,
) : Exception(message, cause)

/**
 * The PC as a hub (`docs/PLAN.md` §6.4, `docs/adr/0015-pc-hub-peer-protocol.md`): serves the folder
 * [HubConfig.storeRoot] to paired devices over HTTP, as a `BlobStore` that `PeerBlobStore` reaches.
 * The PC's own sync uses the same folder directly, as a `LocalFolderBlobStore`, so the PC is one
 * more device on its own hub and needs no network to reach it.
 *
 * This is the whole API for the app that hosts it: [setUpStore] once, when the user turns the hub
 * on; [start] and [stop]; [status] to show; and [pair], [pairedDevices] and [unpair] to manage who
 * may connect. No device can do anything without a token from [pair].
 */
public class PeerHub internal constructor(
    private val config: HubConfig,
    private val store: BlobStore,
    private val initializeStore: suspend () -> Unit,
    fileSystem: FileSystem,
    private val dispatcher: CoroutineDispatcher,
) {
    /** A hub for [config], over the real file system. Nothing happens until [start]. */
    public constructor(config: HubConfig) : this(
        config,
        LocalFolderBlobStore(config.storeRoot),
        FileSystem.SYSTEM,
        Dispatchers.Default,
    )

    private constructor(
        config: HubConfig,
        store: LocalFolderBlobStore,
        fileSystem: FileSystem,
        dispatcher: CoroutineDispatcher,
    ) : this(
        config,
        store,
        {
            withContext(dispatcher) { fileSystem.createDirectories(config.storeRoot) }
            store.initialize()
        },
        fileSystem,
        dispatcher,
    )

    private val tokens = DeviceTokens(config.tokenFile, fileSystem, dispatcher)
    private val mutex = Mutex()
    private var server: PeerServer? = null
    private val mutableStatus = MutableStateFlow<HubStatus>(HubStatus.Stopped)

    /** What the hub is doing now. */
    public val status: StateFlow<HubStatus> = mutableStatus.asStateFlow()

    /**
     * Creates [HubConfig.storeRoot] and marks it as a store. Call it once, when the user turns the
     * hub on, never because the store reads as not set up: its folder may be on a drive that is not
     * mounted (`docs/adr/0012-local-folder-publishing.md`). Until then devices are told that the
     * hub's store is not set up.
     *
     * @throws BlobStoreException if the folder cannot be created or marked.
     */
    public suspend fun setUpStore() {
        try {
            initializeStore()
        } catch (e: IOException) {
            throw BlobStoreFailureException(
                "Cannot create ${config.storeRoot}: ${e.message}",
                e,
            )
        }
    }

    /**
     * Starts listening on [HubConfig.host] and [HubConfig.port], unless the hub already is.
     *
     * @throws HubStartException if it cannot: the port is taken, the address is not this machine's
     *   (Tailscale is down), or the file of paired devices is damaged.
     */
    public suspend fun start(): HubStatus.Running =
        mutex.withLock {
            (mutableStatus.value as? HubStatus.Running)?.let { return@withLock it }
            val handler =
                PeerHandler(
                    store,
                    { tokens.authenticate(it) != null },
                    Semaphore(config.limits.maxTransfers),
                )
            val next = PeerServer(handler, config.limits, dispatcher)
            var started = false
            val running =
                try {
                    tokens.load()
                    HubStatus
                        .Running(config.host, next.start(config.host, config.port))
                        .also { started = true }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    // okio's, kotlinx-io's and, on Native, the socket layer's own exceptions: every
                    // failure to start is the same to the user.
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    val where = "${config.host}:${config.port}"
                    val message = "The hub cannot start on $where: ${e.message}"
                    mutableStatus.value = HubStatus.Failed(message)
                    throw HubStartException(message, e)
                } finally {
                    if (!started) next.stop()
                }
            server = next
            mutableStatus.value = running
            running
        }

    /** Stops listening and drops every connection. Stopping a stopped hub does nothing. */
    public suspend fun stop() {
        mutex.withLock {
            server?.stop()
            server = null
            mutableStatus.value = HubStatus.Stopped
        }
    }

    /**
     * Pairs a new device called [deviceName] and returns its token, which the user enters on that
     * device together with the hub's address. Works whether the hub runs or not.
     *
     * @throws IllegalArgumentException if [deviceName] is empty, longer than 64 characters or has
     *   a control character.
     * @throws IOException if the paired devices cannot be saved; nothing is paired then.
     */
    public suspend fun pair(deviceName: String): Pairing = tokens.pair(deviceName)

    /**
     * Every paired device.
     *
     * @throws IOException if the file of paired devices cannot be read.
     */
    public suspend fun pairedDevices(): List<PairedDevice> = tokens.list()

    /**
     * Forgets the device [deviceId], whose token stops working at once, also on a running hub.
     * Returns whether it was paired.
     *
     * @throws IOException if the paired devices cannot be saved; nothing changes then.
     */
    public suspend fun unpair(deviceId: String): Boolean = tokens.unpair(deviceId)
}
