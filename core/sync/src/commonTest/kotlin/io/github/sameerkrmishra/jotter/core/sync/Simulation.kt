package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.ImportedNote
import io.github.sameerkrmishra.jotter.core.storage.NoSuchNoteException
import io.github.sameerkrmishra.jotter.core.storage.NoteFilter
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.StaleEditException
import okio.Buffer
import okio.FileSystem
import okio.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Instant

/** One simulated installation, with a real SQLite database that survives its crashes. */
@Suppress("LongParameterList") // Each comes from the world's one generator.
internal class SimDevice(
    val index: Int,
    private val path: Path,
    private val clock: SimClock,
    private val ids: SimIds,
    private val random: Random,
    /** Which backends this device is set up with. */
    val links: Map<Int, FlakyLink>,
    private val crashes: () -> Boolean,
) {
    lateinit var store: NoteStore
        private set
    lateinit var engine: SyncEngine
        private set

    /** The conflict resolver of the current process. */
    lateinit var conflicts: ConflictResolver
        private set

    init {
        open()
    }

    fun advanceClock(by: Long) {
        clock.millis += by
    }

    fun restart() {
        store.close()
        open()
    }

    fun close() = store.close()

    private fun open() {
        store = NoteStore.open(path.toString(), "device $index", EmptyCoroutineContext, clock, ids)
        val probe =
            SyncProbe {
                if (crashes() && random.nextInt(CRASH_ODDS) == 0) throw SimulatedCrash()
            }
        engine =
            SyncEngine(
                store,
                DeviceInfo("device $index", "simulator"),
                EmptyCoroutineContext,
                clock,
                SyncSeams(probe = probe),
            )
        conflicts = ConflictResolver(store)
    }

    private companion object {
        const val CRASH_ODDS = 40
    }
}

/** What a case found, for the failure message and the pinned digest. */
internal data class SimOutcome(
    val digest: String,
    val revisions: Int,
    val conflictsSeen: Int,
    val crashes: Int,
    val merges: Int,
)

/**
 * A deterministic world of devices and backends. The same seed gives the same run on every
 * platform: the ids, the clocks, the faults and the choices all come from one generator.
 *
 * Random phase: devices edit offline, sync partially with one backend at a time, crash, restart,
 * and lose files. Then every fault stops and devices sync until nothing changes.
 */
