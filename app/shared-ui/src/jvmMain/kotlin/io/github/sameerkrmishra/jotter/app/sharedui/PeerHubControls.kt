package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.server.HubConfig
import io.github.sameerkrmishra.jotter.server.HubStartException
import io.github.sameerkrmishra.jotter.server.HubStatus
import io.github.sameerkrmishra.jotter.server.PeerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toOkioPath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.UUID
import kotlin.coroutines.CoroutineContext

private val PRIVATE_FILE: FileAttribute<Set<PosixFilePermission>> =
    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
private val PRIVATE_DIRECTORY: FileAttribute<Set<PosixFilePermission>> =
    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

/**
 * The desktop app's hub, a [PeerHub] of the `server` module. Its settings, which hold no secret,
 * are kept in [settingsFile]; the paired devices, as hashes, in [tokenFile].
 *
 * @param manager Adds the hub's folder as a local folder backend, so that this PC syncs with it.
 * @param scope Where the hub's status is followed.
 * @param io Where the settings file is read and written.
 * @param defaults The settings before the user changed any.
 */
public class PeerHubControls(
    private val manager: BackendManager,
    private val settingsFile: Path,
    private val tokenFile: Path,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    defaults: HubSettings,
) : HubControls {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow<HubState>(HubState.Off)
    private var hub: PeerHub? = null
    private var following: Job? = null

    override val state: StateFlow<HubState> = mutableState.asStateFlow()

    override var settings: HubSettings = load() ?: defaults
        private set

    /** Starts the hub if the user left it on, as when the app starts. */
    public suspend fun resume() {
        lock.withLock {
            if (settings.on) start(hubFor(settings) ?: return@withLock)
        }
    }

    override suspend fun turnOn(settings: HubSettings): String? =
        lock.withLock {
            if (!settings.folder.startsWith('/')) {
                return@withLock "Give the hub's folder as a full path, starting with /."
            }
            val next =
                try {
                    newHub(settings)
                } catch (e: IllegalArgumentException) {
                    return@withLock "${e.message}. Use 127.0.0.1 for this PC only, or this PC's " +
                        "Tailscale address, such as 100.101.102.103."
                }
            try {
                next.setUpStore()
            } catch (e: BlobStoreException) {
                return@withLock "Cannot set up ${settings.folder}: ${e.message}"
            }
            when (val added = manager.addLocalFolder(settings.folder)) {
                is AddResult.Added, is AddResult.AlreadyAdded -> Unit
                is AddResult.Refused -> return@withLock added.reason
                else -> return@withLock "Cannot use ${settings.folder} as the hub's folder."
            }
            hub?.stop()
            hub = next
            val problem = start(next)
            if (problem == null) save(settings.copy(on = true))
            problem
        }

    override suspend fun turnOff() {
        lock.withLock {
            hub?.stop()
            save(settings.copy(on = false))
        }
    }

    override suspend fun pair(deviceName: String): HubPairing =
        lock.withLock {
            val current = hub ?: hubFor(settings) ?: error("Turn the hub on first.")
            val pairing =
                try {
                    current.pair(deviceName.trim())
                } catch (e: IllegalArgumentException) {
                    error("Cannot pair \"$deviceName\": ${e.message}.")
                } catch (e: IOException) {
                    error("Cannot save the paired devices: ${e.message}")
                }
            HubPairing(HubDevice(pairing.device.id, pairing.device.name), pairing.token)
        }

    override suspend fun devices(): List<HubDevice> =
        lock.withLock {
            (hub ?: hubFor(settings))?.pairedDevices()?.map { HubDevice(it.id, it.name) }.orEmpty()
        }

    override suspend fun unpair(id: String) {
        lock.withLock { (hub ?: hubFor(settings))?.unpair(id) }
    }

    private fun newHub(settings: HubSettings) =
        PeerHub(
            HubConfig(
                storeRoot = Path.of(settings.folder).toOkioPath(),
                tokenFile = tokenFile.toOkioPath(),
                host = settings.host.trim(),
                port = settings.port,
            ),
        )

    private fun hubFor(settings: HubSettings): PeerHub? =
        hub ?: try {
            newHub(settings).also { hub = it }
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Starts [hub] and follows its status. Returns why it could not start, or null. */
    private suspend fun start(hub: PeerHub): String? {
        following?.cancel()
        following =
            scope.launch {
                hub.status.collect { status ->
                    mutableState.value =
                        when (status) {
                            HubStatus.Stopped -> HubState.Off
                            is HubStatus.Running ->
                                HubState.Running(
                                    "${status.host}:${status.port}",
                                )
                            is HubStatus.Failed -> HubState.Failed(status.message)
                        }
                }
            }
        return try {
            hub.start()
            null
        } catch (e: HubStartException) {
            e.message
        }
    }

    private fun load(): HubSettings? =
        try {
            val properties = Properties()
            Files.newInputStream(settingsFile).use(properties::load)
            HubSettings(
                properties.getProperty("folder") ?: return null,
                properties.getProperty("host") ?: return null,
                properties.getProperty("port")?.toIntOrNull() ?: return null,
                properties.getProperty("on") == "true",
            )
        } catch (_: IOException) {
            null
        }

    private suspend fun save(next: HubSettings) {
        settings = next
        runInterruptible(io) {
            val properties = Properties()
            properties["folder"] = next.folder
            properties["host"] = next.host
            properties["port"] = next.port.toString()
            properties["on"] = next.on.toString()
            val directory = settingsFile.toAbsolutePath().parent
            if (!Files.isDirectory(directory)) Files.createDirectories(directory, PRIVATE_DIRECTORY)
            // Created 0600 by the call that creates it, then renamed over the old one: never
            // readable by others, and never half written.
            val temporary = directory.resolve("${settingsFile.fileName}.${UUID.randomUUID()}.tmp")
            try {
                Files.createFile(temporary, PRIVATE_FILE)
                Files.newOutputStream(temporary).use { properties.store(it, "Jotter's PC hub") }
                Files.move(
                    temporary,
                    settingsFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
