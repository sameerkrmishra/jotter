package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.storage.IdSource
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/** A crash: thrown out of a sync, the transaction rolls back, and the device restarts. */
internal class SimulatedCrash : RuntimeException("simulated crash")

/** A wall clock that the simulator sets. */
internal class SimClock(
    var millis: Long,
) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
}

/** Ids from a seeded generator, so that a seed replays exactly. */
internal class SimIds(
    private val random: Random,
) : IdSource {
    override fun newDeviceId(): DeviceId = DeviceId.parse(hex())

    override fun newNoteId(): NoteId = NoteId.parse(hex())

    override fun newRevisionId(): RevisionId = RevisionId.parse(hex())

    private fun hex(): String = random.nextBytes(ID_BYTES).toHexString()

    private companion object {
        const val ID_BYTES = 16
    }
}

/**
 * A backend over [InMemoryBlobStore] that can also lose a file, as a power cut can (ADR-0012): the
 * path is then free again, which the in-memory store alone cannot do.
 */
internal class LossyBlobStore : BlobStore {
    private val memory = InMemoryBlobStore()
    private val lost = HashSet<String>()
    private val rewritten = HashMap<String, ByteArray>()

    /** Set this to false to act like a backend that went away. */
    var reachable: Boolean
        get() = memory.reachable
        set(value) {
            memory.reachable = value
        }

    /** Loses the file at [path]. */
    fun lose(path: String) {
        lost += path
        rewritten -= path
    }

    /** Replaces the file at [path], which no writer can do, to model a damaged store. */
    suspend fun plant(
        path: String,
        bytes: ByteArray,
    ) {
        memory.writeNew(path, bytes)
    }

    /** Changes a published file in place, as bit rot does. Its path and name stay. */
    suspend fun damage(path: String) {
        val bytes = read(path)
        lose(path)
        rewritten[path] = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
    }

    /** Returns every file: what a disk image of this backend holds. */
    suspend fun image(): Map<String, ByteArray> = list(ROOT).associate { it.path to read(it.path) }

    /** Puts the disk back to [image]: later files are gone and those lost since are back. */
    suspend fun rollBack(image: Map<String, ByteArray>) {
        list(ROOT).filter { it.path !in image }.forEach { lose(it.path) }
        image.forEach { (path, bytes) ->
            if (path in lost) rewritten[path] = bytes.copyOf()
        }
    }

    override suspend fun list(prefix: String): List<BlobInfo> {
        val kept = memory.list(prefix).filter { it.path !in lost }
        val back =
            rewritten
                .filterKeys { it.startsWith("$prefix/") }
                .map { (path, bytes) -> BlobInfo(path, bytes.size.toLong()) }
        return (kept + back).sortedBy { it.path }
    }

    override suspend fun read(path: String): ByteArray {
        rewritten[path]?.let { return it.copyOf() }
        if (path in lost) throw BlobNotFoundException(path)
        return memory.read(path)
    }

    override suspend fun writeNew(
        path: String,
        bytes: ByteArray,
    ) {
        if (path in lost) {
            if (path in rewritten) throw BlobAlreadyExistsException(path)
            if (!reachable) throw BackendUnavailableException(DOWN)
            rewritten[path] = bytes.copyOf()
        } else {
            memory.writeNew(path, bytes)
        }
    }

    override suspend fun ping(): Reachability = memory.ping()

    private companion object {
        const val ROOT = "jotter"
        val DOWN = Reachability.Unreachable(UnreachableReason.FAILED, "the store is switched off")
    }
}

/**
 * One device's connection to a backend, with everything that goes wrong on the way. While [calm]
 * is false, each call can fail: a listing, a read, a write that never happened, a write that
 * failed although the file appeared, a backend that goes away, and a crash just after a write.
 * Each listing can also hold files back, so segments arrive late and out of order.
 */
internal class FlakyLink(
    private val store: LossyBlobStore,
    private val random: Random,
) : BlobStore {
    var calm: Boolean = false

    override suspend fun list(prefix: String): List<BlobInfo> {
        fail(LIST_FAILURE)
        val files = store.list(prefix)
        return if (calm) files else files.filter { random.nextInt(PERCENT) >= HOLD_BACK }
    }

    override suspend fun read(path: String): ByteArray {
        fail(READ_FAILURE)
        return store.read(path)
    }

    override suspend fun writeNew(
        path: String,
        bytes: ByteArray,
    ) {
        fail(WRITE_FAILURE)
        store.writeNew(path, bytes)
        fail(FAILED_AFTER_WRITE)
        if (!calm && random.nextInt(PERCENT) < CRASH_AFTER_WRITE) throw SimulatedCrash()
    }

    override suspend fun ping(): Reachability = store.ping()

    private fun fail(percent: Int) {
        if (calm || random.nextInt(PERCENT) >= percent) return
        if (random.nextBoolean()) {
            throw BlobStoreFailureException("simulated failure")
        }
        throw BackendUnavailableException(
            Reachability.Unreachable(UnreachableReason.FAILED, "simulated outage"),
        )
    }

    private companion object {
        const val PERCENT = 100
        const val LIST_FAILURE = 6
        const val READ_FAILURE = 4
        const val WRITE_FAILURE = 6
        const val FAILED_AFTER_WRITE = 8
        const val CRASH_AFTER_WRITE = 5
        const val HOLD_BACK = 12
    }
}
