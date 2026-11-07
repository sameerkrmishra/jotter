package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.core.logging.Logger
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.dto.DeviceDto
import io.github.sameerkrmishra.jotter.core.sync.dto.FormatDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A backend to sync with.
 *
 * @property id The backend's lasting local name, which sync records are kept under. A backend added
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

    init {
        keepNotesSettled(store)
    }

    /** Runs one cycle with [target]. One at a time: a second call waits for the first. */
    public suspend fun sync(target: SyncTarget): SyncResult =
        cycles.withLock {
            val started = TimeSource.Monotonic.markNow()
            log.debug { "Sync with ${target.id} started" }
            val result = withContext(databaseContext) { cycle(target) }
            logResult(target.id, result, started.elapsedNow())
            result
        }

    /** Forgets what is known of [targetId] (PLAN.md §6.5). Its files stay. */
    public suspend fun forget(targetId: String) {
        cycles.withLock {
            withContext(databaseContext) { store.sync.forgetBackend(targetId) }
        }
    }

    /**
     * Forgets every backend that has sync records here but is not in the database's `backend`
     * table, and returns their ids. A backend removed by one process while another synced with it
     * leaves such records. Only for callers that keep their backends in that table: an id that is
     * not there loses its records.
     */
    public suspend fun forgetUnlisted(): List<String> =
        cycles.withLock {
            withContext(databaseContext) { store.sync.forgetUnlistedBackends() }
        }

    private fun logResult(
        targetId: String,
        result: SyncResult,
        took: Duration,
    ) {
        val time = took.inWholeMilliseconds
        when (result) {
            is SyncResult.Completed ->
                log.info { "Sync with $targetId completed in $time ms: ${summary(result.report)}" }
            is SyncResult.Unreachable ->
                log.info {
                    "Sync with $targetId: unreachable (${result.reachability.reason}): " +
                        result.reachability.detail
                }
            is SyncResult.Incompatible ->
                log.warn { "Sync with $targetId: incompatible backend: ${result.detail}" }
            is SyncResult.Interrupted ->
                log.warn(result.cause) {
                    "Sync with $targetId interrupted after $time ms: ${summary(result.report)}"
                }
        }
        result.issues().forEach { issue -> log.warn { "Sync with $targetId: ${describe(issue)}" } }
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
        val puller = Puller(store.sync, target.id, blobs, seams)
        val pushIssues = mutableListOf<SyncIssue>()
        var pushed = 0
        val report = {
            SyncReport(
                pushedSegments = pushed,
                appliedSegments = puller.applied,
                newRevisions = puller.newRevisions,
                merges = puller.merges,
                issues = pushIssues + puller.issues,
            )
        }
        return try {
            val problem = formatProblem(blobs)
            if (problem != null) {
                SyncResult.Incompatible(problem)
            } else {
                val folders = BackendLayout.segments(listDevices(blobs))
                forgetLostFiles(target.id, folders)
                val pusher = Pusher(store.sync, target.id, blobs, store.deviceId, seams)
                try {
                    pusher.push(folders[store.deviceId].orEmpty())
                } finally {
                    pushed = pusher.pushed
                    pushIssues += pusher.problems
                }
                puller.pull(folders)
                SyncResult.Completed(report())
            }
        } catch (failure: BlobStoreException) {
            SyncResult.Interrupted(failure, report())
        }
    }

    /**
     * A file that was read and is gone from the backend took its revisions along, and its writer
     * may be gone too. They count as not on the backend again, so this device pushes them. A live
     * writer repairs the file as well, which only costs a duplicate.
     */
    private fun forgetLostFiles(
        targetId: String,
        folders: Map<DeviceId, List<SegmentFile>>,
    ) {
        val listed =
            folders.flatMapTo(HashSet()) { (device, files) -> files.map { device to it.name } }
        store.sync.write {
            store.sync
                .readFiles(targetId)
                .filterNot { it in listed }
                .forEach { (device, name) -> store.sync.forgetFile(targetId, device, name) }
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
     * Makes sure the backend holds the format file of this version and no other. Returns what is
     * wrong otherwise. Each format and codec has a path of its own, so two versions that set up
     * one backend at once write two files instead of one replacing the other, and the check after
     * writing finds the other's (ADR-0004).
     */
    private suspend fun formatProblem(blobs: BlobStore): String? {
        val path = BackendLayout.formatPath(SYNC_FORMAT, SYNC_CODEC)
        val text = json.encodeToString(FormatDto.serializer(), FormatDto(SYNC_FORMAT, SYNC_CODEC))
        var found = blobs.list(BackendLayout.FORMATS).map { it.path }
        // A listing without this version's file but with another's is answered below, unwritten.
        if (found.isEmpty()) {
            try {
                blobs.writeNew(path, text.encodeToByteArray())
            } catch (_: BlobAlreadyExistsException) {
                // Another device of this version wrote the same file: a path has one content.
            }
            found = blobs.list(BackendLayout.FORMATS).map { it.path } + path
        }
        val others = found.filter { it != path }
        return when {
            others.isNotEmpty() ->
                "The backend holds the format ${others.joinToString { it.substringAfterLast('/') }}"
            blobs.read(path).decodeToString() != text ->
                "The backend's ${path.substringAfterLast('/')} is damaged"
            else -> null
        }
    }

    /** What this version writes to a backend. */
    public companion object {
        /** The sync format and codec, as in the name of the format file: `1-json+gzip`. */
        public const val FORMAT: String = "$SYNC_FORMAT-$SYNC_CODEC"

        private val json = Json { ignoreUnknownKeys = true }

        private val log = Logger("sync")

        private fun summary(report: SyncReport): String =
            "sent ${report.pushedSegments} files, applied ${report.appliedSegments}, " +
                "${report.newRevisions} new revisions, ${report.merges} merges, " +
                "${report.issues.size} issues"

        private fun SyncResult.issues(): List<SyncIssue> =
            when (this) {
                is SyncResult.Completed -> report.issues
                is SyncResult.Interrupted -> report.issues
                else -> emptyList()
            }

        /** An issue for the log: ids and numbers, never content. */
        private fun describe(issue: SyncIssue): String =
            when (issue) {
                is SyncIssue.SegmentNotApplied ->
                    "segment ${issue.number} of device ${issue.device} not applied " +
                        "(${issue.problem}): ${issue.detail}"
                is SyncIssue.RevisionTooLarge -> "revision ${issue.id} is too large to push"
                is SyncIssue.ClockDrift -> "a revision is dated too far ahead: ${issue.timestamp}"
            }
    }
}
