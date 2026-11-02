package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.HiddenReason
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

    private var backup: Path? = null

    /** Whether a backup is there to restore. */
    val hasBackup: Boolean get() = backup != null

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

    /** Copies the database file, as a user does with the app closed. */
    fun takeBackup() {
        store.close()
        val copy = path.parent!! / "${path.name}.backup"
        FileSystem.SYSTEM.delete(copy, mustExist = false)
        FileSystem.SYSTEM.copy(path, copy)
        backup = copy
        open()
    }

    /** Puts the backup back, and forgets all that happened since. */
    fun restoreBackup() {
        store.close()
        FileSystem.SYSTEM.delete(path)
        FileSystem.SYSTEM.copy(checkNotNull(backup), path)
        open()
    }

    /** Copies the backup to [target], as a user does who moves to a second machine. */
    fun copyBackupTo(target: Path) {
        FileSystem.SYSTEM.copy(checkNotNull(backup), target)
    }

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
    val badStamps: Int,
    val restores: Int,
    val diskRestores: Int,
)

/** A local-folder backend as it was when a device took its backup, on the same disk. */
private class DiskImage(
    val backend: LossyBlobStore,
    val files: Map<String, ByteArray>,
)

/**
 * A deterministic world of devices and backends. The same seed gives the same run on every
 * platform: the ids, the clocks, the faults and the choices all come from one generator.
 *
 * Random phase: devices edit offline, sync partially with one backend at a time, crash, restart,
 * die for good, forget a backend and add it again, restore old backups, also of a whole disk that
 * holds a backend, and lose files, and strangers plant damaged files, forged merges and revisions
 * with bad stamps. Then every fault stops and the living sync until nothing changes.
 *
 * Every line and title is unique, so that [LineHistory] can follow it through the DAG, and of one
 * length, so that what a restored device writes again is of the size of what it forgot.
 */
