package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.dto.DeviceDto
import io.github.sameerkrmishra.jotter.core.sync.dto.FormatDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * A backend to sync with.
 *
 * @property id The backend's lasting local name, which the cursors are kept under. A backend added
 *   again under a new id is a new backend, and gets the whole history.
 * @property store The backend.
 */
public class SyncTarget(
    public val id: String,
    public val store: BlobStore,
)

/**
 * This device as other devices can read it in `device.json`.
 *
 * @property name A name for people, such as "Laptop".
 * @property platform What it runs on, such as "linux".
 */
public data class DeviceInfo(
    public val name: String,
    public val platform: String,
)

/**
 * The sync cycle of PLAN.md §6.3: push, pull, apply, for one backend at a time. Scheduling, and
 * running it again until a backend has this device's merges, is up to the caller.
 *
 * It is safe to crash anywhere, and to run from two processes on one database. Only
 * [BlobStoreException]s are caught, and they end in a [SyncResult]. A database failure, such as
 * `StorageBusyException`, is thrown, and the next cycle repeats what did not finish.
 *
 * @param store This device's database.
 * @param device What `device.json` says.
 * @param databaseContext Where the blocking database work runs, e.g. an IO dispatcher.
 * @param clock The time `device.json` is dated with.
 */
public class SyncEngine internal constructor(
    private val store: NoteStore,
    private val device: DeviceInfo,
    private val databaseContext: CoroutineContext,
    private val clock: Clock,
    private val seams: SyncSeams,
) {
    /** Creates the engine for [store]. See the class for the parameters. */
    public constructor(
        store: NoteStore,
        device: DeviceInfo,
        databaseContext: CoroutineContext,
        clock: Clock = Clock.System,
    ) : this(store, device, databaseContext, clock, SyncSeams())

    private val cycles = Mutex()

    /** Runs one cycle with [target]. One at a time: a second call waits for the first. */
    public suspend fun sync(target: SyncTarget): SyncResult =
        cycles.withLock {
            withContext(databaseContext) { cycle(target) }
        }

    /** Forgets [targetId]'s cursors and segments (PLAN.md §6.5). Its files stay. */
    public suspend fun forget(targetId: String) {
        cycles.withLock {
            withContext(databaseContext) { store.sync.forgetBackend(targetId) }
        }
    }

    private suspend fun cycle(target: SyncTarget): SyncResult {
        val blobs = target.store
        val reachability = blobs.ping()
        return if (reachability is Reachability.Unreachable) {
            SyncResult.Unreachable(reachability)
        } else {
            exchange(target)
        }
    }

    private suspend fun exchange(target: SyncTarget): SyncResult {
        val blobs = target.store
        val pusher = Pusher(store.sync, target.id, blobs, store.deviceId, seams)
        val puller = Puller(store.sync, target.id, blobs, seams)
        val report = {
            SyncReport(
                pushedSegments = pusher.pushed,
                appliedSegments = puller.applied,
                newRevisions = puller.newRevisions,
                merges = puller.merges,
                issues = pusher.problems + puller.issues,
            )
        }
        return try {
            val problem = formatProblem(blobs)
            if (problem != null) {
                SyncResult.Incompatible(problem)
            } else {
                val folders = BackendLayout.segments(listDevices(blobs))
                forgetLostSegments(target.id, folders)
                pusher.push(folders[store.deviceId]?.keys.orEmpty())
                puller.pull(folders - store.deviceId)
                SyncResult.Completed(report())
            }
        } catch (failure: BlobStoreException) {
            SyncResult.Interrupted(failure, report())
        }
    }

    /**
     * A segment read from another device that is gone from the backend took its revisions along,
     * and its writer may be gone too. They count as not on the backend again, so this device
     * pushes them. A live writer repairs the segment as well, which only costs a duplicate.
     */
    private fun forgetLostSegments(
        targetId: String,
        folders: Map<DeviceId, Map<Long, BlobInfo>>,
    ) {
        store.sync.write {
            store.sync.pulledSegments(targetId).forEach { (device, number) ->
                if (folders[device]?.containsKey(number) != true) {
                    store.sync.forgetPulledSegment(targetId, device, number)
                }
            }
        }
    }

    private suspend fun listDevices(blobs: BlobStore): List<BlobInfo> {
        val files = blobs.list(BackendLayout.DEVICES)
        val deviceFile = BackendLayout.deviceFile(store.deviceId)
        if (files.none { it.path == deviceFile }) writeDeviceFile(blobs, deviceFile)
        return files
    }

    private suspend fun writeDeviceFile(
        blobs: BlobStore,
        path: String,
    ) {
        val created = clock.now().toEpochMilliseconds()
        val text =
            json.encodeToString(
                DeviceDto.serializer(),
                DeviceDto(device.name, device.platform, created),
            )
        try {
            blobs.writeNew(path, text.encodeToByteArray())
        } catch (_: BlobAlreadyExistsException) {
            // An earlier write got through. The file is only informative, so any content will do.
        }
    }

    /**
     * Makes sure the backend has a `format.json` this version can use. Returns what is wrong
     * otherwise. Two devices may create it at once, so the loser reads the winner's (ADR-0004).
     */
    private suspend fun formatProblem(blobs: BlobStore): String? {
        val path = BackendLayout.FORMAT_PATH
        val found =
            try {
                blobs.read(path)
            } catch (_: BlobNotFoundException) {
                null
            }
        if (found != null) return problemWith(found)
        val text =
            json.encodeToString(FormatDto.serializer(), FormatDto(SYNC_FORMAT, SYNC_CODEC))
        return try {
            blobs.writeNew(path, text.encodeToByteArray())
            null
        } catch (_: BlobAlreadyExistsException) {
            problemWith(blobs.read(path))
        }
    }

    private fun problemWith(bytes: ByteArray): String? =
        try {
            val format = json.decodeFromString<FormatDto>(bytes.decodeToString())
            when {
                format.format != SYNC_FORMAT -> "The backend has format ${format.format}"
                format.codec != SYNC_CODEC -> "The backend uses the codec ${format.codec}"
                else -> null
            }
        } catch (_: SerializationException) {
            "The backend's format.json is damaged"
        }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