@Suppress("TooManyFunctions") // One function per thing that can happen in the world.
internal class Simulation(
    private val seed: Long,
    private val dir: Path,
) {
    private val random = Random(seed)
    private val backendCount = random.nextInt(1, MAX_BACKENDS + 1)
    private val backends = List(backendCount) { LossyBlobStore() }
    private val devices: List<SimDevice> = createDevices()
    private val sources = List(SOURCE_NOTES) { sharedNoteId(it) }
    private var faulty = true
    private var crashes = 0
    private var conflictsSeen = 0
    private var merges = 0

    /** What the calm syncs of the last round still reported, for the failure message. */
    private val lastIssues = mutableListOf<SyncIssue>()

    /** Runs the whole case and returns its outcome, or fails with the seed in the message. */
    suspend fun run(): SimOutcome {
        try {
            repeat(random.nextInt(MIN_STEPS, MAX_STEPS)) { step() }
            heal()
            settle()
            val first = assertConverged()
            resolveEverything()
            assertSingleHeads()
            return SimOutcome(digest(), first, conflictsSeen, crashes, merges)
        } finally {
            devices.forEach(SimDevice::close)
        }
    }

    private fun createDevices(): List<SimDevice> {
        val count = random.nextInt(2, MAX_DEVICES + 1)
        val memberships = List(count) { mutableSetOf(it % backendCount) }
        // Device 0 relays between all backends, so they are connected, while most other devices
        // never share a backend.
        memberships[0] += backends.indices
        return List(count) { index ->
            val links =
                memberships[index].associateWith {
                    FlakyLink(backends[it], Random(random.nextLong()))
                }
            SimDevice(
                index = index,
                path = dir / "device-$index.db",
                clock = SimClock(START_MILLIS + random.nextLong(MAX_SKEW)),
                ids = SimIds(Random(random.nextLong())),
                random = Random(random.nextLong()),
                links = links,
                crashes = { faulty },
            )
        }
    }

    private fun sharedNoteId(index: Int): NoteId = SimIds(Random(seed + index)).newNoteId()

    private suspend fun step() {
        val device = devices.random(random)
        device.advanceClock(random.nextLong(1, MAX_CLOCK_STEP))
        when (random.nextInt(ACTIONS)) {
            0, 1, 2, 3, 4, 5 -> edit(device)
            6 -> create(device)
            7 -> importShared(device)
            8, 9 -> organise(device)
            10 -> delete(device)
            11, 12, 13, 14, 15 -> syncOnce(device, device.links.keys.random(random))
            16 -> resolveOne(device)
            17 -> loseSegment()
            18 -> flipBackend()
            19 -> plantBadFile()
            else -> device.restart()
        }
    }

    private fun allNotes(device: SimDevice): List<Note> =
        NoteState.entries.flatMap { device.store.notes.list(NoteFilter(state = it)) }

    private fun edit(device: SimDevice) {
        val note = allNotes(device).randomOrNull(random) ?: return
        val body = editedBody(note.body)
        val title =
            if (random.nextInt(TITLE_EDIT_ODDS) ==
                0
            ) {
                "t${random.nextInt(WORDS)}"
            } else {
                note.title
            }
        try {
            device.store.notes.edit(note.id, note.revisionId, title, body)
        } catch (_: StaleEditException) {
            error("The note was read just now, seed $seed")
        }
    }

    private fun editedBody(body: String): String {
        val lines = if (body.isEmpty()) mutableListOf() else body.split('\n').toMutableList()
        val word = "- [ ] item ${random.nextInt(WORDS)}"
        when {
            lines.isEmpty() -> lines += word
            random.nextBoolean() -> lines[random.nextInt(lines.size)] = word
            random.nextBoolean() -> lines.add(random.nextInt(lines.size + 1), word)
            else -> lines.removeAt(random.nextInt(lines.size))
        }
        return lines.joinToString("\n")
    }

    private fun create(device: SimDevice) {
        val lines = List(random.nextInt(1, MAX_LINES)) { "- [ ] item $it" }
        device.store.notes.create("t${random.nextInt(WORDS)}", lines.joinToString("\n"))
    }

    /** Two devices that import one source note give it two roots (ADR-0009). */
    private fun importShared(device: SimDevice) {
        val index = random.nextInt(sources.size)
        val time =
            Instant.fromEpochMilliseconds(
                IMPORT_MILLIS + index + random.nextLong(TAGS.toLong()),
            )
        device.store.notes.importNote(
            ImportedNote(
                id = sources[index],
                title = "imported $index",
                body = "- [ ] kept\n- [ ] line $index",
                tags = setOf(TagName.parse("keep")),
                pinned = random.nextBoolean(),
                state = NoteState.ACTIVE,
                createdAt = time,
                editedAt = time,
            ),
        )
    }

    private fun organise(device: SimDevice) {
        val note = allNotes(device).randomOrNull(random) ?: return
        val notes = device.store.notes
        val tag = TagName.parse("tag${random.nextInt(TAGS)}")
        when (random.nextInt(ORGANISE_ACTIONS)) {
            0 -> notes.setPinned(note.id, !note.isPinned)
            1 -> notes.setState(note.id, NoteState.entries.random(random))
            2 -> notes.addTag(note.id, tag)
            else -> notes.removeTag(note.id, tag)
        }
    }

    private fun delete(device: SimDevice) {
        val note = allNotes(device).randomOrNull(random) ?: return
        val notes = device.store.notes
        notes.setState(note.id, NoteState.TRASHED)
        try {
            notes.deleteForever(note.id)
        } catch (_: NoSuchNoteException) {
            error("The note was read just now, seed $seed")
        }
    }

    private suspend fun syncOnce(
        device: SimDevice,
        backend: Int,
    ) {
        val link = device.links.getValue(backend)
        link.calm = !faulty
        try {
            when (val result = device.engine.sync(SyncTarget("backend-$backend", link))) {
                is SyncResult.Completed -> {
                    merges += result.report.merges
                    if (!faulty) lastIssues += result.report.issues
                    result.report.issues.forEach { issue ->
                        check(issue !is SyncIssue.SegmentCollision) { "Seed $seed: $issue" }
                        check(faulty || issue !is SyncIssue.ImplausibleSegmentNumber) { "$issue" }
                    }
                }

                is SyncResult.Interrupted -> {
                    merges += result.report.merges
                    check(faulty) { "Seed $seed: ${result.cause} while calm" }
                }

                is SyncResult.Incompatible -> error("Seed $seed: ${result.detail}")
                is SyncResult.Unreachable -> check(faulty) { "Seed $seed: unreachable while calm" }
            }
        } catch (_: SimulatedCrash) {
            crashes++
            device.restart()
        }
    }

    private fun resolveOne(device: SimDevice) {
        val conflict = device.conflicts.open().randomOrNull(random) ?: return
        conflictsSeen++
        val resolution =
            when (random.nextInt(RESOLUTIONS)) {
                0 -> ConflictResolution.KeepShown
                1 -> ConflictResolution.KeepLosing
                2 -> ConflictResolution.KeepBoth
                else ->
                    ConflictResolution.Edited(
                        "resolved",
                        "merged by hand ${random.nextInt(WORDS)}",
                    )
            }
        device.conflicts.resolve(conflict.mergeId, resolution)
    }

    /** A power cut on a backend takes a published segment with it. Its writer must notice. */
    private suspend fun loseSegment() {
        val backend = backends.random(random)
        if (!backend.reachable) return
        val segments = backend.list(BackendLayout.DEVICES).filter { it.path.endsWith(".json.gz") }
        segments.randomOrNull(random)?.let { backend.lose(it.path) }
    }

    private fun flipBackend() {
        val backend = backends.random(random)
        backend.reachable = !backend.reachable
    }

    /** Puts a file in a stranger's folder that no device may accept, and none may trip over. */
    private suspend fun plantBadFile() {
        val backend = backends.random(random)
        if (!backend.reachable) return
        val stranger = SimIds(random).newDeviceId()
        val path = BackendLayout.segmentPath(stranger, 1)
        val good = JsonGzipCodec().encode(Segment(stranger, 1, emptyList()))
        val bytes =
            when (random.nextInt(BAD_FILES)) {
                0 -> good.copyOf(good.size / 2)
                1 -> random.nextBytes(good.size)
                else -> good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
            }
        backend.plant(path, bytes)
    }

    private fun heal() {
        faulty = false
        backends.forEach { it.reachable = true }
    }

    /** Syncs everyone with everything until a whole round adds nothing. */
    private suspend fun settle() {
        var previous = -1
        repeat(MAX_ROUNDS) {
            lastIssues.clear()
            devices.forEach { device ->
                device.links.keys.forEach { backend -> syncOnce(device, backend) }
            }
            val held = devices.map(::revisions)
            val total = held.sumOf { revisionSet -> revisionSet.size }
            val isSame = held.distinct().size == 1
            if (isSame && total == previous) return
            previous = total
        }
        val sizes = devices.map { revisions(it).size }
        error("Seed $seed did not settle in $MAX_ROUNDS rounds: $sizes, $lastIssues")
    }

    /** Every note any device has heard of. No backend has this name, so nothing is pushed there. */
    private fun knownNotes(): Set<NoteId> =
        devices.flatMapTo(HashSet()) { device ->
            device.store.sync
                .unpushed("no such backend")
                .mapNotNull {
                    device.store.sync
                        .revision(it)
                        ?.noteId
                }
        }

    private fun revisions(device: SimDevice): Set<Revision> =
        knownNotes().flatMapTo(HashSet()) { device.store.sync.revisions(it) }

    private fun assertConverged(): Int {
        val reference = devices.first()
        val expected = revisions(reference)
        devices.drop(1).forEach { device ->
            check(revisions(device) == expected) {
                "Seed $seed: device ${device.index} has other revisions than device 0"
            }
            check(state(device) == state(reference)) {
                "Seed $seed: device ${device.index} shows other notes than device 0: " +
                    "${state(device)} / ${state(reference)}"
            }
        }
        expected.groupBy { it.noteId }.forEach { (noteId, revisions) ->
            check(RevisionGraph(revisions).isComplete) { "Seed $seed: $noteId lacks an ancestor" }
        }
        return expected.size
    }

    /** Everything a device shows or decides from, without what is local to it. */
    private fun state(device: SimDevice): List<Any?> =
        knownNotes().sortedBy { it.toString() }.map { id ->
            listOf(
                device.store.notes.get(id),
                RevisionGraph(device.store.sync.revisions(id)).liveHeads.map { it.id },
            )
        } + listOf(device.conflicts.open())

    /** The last word: with every conflict answered, each note has one head everywhere. */
    private suspend fun resolveEverything() {
        repeat(MAX_ROUNDS) {
            val open = devices.first().conflicts.open()
            if (open.isEmpty()) return
            conflictsSeen += open.size
            open.forEach { conflict ->
                if (devices.first().conflicts.details(conflict.mergeId) != null) {
                    val resolver = devices.first().conflicts
                    resolver.resolve(conflict.mergeId, ConflictResolution.KeepShown)
                }
            }
            settle()
            assertConverged()
        }
        error("Seed $seed: conflicts did not end")
    }

    private fun assertSingleHeads() {
        val device = devices.first()
        check(device.conflicts.open().isEmpty()) { "Seed $seed: conflicts stayed open" }
        knownNotes().forEach { id ->
            val revisions = device.store.sync.revisions(id)
            if (revisions.isNotEmpty()) {
                val graph = RevisionGraph(revisions)
                check(graph.liveHeads.size == 1) { "Seed $seed: note $id has ${graph.liveHeads}" }
                val shown = device.store.notes.get(id)
                check(shown?.revisionId == graph.liveHeads.single().id || shown == null) {
                    "Seed $seed: note $id does not show its head"
                }
            }
        }
    }

    /** A digest of the final state: the same on every platform, and pinned by a test. */
    private fun digest(): String {
        val out = Buffer()
        val device = devices.first()
        val revisions = revisions(device).sortedBy { it.id }
        revisions.forEach { revision ->
            out.writeUtf8(revision.id.toString())
            if (revision.kind != RevisionKind.User) out.write(canonicalMergeBytes(revision))
        }
        knownNotes().sortedBy { it.toString() }.forEach { id ->
            device.store.notes.get(id)?.let { note ->
                out.writeUtf8("${note.id}|${note.revisionId}|${note.title}|${note.body}|")
                out.writeUtf8("${note.tags.map(TagName::toString).sorted()}|${note.isPinned}|")
                out.writeUtf8("${note.state}|${note.createdAt}|${note.updatedAt}\n")
            }
        }
        return out.sha256().hex()
    }

    /** Sizes and odds of the world. */
    private companion object {
        const val MAX_BACKENDS = 3
        const val MAX_DEVICES = 5
        const val MIN_STEPS = 30
        const val MAX_STEPS = 140
        const val ACTIONS = 21
        const val SOURCE_NOTES = 3
        const val START_MILLIS = 1_800_000_000_000L
        const val IMPORT_MILLIS = 1_500_000_000_000L
        const val MAX_SKEW = 3_600_000L
        const val MAX_CLOCK_STEP = 5_000L
        const val TITLE_EDIT_ODDS = 5
        const val WORDS = 6
        const val MAX_LINES = 7
        const val TAGS = 4
        const val ORGANISE_ACTIONS = 4
        const val RESOLUTIONS = 4
        const val BAD_FILES = 3
        const val MAX_ROUNDS = 25
    }
}

/** Runs one case in a fresh directory, which is deleted afterwards. */
internal suspend fun simulate(seed: Long): SimOutcome {
    val dir =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-sync-sim-${Random.nextLong().toULong()}"
    FileSystem.SYSTEM.createDirectories(dir)
    try {
        return Simulation(seed, dir).run()
    } finally {
        FileSystem.SYSTEM.deleteRecursively(dir)
    }
}
