package io.github.sameerkrmishra.jotter.core.sync

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
 * die for good, forget a backend and add it again, and lose files, and strangers plant damaged
 * files and forged merges. Then every fault stops and the living sync until nothing changes.
 *
 * Every line is unique, so that the simulator can follow it: a line somebody wrote and nobody
 * removed must stay in a note's text, an open conflict or a hidden head.
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
    private var lines = 0

    /** The devices that still run. Device 0, the relay, never dies. */
    private val alive = devices.toMutableList()

    /** Every line written, and those a person removed: by editing, deleting or resolving. */
    private val written = HashSet<String>()
    private val removed = HashSet<String>()

    /** Forged merges planted, none of which may ever be stored. */
    private val forged = HashSet<RevisionId>()

    /** Links whose next sync must see the whole folder, after a backend was added again. */
    private val readded = HashSet<FlakyLink>()

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
            resolveEverything()
            assertSingleHeads()
            assertTextSurvives()
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

    @Suppress("CyclomaticComplexMethod") // A plain table of what can happen.
    private suspend fun step() {
        val device = alive.random(random)
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
            20 -> plantForgedMerge(device)
            21 -> forgetBackend(device)
            22 -> kill(device)
            else -> device.restart()
        }
    }

    private fun newLine(): String {
        val line = "- [ ] item ${random.nextInt(WORDS)} #${lines++}"
        written += line
        return line
    }

    private fun linesOf(text: String): Set<String> =
        text.split('\n').filterTo(HashSet(), String::isNotEmpty)

    /** A device that never comes back, with whatever it did not push. Its files stay. */
    private fun kill(device: SimDevice) {
        if (device.index != 0 && random.nextInt(KILL_ODDS) == 0) alive -= device
    }

    /** Removes a backend and adds it again, which keeps its files (PLAN.md §6.5). */
    private suspend fun forgetBackend(device: SimDevice) {
        val backend = device.links.keys.random(random)
        device.engine.forget("backend-$backend")
        readded += device.links.getValue(backend)
    }

    /** Copies a merge under another device: the hash is right, and the parents disagree. */
    private suspend fun plantForgedMerge(device: SimDevice) {
        val backend = backends.random(random)
        val merge =
            revisions(device).filter { it.kind != RevisionKind.User }.randomOrNull(random) ?: return
        if (!backend.reachable) return
        val stranger = SimIds(random).newDeviceId()
        val changed = merge.copy(deviceId = stranger)
        val fake = changed.copy(id = mergeRevisionId(changed))
        forged += fake.id
        val segment = Segment(stranger, 1, listOf(fake))
        backend.plant(BackendLayout.segmentPath(stranger, 1), JsonGzipCodec().encode(segment))
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
        removed += linesOf(note.body) - linesOf(body)
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
        removed += linesOf(note.body)
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
        link.calm = !faulty || link in readded
        try {
            when (val result = device.engine.sync(SyncTarget("backend-$backend", link))) {
                is SyncResult.Completed -> {
                    readded -= link
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
        val shown = device.conflicts.details(conflict.mergeId)?.shownBody ?: return
        val resolution = randomResolution(shown, conflict.losingBody)
        device.conflicts.resolve(conflict.mergeId, resolution)
    }

    /** A random answer, with the lines it gives up noted down. */
    private fun randomResolution(
        shown: String,
        losing: String,
    ): ConflictResolution =
        when (random.nextInt(RESOLUTIONS)) {
            0 -> {
                removed += linesOf(losing) - linesOf(shown)
                ConflictResolution.KeepShown
            }

            1 -> {
                removed += linesOf(shown) - linesOf(losing)
                ConflictResolution.KeepLosing
            }

            2 -> {
                ConflictResolution.KeepBoth
            }

            else -> {
                removed += linesOf(shown) + linesOf(losing)
                ConflictResolution.Edited("resolved", newLine())
            }
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
        check(expected.none { it.id in forged }) { "Seed $seed: a forged merge was stored" }
        alive.drop(1).forEach { device ->
            check(revisions(device) == expected) {
                "Seed $seed: device ${device.index} has other revisions than device 0"
            }
            check(state(device) == state(reference)) {
                "Seed $seed: device ${device.index} shows other notes than device 0: " +
                    "${state(device)} / ${state(reference)}"
            }
        }
        expected.groupBy { it.noteId }.forEach { (noteId, revisions) ->
            val graph = RevisionGraph(revisions)
            // Only a dead writer can leave a hole: a live one repairs what was lost.
            val mayLack = alive.size < devices.size
            check(graph.isComplete || mayLack) { "Seed $seed: $noteId lacks an ancestor" }
            if (graph.isComplete) {
                val automatic = revisions.filter { it.kind != RevisionKind.User }
                automatic.forEach { assertRederives(graph, it) }
            }
        }
        return expected.size
    }

    /**
     * Nothing a person wrote and nobody removed may vanish: each such line that reached the living
     * is in a note's text, in the text of an open conflict, or in a hidden head. A note whose every
     * head is a tombstone is gone on purpose, with a conflict it may have had. A deletion counts
     * for no other line, so that a conflict's losing text must outlive a deletion beside an edit.
     */
    private fun assertTextSurvives() {
        val device = alive.first()
        val everWritten =
            revisions(device)
                .groupBy { it.noteId }
                .values
                .filter { note ->
                    RevisionGraph(note).heads.any { it.content is RevisionContent.Snapshot }
                }.flatten()
                .mapNotNull { it.content as? RevisionContent.Snapshot }
                .flatMapTo(HashSet()) { linesOf(it.body) }
        val findable = HashSet<String>()
        allNotes(device).forEach { findable += linesOf(it.body) }
        device.conflicts.open().forEach { findable += linesOf(it.losingBody) }
        device.conflicts.hidden().forEach { findable += linesOf(it.body) }
        val given = linesGivenUpByDeleting(device)
        val lost =
            (written - removed).filter { it in everWritten && it !in findable && it !in given }
        check(lost.isEmpty()) { "Seed $seed: text was lost: $lost\n${histories(device, lost)}" }
    }

    /**
     * The text that lost a conflict, where a user deleted the note on top of that conflict and no
     * edit went on from it: they gave that text up, even if another branch brings the note back.
     * Worked out here from the DAG alone, not with the code under test, so that a wrong rule for
     * closing conflicts shows as lost text.
     */
    private fun linesGivenUpByDeleting(device: SimDevice): Set<String> {
        val given = HashSet<String>()
        revisions(device).groupBy { it.noteId }.values.forEach { note ->
            val byId = note.associateBy { it.id }
            val children = HashMap<RevisionId, MutableList<Revision>>()
            note.forEach { child ->
                child.parents.forEach { children.getOrPut(it) { mutableListOf() } += child }
            }
            note.filter { it.isConflictedMerge() }.forEach { conflict ->
                if (isOnlyDeletedOnTop(conflict, children)) {
                    val loser = (conflict.kind as RevisionKind.AutomaticMerge).losingParent
                    val text = byId[loser]?.content as? RevisionContent.Snapshot
                    given += linesOf(text?.body.orEmpty())
                }
            }
        }
        return given
    }

    /** Whether every way up from [conflict] ends in a tombstone, and there is at least one way. */
    private fun isOnlyDeletedOnTop(
        conflict: Revision,
        children: Map<RevisionId, List<Revision>>,
    ): Boolean {
        val stack = ArrayDeque(children[conflict.id].orEmpty())
        var isDeleted = stack.isNotEmpty()
        val seen = HashSet<RevisionId>()
        while (stack.isNotEmpty()) {
            val revision = stack.removeLast()
            val above = children[revision.id].orEmpty()
            val hasText = revision.content is RevisionContent.Snapshot
            if (hasText && above.isEmpty()) isDeleted = false
            if (seen.add(revision.id) && hasText) stack.addAll(above)
        }
        return isDeleted
    }

    /** The notes that hold [lines], revision by revision, for the failure message. */
    private fun histories(
        device: SimDevice,
        lines: List<String>,
    ): String {
        val all = revisions(device)
        val notes =
            all
                .filter { revision ->
                    val body = (revision.content as? RevisionContent.Snapshot)?.body.orEmpty()
                    lines.any { line -> line in linesOf(body) }
                }.map { it.noteId }
                .toSet()
        return notes.joinToString("\n") { noteId ->
            val revisions = all.filter { it.noteId == noteId }.sortedBy { it.stamp }
            val graph = RevisionGraph(revisions)
            val heads = graph.heads.map(::shortId)
            val live = graph.liveHeads.map(::shortId)
            val top = "$noteId heads=$heads live=$live open=${graph.openConflicts.map(::shortId)}"
            top + revisions.joinToString("") { "\n  ${describe(it)}" }
        }
    }

    private fun shortId(revision: Revision): String = revision.id.toString().take(SHORT_ID)

    private fun describe(revision: Revision): String {
        val snapshot = revision.content as? RevisionContent.Snapshot
        val text = snapshot?.body?.replace('\n', '|')?.replace("- [ ] item ", "")
        val parents = revision.parents.map { it.toString().take(SHORT_ID) }
        return "${shortId(revision)} ${revision.kind} p=$parents res=${revision.resolves} " +
            "${snapshot?.state?.value} $text"
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
        check(mergeRevisions(graph, a, b) == merge) {
            "Seed $seed: merge ${merge.id} does not follow from its parents"
        }
    }

    /** Everything a device shows or decides from, without what is local to it. */
    private fun state(device: SimDevice): List<Any?> =
        knownNotes().sortedBy { it.toString() }.map { id ->
            listOf(
                device.store.notes.get(id),
                RevisionGraph(device.store.sync.revisions(id)).liveHeads.map { it.id },
            )
        } + listOf(device.conflicts.open(), device.conflicts.hidden())

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
                resolver.details(conflict.mergeId)?.let { details ->
                    removed += linesOf(conflict.losingBody) - linesOf(details.shownBody)
                    resolver.resolve(conflict.mergeId, ConflictResolution.KeepShown)
                }
            }
            // A resolution above can have merged these away. KeepBoth loses no line.
            resolver.hidden().filter { head -> head in waiting }.forEach { head ->
                resolver.settleHidden(head.revisionId, ConflictResolution.KeepBoth)
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
            val revisions = device.store.sync.revisions(id)
            if (revisions.isNotEmpty()) {
                val graph = RevisionGraph(revisions)
                // Beside a missing ancestor a tombstone cannot merge, and it hides no text.
                val live =
                    graph.liveHeads.filter {
                        graph.isComplete || it.content is RevisionContent.Snapshot
                    }
                check(live.size == 1 || (live.isEmpty() && !graph.isComplete)) {
                    "Seed $seed: note $id has $live"
                }
                val shown = device.store.notes.get(id)
                check(shown == null || shown.revisionId == live.single().id) {
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
        const val ACTIONS = 24
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