@Suppress("TooManyFunctions", "LargeClass") // One function per thing that can happen in the world.
internal class Simulation(
    private val seed: Long,
    private val dir: Path,
) {
    private val random = Random(seed)
    private val backendCount = random.nextInt(1, MAX_BACKENDS + 1)
    private val backends = List(backendCount) { LossyBlobStore() }
    private val devices = createDevices().toMutableList()
    private val sources = List(SOURCE_NOTES) { sharedNoteId(it) }
    private var faulty = true
    private var crashes = 0
    private var conflictsSeen = 0
    private var merges = 0
    private var lines = 0
    private var restores = 0
    private var diskRestores = 0

    /** The devices that still run. Device 0, the relay, never dies. */
    private val alive = devices.toMutableList()

    /** The disk images taken with each device's backup, by device. */
    private val diskImages = HashMap<Int, DiskImage>()

    /** Revisions planted that must never count, on any device. */
    private val forged = HashSet<RevisionId>()

    /** What the calm syncs of the last round still reported, for the failure message. */
    private val lastIssues = mutableListOf<SyncIssue>()

    /** Runs the whole case and returns its outcome, or fails with the seed in the message. */
    suspend fun run(): SimOutcome {
        try {
            repeat(random.nextInt(MIN_STEPS, MAX_STEPS)) { step() }
            heal()
            settle()
            val first = assertConverged()
            assertTextSurvives()
            challengeAConflict()
            resolveEverything()
            assertSingleHeads()
            assertTextSurvives()
            return SimOutcome(
                digest(),
                first,
                conflictsSeen,
                crashes,
                merges,
                forged.size,
                restores,
                diskRestores,
            )
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
            device(index, dir / "device-$index.db", memberships[index])
        }
    }

    private fun device(
        index: Int,
        path: Path,
        memberOf: Set<Int>,
    ): SimDevice {
        val links = memberOf.associateWith { FlakyLink(backends[it], Random(random.nextLong())) }
        return SimDevice(
            index = index,
            path = path,
            clock = SimClock(START_MILLIS + random.nextLong(MAX_SKEW)),
            ids = SimIds(Random(random.nextLong())),
            random = Random(random.nextLong()),
            links = links,
            crashes = { faulty },
        )
    }

    private fun sharedNoteId(index: Int): NoteId = SimIds(Random(seed + index)).newNoteId()

    @Suppress("CyclomaticComplexMethod") // A plain table of what can happen.
    private suspend fun step() {
        val device = alive.random(random)
        device.advanceClock(random.nextLong(1, MAX_CLOCK_STEP))
        when (random.nextInt(ACTIONS)) {
            0, 1, 2, 3, 4, 5 -> edit(device)
            6 -> create(device)
            7 -> importShared(device)
            8, 9 -> organise(device)
            10, 26, 27, 28, 29 -> delete(device)
            30 -> plantClaimOnOpenConflict(device)
            11, 12, 13, 14, 15 -> syncOnce(device, device.links.keys.random(random))
            16 -> resolveOne(device)
            17 -> loseSegment()
            18 -> flipBackend()
            19 -> plantBadFile()
            20 -> plantForgedMerge(device)
            21 -> forgetBackend(device)
            22 -> kill(device)
            23 -> plantBadStamp(device)
            24 -> backUpOrRestore(device)
            25 -> settleOne(device)
            else -> device.restart()
        }
    }

    private fun nextNumber(): String = (lines++).toString().padStart(NUMBER_DIGITS, '0')

    private fun newLine(): String = "- [ ] item ${random.nextInt(WORDS)} #${nextNumber()}"

    private fun newTitle(): String = "title #${nextNumber()}"

    /** A device that never comes back, with whatever it did not push. Its files stay. */
    private fun kill(device: SimDevice) {
        if (device.index != 0 && random.nextInt(KILL_ODDS) == 0) alive -= device
    }

    /** Removes a backend and adds it again, which keeps its files (PLAN.md §6.5). */
    private suspend fun forgetBackend(device: SimDevice) {
        device.engine.forget("backend-${device.links.keys.random(random)}")
    }

    /**
     * Takes a backup, with an image of a backend on the same disk, or goes back to either, or
     * starts a second installation from the backup. Everything the device did since is gone from
     * it. What it published comes back from its folder, unless the disk took that along too: then
     * it is only on the devices that read it, and they push it again.
     */
    private suspend fun backUpOrRestore(device: SimDevice) {
        val image = diskImages[device.index]
        when {
            !device.hasBackup -> {
                device.takeBackup()
                val backend = backends[device.links.keys.random(random)]
                if (backend.reachable) {
                    diskImages[device.index] = DiskImage(backend, backend.image())
                }
            }

            image != null && image.backend.reachable && random.nextBoolean() -> {
                device.restoreBackup()
                image.backend.rollBack(image.files)
                restores++
                diskRestores++
            }

            devices.size < MAX_DEVICES + MAX_CLONES && random.nextBoolean() -> {
                val index = devices.size
                val path = dir / "device-$index.db"
                device.copyBackupTo(path)
                val clone = device(index, path, device.links.keys)
                devices += clone
                alive += clone
                restores++
            }

            else -> {
                device.restoreBackup()
                restores++
            }
        }
    }

    /** Copies a merge under another device: the hash is right, and the parents disagree. */
    private suspend fun plantForgedMerge(device: SimDevice) {
        val backend = backends.random(random)
        val merge =
            sortedRevisions(device).filter { it.kind != RevisionKind.User }.randomOrNull(random)
                ?: return
        if (!backend.reachable) return
        val stranger = SimIds(random).newDeviceId()
        val changed = merge.copy(deviceId = stranger)
        val fake = changed.copy(id = mergeRevisionId(changed))
        forged += fake.id
        val segment = Segment(stranger, 1, listOf(fake))
        backend.plant(pathOf(segment), JsonGzipCodec().encode(segment))
    }

    /**
     * A segment from a device with a broken clock: a real child of a note, stamped below its
     * parent, with something built on it, next to a good revision of another note, which must
     * count on every device. Arrival order is random, as devices sync at random.
     */
    private suspend fun plantBadStamp(device: SimDevice) {
        val backend = backends.random(random)
        val parent = sortedRevisions(device).filter(::hasText).randomOrNull(random) ?: return
        if (!backend.reachable) return
        val stranger = SimIds(random).newDeviceId()
        val ids = SimIds(random)
        val text = parent.content as RevisionContent.Snapshot
        val bad =
            parent.copy(
                id = ids.newRevisionId(),
                parents = setOf(parent.id),
                timestamp = HybridTimestamp.ZERO,
                deviceId = stranger,
                kind = RevisionKind.User,
                content = text.copy(body = newLine()),
                resolves = null,
            )
        val onTop =
            bad.copy(
                id = ids.newRevisionId(),
                parents = setOf(bad.id),
                timestamp = HybridTimestamp(1, 0),
                content = text.copy(body = newLine()),
            )
        val good =
            bad.copy(
                id = ids.newRevisionId(),
                noteId = ids.newNoteId(),
                parents = emptySet(),
                content = text.copy(body = newLine()),
            )
        forged += bad.id
        val segment = Segment(stranger, 1, listOf(onTop, bad, good).shuffled(random))
        backend.plant(pathOf(segment), JsonGzipCodec().encode(segment))
    }

    /**
     * A revision that says it resolves a conflict, on a parent in another note. It does not count,
     * so the conflict stays open, on every device.
     */
    private suspend fun plantForeignClaim(
        device: SimDevice,
        mergeId: RevisionId,
    ) {
        val merge = checkNotNull(device.store.sync.revision(mergeId)) { "$mergeId is gone" }
        val others = sortedRevisions(device).filter { it.noteId != merge.noteId }
        val foreign = others.randomOrNull(random)
        val backend = backends[device.links.keys.random(random)]
        if (foreign == null || !backend.reachable) return
        val ids = SimIds(random)
        val stranger = ids.newDeviceId()
        val claim =
            merge.copy(
                id = ids.newRevisionId(),
                parents = setOf(foreign.id),
                timestamp = HybridTimestamp(merge.timestamp.physicalMillis + 1, 0),
                deviceId = stranger,
                kind = RevisionKind.User,
                resolves = mergeId,
            )
        forged += claim.id
        val segment = Segment(stranger, 1, listOf(claim))
        backend.plant(pathOf(segment), JsonGzipCodec().encode(segment))
    }

    private suspend fun plantClaimOnOpenConflict(device: SimDevice) {
        val conflict = device.conflicts.open().randomOrNull(random) ?: return
        plantForeignClaim(device, conflict.mergeId)
    }

    /**
     * Every run ends on one conflict for sure, as open ones are rare: nothing but an answer may
     * close it. Not a claim from a revision that does not count, and not a deletion beside an edit.
     * This function made that history, so it knows the conflict must still be open everywhere.
     */
    private suspend fun challengeAConflict() {
        if (alive.size < 2) return
        val (first, second) = alive
        val note = first.store.notes.create(newTitle(), newLine())
        first.store.notes.create(newTitle(), newLine())
        settle()
        val body = listOf(first, second).map { device -> device to newLine() }
        body.forEach { (device, line) ->
            device.store.notes.edit(note.id, note.revisionId, note.title, line)
        }
        settle()
        val conflict = first.conflicts.open().firstOrNull { it.noteId == note.id }
        val mergeId = checkNotNull(conflict) { "Seed $seed: two edits made no conflict" }.mergeId
        plantForeignClaim(first, mergeId)
        settle()
        assertConflictStands(mergeId)
        val shown = checkNotNull(second.store.notes.get(note.id)) { "Seed $seed: the note is gone" }
        second.store.notes.edit(note.id, shown.revisionId, shown.title, newLine())
        delete(first, note.id)
        settle()
        assertConflictStands(mergeId)
    }

    private fun assertConflictStands(mergeId: RevisionId) {
        alive.forEach { device ->
            val isListed = device.conflicts.open().any { it.mergeId == mergeId }
            check(isListed) { "Seed $seed: device ${device.index} closed $mergeId unanswered" }
            checkNotNull(device.conflicts.details(mergeId)) {
                "Seed $seed: device ${device.index} has no details of $mergeId"
            }
        }
    }

    private fun hasText(revision: Revision): Boolean = revision.content is RevisionContent.Snapshot

    /** In a fixed order: a hash of a [Revision] holds an enum's, which differs between runs. */
    private fun sortedRevisions(device: SimDevice): List<Revision> =
        revisions(device).sortedBy { it.id }

    private fun allNotes(device: SimDevice): List<Note> =
        NoteState.entries.flatMap { device.store.notes.list(NoteFilter(state = it)) }

    private fun edit(device: SimDevice) {
        val note = allNotes(device).randomOrNull(random) ?: return
        val body = editedBody(note.body)
        val changesTitle = random.nextInt(TITLE_EDIT_ODDS) == 0
        val title = if (changesTitle) newTitle() else note.title
        try {
            device.store.notes.edit(note.id, note.revisionId, title, body)
        } catch (_: StaleEditException) {
            error("The note was read just now, seed $seed")
        }
    }

    private fun editedBody(body: String): String {
        val lines = if (body.isEmpty()) mutableListOf() else body.split('\n').toMutableList()
        val word = newLine()
        when {
            lines.isEmpty() -> lines += word
            random.nextBoolean() -> lines[random.nextInt(lines.size)] = word
            random.nextBoolean() -> lines.add(random.nextInt(lines.size + 1), word)
            else -> lines.removeAt(random.nextInt(lines.size))
        }
        return lines.joinToString("\n")
    }

    private fun create(device: SimDevice) {
        val lines = List(random.nextInt(1, MAX_LINES)) { newLine() }
        device.store.notes.create(newTitle(), lines.joinToString("\n"))
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
        delete(device, note.id)
    }

    private fun delete(
        device: SimDevice,
        noteId: NoteId,
    ) {
        val notes = device.store.notes
        notes.setState(noteId, NoteState.TRASHED)
        try {
            notes.deleteForever(noteId)
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
                }

                is SyncResult.Interrupted -> {
                    merges += result.report.merges
                    check(faulty) { "Seed $seed: ${result.cause} while calm" }
                }

                is SyncResult.Incompatible -> {
                    error("Seed $seed: ${result.detail}")
                }

                is SyncResult.Unreachable -> {
                    check(faulty) { "Seed $seed: unreachable while calm" }
                }
            }
        } catch (_: SimulatedCrash) {
            crashes++
            device.restart()
        }
    }

    private fun resolveOne(device: SimDevice) {
        val conflict = device.conflicts.open().randomOrNull(random) ?: return
        conflictsSeen++
        device.conflicts.resolve(conflict.mergeId, randomResolution())
    }

    private fun settleOne(device: SimDevice) {
        val head =
            device.conflicts
                .hidden()
                .filter { it.reason == HiddenReason.MISSING_ANCESTOR }
                .randomOrNull(random)
        if (head != null) device.conflicts.settleHidden(head.revisionId, randomResolution())
    }

    private fun randomResolution(): ConflictResolution =
        when (random.nextInt(RESOLUTIONS)) {
            0 -> ConflictResolution.KeepShown
            1 -> ConflictResolution.KeepLosing
            2 -> ConflictResolution.KeepBoth
            else -> ConflictResolution.Edited(newTitle(), newLine())
        }

    /** A power cut on a backend takes a published segment with it. Its writer must notice. */
    private suspend fun loseSegment() {
        val backend = backends.random(random)
        if (!backend.reachable) return
        val segments =
            backend.list(BackendLayout.DEVICES).filter { file ->
                file.path.endsWith(".json.gz")
            }
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
        val good = JsonGzipCodec().encode(Segment(stranger, 1, emptyList()))
        val bytes =
            when (random.nextInt(BAD_FILES)) {
                0 -> good.copyOf(good.size / 2)
                1 -> random.nextBytes(good.size)
                2 -> good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
                else -> wellFormedSegment(stranger)
            }
        backend.plant(junkPath(stranger, 1), bytes)
    }

    /** A sound segment, for a path whose hash it does not have: it must not count. */
    private fun wellFormedSegment(stranger: DeviceId): ByteArray {
        val ids = SimIds(random)
        val revision =
            textRevision(1, IMPORT_MILLIS, newLine(), device = stranger).copy(
                id = ids.newRevisionId(),
                noteId = ids.newNoteId(),
            )
        forged += revision.id
        return JsonGzipCodec().encode(Segment(stranger, 1, listOf(revision)))
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
            alive.forEach { device ->
                device.links.keys.forEach { backend -> syncOnce(device, backend) }
            }
            val held = alive.map(::revisions)
            val total = held.sumOf { revisionSet -> revisionSet.size }
            val isSame = held.distinct().size == 1
            if (isSame && total == previous) return
            previous = total
        }
        val sizes = alive.map { revisions(it).size }
        error("Seed $seed did not settle in $MAX_ROUNDS rounds: $sizes, $lastIssues")
    }

    /** Every note any device has heard of. No backend has this name, so nothing is pushed there. */
    private fun knownNotes(): Set<NoteId> =
        alive.flatMapTo(HashSet()) { device ->
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
        val reference = alive.first()
        val expected = revisions(reference)
        alive.drop(1).forEach { device ->
            check(revisions(device) == expected) {
                "Seed $seed: device ${device.index} has other revisions"
            }
            check(state(device) == state(reference)) {
                "Seed $seed: device ${device.index} shows other notes: " +
                    "${state(device)} / ${state(reference)}"
            }
        }
        expected.groupBy { it.noteId }.forEach { (noteId, revisions) ->
            val graph = storedGraph(reference.store.sync, noteId)
            // Only a dead writer or a restored backup can leave a hole.
            val mayLack = alive.size < devices.size || restores > 0
            check(graph.isComplete || mayLack) { "Seed $seed: $noteId lacks an ancestor" }
            // With its parent gone for good, a bad stamp can never show: it stays as it is.
            val counted =
                forged.filter { id ->
                    graph[id]?.parents?.all { graph[it] != null } ==
                        true
                }
            check(counted.isEmpty()) { "Seed $seed: a bad revision counts: $counted" }
            val automatic =
                revisions.filter {
                    it.kind != RevisionKind.User && graph[it.id] != null
                }
            automatic.forEach { assertRederives(graph, it) }
        }
        return expected.size
    }

    /**
     * Nothing a person wrote and nobody removed may vanish. Where the history cannot explain the
     * absence of a line from a head that counts, it was lost. Text the DAG refuses is not in the
     * graph, and each such revision must be listed instead.
     */
    private fun assertTextSurvives() {
        val device = alive.first()
        val open = device.conflicts.open().mapTo(HashSet()) { it.mergeId }
        val listed = device.conflicts.invalid().mapTo(HashSet()) { it.revisionId }
        revisions(device).groupBy { it.noteId }.values.forEach { note ->
            val graph = storedGraph(device.store.sync, note.first().noteId)
            val answered = open + note.mapNotNull { it.resolves }
            val texts =
                note
                    .filter { graph[it.id] != null }
                    .mapNotNull { it.content as? RevisionContent.Snapshot }
            listOf(bodyLines, titleLine).forEach { field ->
                texts
                    .flatMapTo(HashSet(), field)
                    .filterTo(HashSet(), String::isNotEmpty)
                    .forEach { line -> assertLineExplained(graph, field, line, answered) }
            }
            graph.invalidEnds.forEach {
                check(it.id in listed) { "Seed $seed: ${it.id} is hidden in silence" }
            }
        }
    }

    private fun assertLineExplained(
        graph: RevisionGraph,
        field: TextField,
        line: String,
        answered: Set<RevisionId>,
    ) {
        val history = LineHistory(graph, line, field)
        graph.liveHeads.forEach { head ->
            val state = history.stateAt(head)
            val isLost =
                state == LineState.Lost ||
                    (state is LineState.Waiting && state.conflict !in answered)
            check(!isLost) { "Seed $seed: $line is $state at ${head.id}\n${histories(graph)}" }
        }
    }

    /** A note revision by revision, for the failure message. */
    private fun histories(graph: RevisionGraph): String {
        val revisions =
            graph.heads
                .flatMap {
                    graph.ancestors(
                        it.id,
                    )
                }.toSet()
                .mapNotNull(graph::get)
        return revisions.sortedBy { it.stamp }.joinToString("\n") { describe(it) }
    }

    private fun shortId(id: RevisionId): String = id.toString().take(SHORT_ID)

    private fun describe(revision: Revision): String {
        val snapshot = revision.content as? RevisionContent.Snapshot
        val text = snapshot?.body?.replace('\n', '|')?.replace("- [ ] item ", "")
        val parents = revision.parents.map(::shortId)
        return "${shortId(revision.id)} ${revision.kind} p=$parents res=${revision.resolves} " +
            "${snapshot?.state?.value} ${snapshot?.title}: $text"
    }

    /**
     * A merge must follow from its parents and their ancestors alone. A device that merged on too
     * little would still converge, on a revision no other device would have computed.
     */
    private fun assertRederives(
        graph: RevisionGraph,
        merge: Revision,
    ) {
        val (a, b) = merge.parents.map { checkNotNull(graph[it]) }
        check(!graph.canMerge(a, b) || mergeRevisions(graph, a, b) == merge) {
            "Seed $seed: merge ${merge.id} does not follow from its parents"
        }
    }

    /** Everything a device shows or decides from, without what is local to it. */
    private fun state(device: SimDevice): List<Any?> =
        knownNotes().sortedBy { it.toString() }.map { id ->
            listOf(
                device.store.notes.get(id),
                storedGraph(device.store.sync, id).liveHeads.map { it.id },
            )
        } +
            listOf(
                device.conflicts.open(),
                device.conflicts.hidden(),
                device.conflicts.invalid(),
            )

    /** The last word: with everything answered, each note has one head everywhere. */
    private suspend fun resolveEverything() {
        val resolver = alive.first().conflicts
        repeat(MAX_ROUNDS) {
            val open = resolver.open()
            val waiting =
                resolver.hidden().filter { head -> head.reason == HiddenReason.MISSING_ANCESTOR }
            if (open.isEmpty() && waiting.isEmpty()) return
            conflictsSeen += open.size
            open.forEach { conflict ->
                if (resolver.details(conflict.mergeId) != null) {
                    resolver.resolve(conflict.mergeId, ConflictResolution.KeepBoth)
                }
            }
            // A resolution above can have merged these away. KeepBoth loses no line.
            waiting.forEach { head ->
                // Settling one can merge another of its note.
                if (head in resolver.hidden()) {
                    resolver.settleHidden(head.revisionId, ConflictResolution.KeepBoth)
                }
            }
            settle()
            assertConverged()
        }
        error("Seed $seed: conflicts did not end")
    }

    private fun assertSingleHeads() {
        val device = alive.first()
        check(device.conflicts.open().isEmpty()) { "Seed $seed: conflicts stayed open" }
        check(device.conflicts.hidden().isEmpty()) { "Seed $seed: heads stayed hidden" }
        knownNotes().forEach { id ->
            val graph = storedGraph(device.store.sync, id)
            val live = graph.liveHeads
            check(live.size <= 1) { "Seed $seed: note $id has $live" }
            val shown = device.store.notes.get(id)
            check(shown == null || shown.revisionId == live.single().id) {
                "Seed $seed: note $id does not show its head"
            }
        }
    }

    /** A digest of the final state: the same on every platform, and pinned by a test. */
    private fun digest(): String {
        val out = Buffer()
        val device = alive.first()
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
        const val MAX_CLONES = 2
        const val MIN_STEPS = 30
        const val MAX_STEPS = 140
        const val ACTIONS = 32
        const val KILL_ODDS = 3
        const val SHORT_ID = 6
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
        const val BAD_FILES = 4
        const val NUMBER_DIGITS = 6
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
