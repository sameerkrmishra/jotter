package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.NoteFilter
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.GzipSink
import okio.buffer
import okio.use
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [BlobStore] whose next calls fail in ways a test asks for. */
private class ScriptedStore(
    val inner: BlobStore = InMemoryBlobStore(),
) : BlobStore by inner {
    var listFailure: Exception? = null
    var writeFailureBefore: Exception? = null
    var writeFailureAfter: Exception? = null
    var pingAnswer: Reachability = Reachability.Reachable
    val written = mutableListOf<String>()

    override suspend fun list(prefix: String): List<BlobInfo> {
        listFailure?.let { throw it }
        return inner.list(prefix)
    }

    override suspend fun writeNew(
        path: String,
        bytes: ByteArray,
    ) {
        writeFailureBefore?.let {
            writeFailureBefore = null
            throw it
        }
        inner.writeNew(path, bytes)
        written += path
        writeFailureAfter?.let {
            writeFailureAfter = null
            throw it
        }
    }

    override suspend fun ping(): Reachability = pingAnswer
}

@Suppress("LargeClass") // One rule per test, and they share the fixtures above and below.
class SyncEngineTest {
    private val dir =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-sync-test-${Random.nextLong().toULong()}"
    private val stores = mutableListOf<NoteStore>()
    private val outage =
        BackendUnavailableException(Reachability.Unreachable(UnreachableReason.FAILED, "gone"))

    init {
        FileSystem.SYSTEM.createDirectories(dir)
    }

    @AfterTest
    fun cleanUp() {
        stores.forEach(NoteStore::close)
        FileSystem.SYSTEM.deleteRecursively(dir)
    }

    private fun device(name: String): NoteStore =
        NoteStore
            .open((dir / "$name.db").toString(), name, EmptyCoroutineContext)
            .also { stores += it }

    private fun NoteStore.engine(
        probe: SyncProbe = SyncProbe {},
        maxBytes: Long = MAX_SEGMENT_BYTES,
    ): SyncEngine =
        SyncEngine(
            this,
            DeviceInfo("test", "test"),
            EmptyCoroutineContext,
            kotlin.time.Clock.System,
            SyncSeams(probe = probe, maxSegmentBytes = maxBytes),
        )

    private suspend fun NoteStore.sync(blobs: BlobStore): SyncReport {
        val result = engine().sync(SyncTarget("b", blobs))
        return assertIs<SyncResult.Completed>(result, "$result").report
    }

    private suspend fun exchange(
        blobs: BlobStore,
        vararg devices: NoteStore,
    ) {
        repeat(ROUNDS) { devices.forEach { store -> store.sync(blobs) } }
    }

    private fun NoteStore.all(): List<Note> =
        NoteState.entries.flatMap { notes.list(NoteFilter(state = it)) }

    private fun gzip(text: String): ByteArray {
        val out = Buffer()
        GzipSink(out).buffer().use { it.writeUtf8(text) }
        return out.readByteArray()
    }

    private suspend fun segmentFiles(blobs: BlobStore): List<String> =
        blobs.list(BackendLayout.DEVICES).map { it.path }.filter { it.endsWith(".json.gz") }

    @Test
    fun aNewBackendGetsTheFormatTheDeviceFileAndTheHistory(): TestResult =
        runTest {
            val phone = device("phone")
            phone.notes.create("t", "body")
            val blobs = InMemoryBlobStore()
            val report = phone.sync(blobs)
            assertEquals(1, report.pushedSegments)
            assertEquals(
                "{\"format\":1,\"codec\":\"json+gzip\"}",
                blobs.read("jotter/format.json").decodeToString(),
            )
            val deviceFile = "jotter/devices/${phone.deviceId}/device.json"
            assertTrue(blobs.read(deviceFile).decodeToString().contains("\"platform\":\"test\""))
            val segment = "jotter/devices/${phone.deviceId}/000000000001.json.gz"
            assertEquals(1, JsonGzipCodec().decode(blobs.read(segment)).revisions.size)
        }

    @Test
    fun anEditTravelsToAnotherDevice(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val note = phone.notes.create("t", "body", setOf(TagName.parse("x")), pinned = true)
            val blobs = InMemoryBlobStore()
            exchange(blobs, phone, pc)
            assertEquals(note, pc.notes.get(note.id))
            assertEquals(
                pc.notes
                    .search("body")
                    .single()
                    .note,
                note,
            )
        }

    @Test
    fun aRepeatedSyncPushesAndAppliesNothing(): TestResult =
        runTest {
            val phone = device("phone")
            phone.notes.create("t", "body")
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            exchange(blobs, phone, pc)
            val again = pc.sync(blobs)
            assertEquals(0, again.pushedSegments + again.appliedSegments + again.merges)
        }

    @Test
    fun concurrentEditsMergeToTheSameRevisionOnBothDevices(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val note = phone.notes.create("", "a\nb\nc\n")
            exchange(blobs, phone, pc)
            phone.notes.edit(note.id, note.revisionId, "", "A\nb\nc\n")
            pc.notes.edit(note.id, note.revisionId, "", "a\nb\nC\n")
            phone.sync(blobs)
            pc.sync(blobs)
            phone.sync(blobs)
            val onPhone = assertNotNull(phone.notes.get(note.id))
            assertEquals("A\nb\nC\n", onPhone.body)
            assertEquals(onPhone, pc.notes.get(note.id))
            exchange(blobs, phone, pc)
            assertEquals(phone.sync.revisions(note.id).toSet(), pc.sync.revisions(note.id).toSet())
        }

    /** ADR-0013: devices out of step must not merge each other's merges for ever. */
    @Test
    fun devicesOutOfStepStopMakingMerges(): TestResult =
        runTest {
            val devices = List(MANY) { device("d$it") }
            val blobs = InMemoryBlobStore()
            val note = devices[0].notes.create("", "1\n2\n3\n4\n5\n6\n")
            exchange(blobs, *devices.toTypedArray())
            devices.forEachIndexed { index, store ->
                val lines = "1\n2\n3\n4\n5\n6\n".lines().toMutableList()
                lines[index] = "edit $index"
                store.notes.edit(note.id, note.revisionId, "", lines.joinToString("\n"))
            }
            exchange(blobs, *devices.toTypedArray())
            val quiet = devices.sumOf { it.sync(blobs).merges }
            assertEquals(0, quiet)
            val counts = devices.map { it.sync.revisions(note.id).size }.toSet()
            assertEquals(1, counts.size)
            assertEquals(1, devices.map { it.notes.get(note.id) }.toSet().size)
        }

    @Test
    fun aListFailureIsNotAnEmptyStore(): TestResult =
        runTest {
            val phone = device("phone")
            phone.notes.create("t", "body")
            val pc = device("pc")
            val blobs = ScriptedStore()
            exchange(blobs, phone, pc)
            val cursor = pc.sync.cursor("b", phone.deviceId)
            assertEquals(1, cursor)
            phone.notes.create("second", "note")
            phone.sync(blobs)
            val own = pc.sync.ownSegments("b")

            blobs.listFailure = BlobStoreFailureException("disk error")
            val result = pc.engine().sync(SyncTarget("b", blobs))
            assertIs<SyncResult.Interrupted>(result)
            assertEquals(cursor, pc.sync.cursor("b", phone.deviceId))
            assertEquals(own, pc.sync.ownSegments("b"))
            assertEquals(1, pc.all().size)

            blobs.listFailure = null
            pc.sync(blobs)
            assertEquals(2, pc.all().size)
        }

    @Test
    fun anUnreachableStoreIsSkippedAndNothingChanges(): TestResult =
        runTest {
            val phone = device("phone")
            phone.notes.create("t", "body")
            val blobs = ScriptedStore()
            val down =
                Reachability.Unreachable(
                    UnreachableReason.NOT_INITIALIZED,
                    "bare mount point",
                )
            blobs.pingAnswer = down
            assertEquals(SyncResult.Unreachable(down), phone.engine().sync(SyncTarget("b", blobs)))
            assertTrue(blobs.written.isEmpty())
            assertTrue(phone.sync.ownSegments("b").isEmpty())
        }

    @Test
    fun aBackendThatGoesAwayDuringAReadKeepsWhatCameBefore(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val memory = InMemoryBlobStore()
            repeat(2) {
                phone.notes.create("t$it", "b")
                phone.sync(memory)
            }
            var reads = 0
            val flaky =
                object : BlobStore by memory {
                    override suspend fun read(path: String): ByteArray {
                        if (path.endsWith("2.json.gz") && reads++ == 0) throw outage
                        return memory.read(path)
                    }
                }
            assertIs<SyncResult.Interrupted>(pc.engine().sync(SyncTarget("b", flaky)))
            assertEquals(1, pc.sync.cursor("b", phone.deviceId))
            assertEquals(1, pc.all().size)
            pc.sync(flaky)
            assertEquals(2, pc.all().size)
        }

    @Test
    fun aFailedWriteThatPublishedIsRecognisedAndNotRenumbered(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            phone.notes.create("t", "body")
            val blobs = ScriptedStore()
            phone.sync(blobs)
            phone.notes.create("second", "note")
            blobs.writeFailureAfter = BlobStoreFailureException("fsync failed")
            assertIs<SyncResult.Interrupted>(phone.engine().sync(SyncTarget("b", blobs)))
            assertEquals(mapOf(1L to true, 2L to false), phone.sync.ownSegments("b"))
            phone.notes.create("third", "note")
            val report = phone.sync(blobs)
            assertEquals(mapOf(1L to true, 2L to true, 3L to true), phone.sync.ownSegments("b"))
            assertTrue(report.issues.isEmpty())
            assertEquals(3, segmentFiles(blobs).size)
            pc.sync(blobs)
            assertEquals(3, pc.all().size)
        }

    @Test
    fun aRetryThatFailsAgainAfterPublishingIsStillSafe(): TestResult =
        runTest {
            val phone = device("phone")
            val blobs = ScriptedStore()
            phone.sync(blobs)
            phone.notes.create("t", "body")
            blobs.writeFailureAfter = BlobStoreFailureException("fsync failed")
            assertIs<SyncResult.Interrupted>(phone.engine().sync(SyncTarget("b", blobs)))
            blobs.writeFailureBefore = BlobStoreFailureException("failed again")
            assertIs<SyncResult.Interrupted>(phone.engine().sync(SyncTarget("b", blobs)))
            assertEquals(mapOf(1L to false), phone.sync.ownSegments("b"))
            assertTrue(phone.sync(blobs).issues.isEmpty())
            assertEquals(mapOf(1L to true), phone.sync.ownSegments("b"))
        }

    @Test
    fun aRetryOfAFreePathWritesTheRecordedSegmentNotANewOne(): TestResult =
        runTest {
            val phone = device("phone")
            val blobs = ScriptedStore()
            phone.sync(blobs)
            phone.notes.create("t", "body")
            blobs.writeFailureBefore = BlobStoreFailureException("disk full")
            assertIs<SyncResult.Interrupted>(phone.engine().sync(SyncTarget("b", blobs)))
            val later = phone.notes.create("later", "note")
            phone.sync(blobs)
            val path = BackendLayout.segmentPath(phone.deviceId, 1)
            assertFalse(
                JsonGzipCodec().decode(blobs.read(path)).revisions.any {
                    it.noteId ==
                        later.id
                },
            )
            val second = BackendLayout.segmentPath(phone.deviceId, 2)
            assertTrue(
                JsonGzipCodec().decode(blobs.read(second)).revisions.any {
                    it.noteId ==
                        later.id
                },
            )
        }

    @Test
    fun aCrashBetweenWritingAndRecordingLosesNothing(): TestResult =
        runTest {
            val phone = device("phone")
            phone.notes.create("t", "body")
            val blobs = InMemoryBlobStore()
            val crashing =
                phone.engine(
                    SyncProbe {
                        if (it ==
                            SyncPoint.SEGMENT_WRITTEN
                        ) {
                            throw SimulatedCrash()
                        }
                    },
                )
            assertFailsWith<SimulatedCrash> { crashing.sync(SyncTarget("b", blobs)) }
            assertEquals(mapOf(1L to false), phone.sync.ownSegments("b"))
            assertTrue(phone.sync(blobs).issues.isEmpty())
            assertEquals(mapOf(1L to true), phone.sync.ownSegments("b"))
            assertEquals(1, segmentFiles(blobs).size)
        }

    @Test
    fun aCrashDuringApplyRollsBackRevisionsAndCursors(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            phone.notes.create("t", "body")
            val blobs = InMemoryBlobStore()
            phone.sync(blobs)
            val crashing =
                pc.engine(
                    SyncProbe {
                        if (it ==
                            SyncPoint.REVISIONS_INSERTED
                        ) {
                            throw SimulatedCrash()
                        }
                    },
                )
            assertFailsWith<SimulatedCrash> { crashing.sync(SyncTarget("b", blobs)) }
            assertEquals(0, pc.sync.cursor("b", phone.deviceId))
            assertTrue(pc.all().isEmpty())
            pc.sync(blobs)
            assertEquals(1, pc.all().size)
        }

    @Test
    fun anotherFileAtOurPathIsReportedAndNothingIsOverwritten(): TestResult =
        runTest {
            val phone = device("phone")
            val blobs = InMemoryBlobStore()
            phone.notes.create("t", "body")
            val path = BackendLayout.segmentPath(phone.deviceId, 1)
            val foreign = textRevision(9, 10, "a copied database wrote this")
            val bytes = JsonGzipCodec().encode(Segment(phone.deviceId, 1, listOf(foreign)))
            blobs.writeNew(path, bytes)
            val report = phone.sync(blobs)
            assertEquals(listOf<SyncIssue>(SyncIssue.SegmentCollision(1)), report.issues)
            assertTrue(blobs.read(path).contentEquals(bytes))
            assertEquals(mapOf(1L to false), phone.sync.ownSegments("b"))
        }

    @Test
    fun aDamagedFileAtOurPathIsACollisionToo(): TestResult =
        runTest {
            val phone = device("phone")
            val blobs = InMemoryBlobStore()
            phone.notes.create("t", "body")
            blobs.writeNew(BackendLayout.segmentPath(phone.deviceId, 1), ByteArray(3))
            assertIs<SyncIssue.SegmentCollision>(phone.sync(blobs).issues.single())
        }

    @Test
    fun aLostSegmentIsWrittenAgainWithItsContent(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = LossyBlobStore()
            val first = phone.notes.create("first", "note")
            phone.sync(blobs)
            val second = phone.notes.create("second", "note")
            phone.sync(blobs)
            val path = BackendLayout.segmentPath(phone.deviceId, 1)
            blobs.lose(path)
            pc.sync(blobs)
            assertNull(pc.notes.get(first.id))
            assertNotNull(pc.notes.get(second.id))
            assertEquals(setOf(1L), pc.sync.gaps("b", phone.deviceId))
            assertEquals(1, phone.sync(blobs).pushedSegments)
            pc.sync(blobs)
            assertNotNull(pc.notes.get(first.id))
            assertTrue(pc.sync.gaps("b", phone.deviceId).isEmpty())
        }

    @Test
    fun aHoleInAFolderDelaysOnlyWhatIsMissing(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = LossyBlobStore()
            val note = phone.notes.create("t", "one")
            phone.sync(blobs)
            phone.notes.edit(note.id, note.revisionId, "t", "two")
            phone.sync(blobs)
            blobs.lose(BackendLayout.segmentPath(phone.deviceId, 1))
            val report = pc.sync(blobs)
            val missing = assertIs<SyncIssue.SegmentNotApplied>(report.issues.single())
            assertEquals(SegmentProblem.MISSING, missing.problem)
            assertEquals("two", pc.notes.get(note.id)?.body)
        }

    @Test
    fun aSegmentArrivingBeforeItsParentWaitsForMerging(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = LossyBlobStore()
            val note = phone.notes.create("", "a\nb\n")
            phone.sync(blobs)
            pc.sync(blobs)
            val edited = phone.notes.edit(note.id, note.revisionId, "", "A\nb\n")
            phone.sync(blobs)
            phone.notes.edit(note.id, edited.revisionId, "", "A\nb\nmore\n")
            phone.sync(blobs)
            pc.notes.edit(note.id, note.revisionId, "", "a\nB\n")
            blobs.lose(BackendLayout.segmentPath(phone.deviceId, 2))
            assertEquals(0, pc.sync(blobs).merges)
            phone.sync(blobs)
            assertEquals(1, pc.sync(blobs).merges)
            assertEquals("A\nB\nmore\n", pc.notes.get(note.id)?.body)
        }

    @Test
    fun aCorruptSegmentBlocksNeitherItsFolderNorOthers(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val laptop = device("laptop")
            val blobs = InMemoryBlobStore()
            phone.notes.create("from phone", "b")
            laptop.notes.create("from laptop", "b")
            phone.sync(blobs)
            laptop.sync(blobs)
            val stranger = DeviceId.generate()
            blobs.writeNew(BackendLayout.segmentPath(stranger, 1), "junk".encodeToByteArray())
            val good = Segment(stranger, 2, listOf(textRevision(5, 10, "good")))
            blobs.writeNew(BackendLayout.segmentPath(stranger, 2), JsonGzipCodec().encode(good))
            val report = pc.sync(blobs)
            val issue = assertIs<SyncIssue.SegmentNotApplied>(report.issues.single())
            assertEquals(SegmentProblem.CORRUPT, issue.problem)
            assertEquals(3, pc.all().size)
            assertEquals(setOf(1L), pc.sync.gaps("b", stranger))
            assertEquals(2, pc.sync.cursor("b", stranger))
            assertEquals(1, pc.sync(blobs).issues.size)
        }

    @Test
    fun aFutureSegmentIsAppliedOnlyByAVersionThatKnowsIt(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val stranger = DeviceId.generate()
            val bytes = gzip("{\"format\":99,\"device\":\"$stranger\"}")
            blobs.writeNew(BackendLayout.segmentPath(stranger, 1), bytes)
            val issue = assertIs<SyncIssue.SegmentNotApplied>(pc.sync(blobs).issues.single())
            assertEquals(SegmentProblem.FUTURE_FORMAT, issue.problem)
            assertEquals(setOf(1L), pc.sync.gaps("b", stranger))
        }

    @Test
    fun aSegmentInTheWrongFolderIsRefused(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val stranger = DeviceId.generate()
            val segment = Segment(DeviceId.generate(), 1, listOf(textRevision(1, 10, "moved")))
            blobs.writeNew(BackendLayout.segmentPath(stranger, 1), JsonGzipCodec().encode(segment))
            val issue = assertIs<SyncIssue.SegmentNotApplied>(pc.sync(blobs).issues.single())
            assertEquals(SegmentProblem.CORRUPT, issue.problem)
            assertTrue(pc.all().isEmpty())
        }

    @Test
    fun aFileTooLargeIsNotRead(): TestResult =
        runTest {
            val pc = device("pc")
            val stranger = DeviceId.generate()
            val path = BackendLayout.segmentPath(stranger, 1)
            val memory = InMemoryBlobStore()
            val huge =
                object : BlobStore by memory {
                    override suspend fun list(prefix: String): List<BlobInfo> =
                        listOf(BlobInfo(path, MAX_SEGMENT_BYTES + 1))

                    override suspend fun read(path: String): ByteArray =
                        if (path == BackendLayout.FORMAT_PATH) memory.read(path) else error("read")
                }
            assertIs<SyncIssue.SegmentNotApplied>(pc.sync(huge).issues.single())
        }

    @Test
    fun aFileThatCannotBeReadBecomesAGap(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val memory = InMemoryBlobStore()
            phone.notes.create("t", "b")
            phone.sync(memory)
            var failing = true
            val flaky =
                object : BlobStore by memory {
                    override suspend fun read(path: String): ByteArray {
                        if (failing && path.endsWith(".gz")) {
                            throw BlobStoreFailureException("bad sector")
                        }
                        return memory.read(path)
                    }
                }
            val issue = assertIs<SyncIssue.SegmentNotApplied>(pc.sync(flaky).issues.single())
            assertEquals(SegmentProblem.UNREADABLE, issue.problem)
            failing = false
            assertTrue(pc.sync(flaky).issues.isEmpty())
            assertEquals(1, pc.all().size)
        }

    @Test
    fun anImplausibleNumberStopsOnlyItsFolder(): TestResult =
        runTest {
            val pc = device("pc")
            val phone = device("phone")
            phone.notes.create("t", "b")
            val blobs = InMemoryBlobStore()
            phone.sync(blobs)
            val stranger = DeviceId.generate()
            blobs.writeNew(BackendLayout.segmentPath(stranger, 999_999_999_999), ByteArray(1))
            val report = pc.sync(blobs)
            assertEquals(
                listOf<SyncIssue>(SyncIssue.ImplausibleSegmentNumber(stranger, 999_999_999_999)),
                report.issues,
            )
            assertEquals(1, pc.all().size)
            assertEquals(0, pc.sync.cursor("b", stranger))
        }

    @Test
    fun otherFilesInTheFoldersAreIgnored(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val id = DeviceId.generate()
            listOf(
                "jotter/devices/not-a-device/000000000001.json.gz",
                "jotter/devices/${id.toString().uppercase()}/000000000001.json.gz",
                "jotter/devices/$id/1.json.gz",
                "jotter/devices/$id/000000000000.json.gz",
                "jotter/devices/$id/00000000000x.json.gz",
                "jotter/devices/$id/000000000001.json",
                "jotter/devices/$id/sub/000000000001.json.gz",
            ).forEach { blobs.writeNew(it, ByteArray(1)) }
            assertTrue(pc.sync(blobs).issues.isEmpty())
        }

    private suspend fun rejected(revisions: List<Revision>): SegmentProblem {
        val pc = device("pc")
        val blobs = InMemoryBlobStore()
        revisions.forEachIndexed { index, revision ->
            val stranger = DeviceId.generate()
            val segment = Segment(stranger, 1, listOf(revision))
            val path = BackendLayout.segmentPath(stranger, 1)
            blobs.writeNew(path, JsonGzipCodec().encode(segment))
            val report = pc.sync(blobs)
            if (index == revisions.lastIndex) {
                val issue = assertIs<SyncIssue.SegmentNotApplied>(report.issues.last())
                return issue.problem
            }
        }
        error("No revisions")
    }

    @Test
    fun theSameIdWithOtherContentIsRejected(): TestResult =
        runTest {
            val one = textRevision(1, 10, "one")
            assertEquals(
                SegmentProblem.REJECTED,
                rejected(listOf(one, one.copy(deviceId = DEVICE_B))),
            )
        }

    @Test
    fun twoRevisionsOfOneIdInASegmentAreRejected(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val one = textRevision(1, 10, "one")
            val stranger = DeviceId.generate()
            val segment = Segment(stranger, 1, listOf(one, one.copy(deviceId = DEVICE_B)))
            blobs.writeNew(BackendLayout.segmentPath(stranger, 1), JsonGzipCodec().encode(segment))
            assertIs<SyncIssue.SegmentNotApplied>(pc.sync(blobs).issues.single())
            assertTrue(pc.all().isEmpty())
        }

    @Test
    fun aChildNotAboveItsParentIsRejectedInEitherOrderOfArrival(): TestResult =
        runTest {
            val parent = textRevision(1, 50, "parent")
            val child = textRevision(2, 40, "child", parent)
            assertEquals(SegmentProblem.REJECTED, rejected(listOf(parent, child)))
            assertEquals(SegmentProblem.REJECTED, rejected(listOf(child, parent)))
        }

    @Test
    fun aParentInAnotherNoteIsRejected(): TestResult =
        runTest {
            val parent = textRevision(1, 10, "parent")
            val child =
                textRevision(2, 20, "child", parent)
                    .copy(
                        noteId =
                            io.github.sameerkrmishra.jotter.core.model.NoteId
                                .generate(),
                    )
            assertEquals(SegmentProblem.REJECTED, rejected(listOf(parent, child)))
        }

    @Test
    fun aCycleCannotEnter(): TestResult =
        runTest {
            val a = textRevision(1, 10, "a")
            val b = textRevision(2, 20, "b", a)
            val aOnB = a.copy(parents = setOf(b.id), timestamp = a.timestamp)
            assertEquals(SegmentProblem.REJECTED, rejected(listOf(b, aOnB)))
        }

    @Test
    fun aMergeThatDoesNotFollowFromItsParentsIsRejected(): TestResult =
        runTest {
            val root = textRevision(1, 10, "a\nb\n")
            val one = textRevision(2, 20, "A\nb\n", root)
            val two = textRevision(3, 30, "a\nB\n", root, device = DEVICE_B)
            val merge = mergeRevisions(RevisionGraph(listOf(root, one, two)), one, two)
            val wrong = merge.copy(deviceId = DEVICE_A)
            val forged = wrong.copy(id = mergeRevisionId(wrong))
            assertEquals(SegmentProblem.REJECTED, rejected(listOf(root, one, two, forged)))
        }

    @Test
    fun aRejectedSegmentLeavesNoTrace(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val parent = textRevision(1, 50, "parent")
            val good = textRevision(3, 60, "good", parent)
            val bad = textRevision(2, 40, "bad", parent)
            val stranger = DeviceId.generate()
            val first = Segment(stranger, 1, listOf(parent))
            blobs.writeNew(BackendLayout.segmentPath(stranger, 1), JsonGzipCodec().encode(first))
            pc.sync(blobs)
            val second = Segment(stranger, 2, listOf(good, bad))
            blobs.writeNew(BackendLayout.segmentPath(stranger, 2), JsonGzipCodec().encode(second))
            assertEquals(1, pc.sync(blobs).issues.size)
            assertNull(pc.sync.revision(good.id))
        }

    @Test
    fun aBackendInAFutureFormatIsLeftAlone(): TestResult =
        runTest {
            val phone = device("phone")
            phone.notes.create("t", "b")
            val blobs = ScriptedStore()
            blobs.inner.writeNew(
                BackendLayout.FORMAT_PATH,
                "{\"format\":2,\"codec\":\"x\"}".encodeToByteArray(),
            )
            assertIs<SyncResult.Incompatible>(phone.engine().sync(SyncTarget("b", blobs)))
            assertTrue(blobs.written.isEmpty())
        }

    @Test
    fun anUnknownCodecAndADamagedFormatFileAreLeftAlone(): TestResult =
        runTest {
            val phone = device("phone")
            val codec = InMemoryBlobStore()
            codec.writeNew(
                BackendLayout.FORMAT_PATH,
                "{\"format\":1,\"codec\":\"xchacha\"}".encodeToByteArray(),
            )
            assertIs<SyncResult.Incompatible>(phone.engine().sync(SyncTarget("b", codec)))
            val damaged = InMemoryBlobStore()
            damaged.writeNew(BackendLayout.FORMAT_PATH, "{".encodeToByteArray())
            assertIs<SyncResult.Incompatible>(phone.engine().sync(SyncTarget("b", damaged)))
        }

    @Test
    fun theLoserOfTheRaceToCreateTheFormatFileReadsTheWinners(): TestResult =
        runTest {
            val phone = device("phone")
            val memory = InMemoryBlobStore()
            var first = true
            val racing =
                object : BlobStore by memory {
                    override suspend fun read(path: String): ByteArray {
                        if (first) {
                            first = false
                            val winner = "{\"format\":1,\"codec\":\"json+gzip\"}"
                            val bytes = memory.runCatching { read(path) }
                            check(bytes.isFailure)
                            throw io.github.sameerkrmishra.jotter.backend.api
                                .BlobNotFoundException(path)
                                .also { memory.writeNew(path, winner.encodeToByteArray()) }
                        }
                        return memory.read(path)
                    }
                }
            assertIs<SyncResult.Completed>(phone.engine().sync(SyncTarget("b", racing)))
        }

    @Test
    fun aDeviceFileThatAppearedMeanwhileIsFine(): TestResult =
        runTest {
            val phone = device("phone")
            val memory = InMemoryBlobStore()
            val hiding =
                object : BlobStore by memory {
                    override suspend fun list(prefix: String): List<BlobInfo> = emptyList()
                }
            memory.writeNew(BackendLayout.deviceFile(phone.deviceId), ByteArray(1))
            assertIs<SyncResult.Completed>(phone.engine().sync(SyncTarget("b", hiding)))
        }

    @Test
    fun aRelayCarriesRevisionsBetweenBackends(): TestResult =
        runTest {
            val phone = device("phone")
            val relay = device("relay")
            val pc = device("pc")
            val left = InMemoryBlobStore()
            val right = InMemoryBlobStore()
            val note = phone.notes.create("t", "b")
            phone.sync(left)
            relay.engine().sync(SyncTarget("left", left))
            relay.engine().sync(SyncTarget("right", right))
            pc.sync(right)
            assertEquals(note, pc.notes.get(note.id))
            assertTrue(relay.sync.unpushed("right").isEmpty())
            assertTrue(relay.sync.unpushed("left").isEmpty())
        }

    @Test
    fun forgettingABackendSeedsItAgain(): TestResult =
        runTest {
            val phone = device("phone")
            phone.notes.create("t", "b")
            val blobs = InMemoryBlobStore()
            val engine = phone.engine()
            engine.sync(SyncTarget("b", blobs))
            engine.forget("b")
            assertTrue(phone.sync.ownSegments("b").isEmpty())
            assertEquals(1, phone.sync.unpushed("b").size)
            val fresh = InMemoryBlobStore()
            assertIs<SyncResult.Completed>(engine.sync(SyncTarget("b", fresh)))
            assertEquals(1, segmentFiles(fresh).size)
        }

    @Test
    fun aRevisionTooLargeForAnySegmentIsReportedAndTheRestGoes(): TestResult =
        runTest {
            val phone = device("phone")
            val blobs = InMemoryBlobStore()
            val big = phone.notes.create("t", "x".repeat(LARGE))
            phone.notes.create("small", "note")
            val result = phone.engine(maxBytes = LIMIT).sync(SyncTarget("b", blobs))
            val report = assertIs<SyncResult.Completed>(result).report
            val id =
                phone.sync
                    .revisions(big.id)
                    .single()
                    .id
            assertEquals(listOf<SyncIssue>(SyncIssue.RevisionTooLarge(id)), report.issues)
            assertEquals(1, report.pushedSegments)
        }

    @Test
    fun manyRevisionsSplitIntoSegmentsWithinTheLimit(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            repeat(SPLIT_NOTES) { phone.notes.create("t", "y".repeat(LINE)) }
            val result = phone.engine(maxBytes = LIMIT).sync(SyncTarget("b", blobs))
            assertTrue(assertIs<SyncResult.Completed>(result).report.pushedSegments > 1)
            blobs.list(BackendLayout.DEVICES).forEach { assertTrue(it.size <= LIMIT) }
            pc.sync(blobs)
            assertEquals(SPLIT_NOTES, pc.all().size)
        }

    @Test
    fun aBatchIsAppliedWhenItFillsUp(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            repeat(SPLIT_NOTES) {
                phone.notes.create("t", "z")
                phone.sync(blobs)
            }
            val result = pc.engine(maxBytes = 1).sync(SyncTarget("b", blobs))
            // A limit of one byte refuses every file unread: nothing is applied, but all is a gap.
            val report = assertIs<SyncResult.Completed>(result).report
            assertEquals(SPLIT_NOTES, report.issues.size)
            assertEquals(0, report.appliedSegments)
        }

    @Test
    fun aFarFutureTimestampIsReportedAndTheRevisionKept(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val stranger = DeviceId.generate()
            val future = textRevision(1, Long.MAX_VALUE / 2, "from the future")
            val segment = Segment(stranger, 1, listOf(future))
            blobs.writeNew(BackendLayout.segmentPath(stranger, 1), JsonGzipCodec().encode(segment))
            assertIs<SyncIssue.ClockDrift>(pc.sync(blobs).issues.single())
            assertEquals("from the future", pc.notes.get(future.noteId)?.body)
        }

    @Test
    fun aRemoteEditMovesTheClockForward(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val note = phone.notes.create("", "x")
            exchange(blobs, phone, pc)
            val edit = pc.notes.edit(note.id, note.revisionId, "", "y")
            assertTrue(edit.updatedAt > note.updatedAt)
        }

    @Test
    fun aDeletionTravelsAndAConcurrentEditBringsTheNoteBackInTheTrash(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val note = phone.notes.create("", "text")
            exchange(blobs, phone, pc)
            phone.notes.setState(note.id, NoteState.TRASHED)
            phone.notes.deleteForever(note.id)
            pc.notes.edit(note.id, note.revisionId, "", "still needed")
            exchange(blobs, phone, pc)
            assertEquals(NoteState.TRASHED, phone.notes.get(note.id)?.state)
            assertEquals("still needed", pc.notes.get(note.id)?.body)
        }

    @Test
    fun aDeletionAloneRemovesTheNoteEverywhere(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val note = phone.notes.create("", "text")
            exchange(blobs, phone, pc)
            phone.notes.setState(note.id, NoteState.TRASHED)
            phone.notes.deleteForever(note.id)
            exchange(blobs, phone, pc)
            assertNull(pc.notes.get(note.id))
            val last = pc.sync.revisions(note.id).last()
            assertEquals(RevisionContent.Tombstone, last.content)
        }

    @Test
    fun theSameFileWrittenTwiceCountsAsExisting(): TestResult =
        runTest {
            val blobs = InMemoryBlobStore()
            blobs.writeNew("a/b", ByteArray(1))
            assertFailsWith<BlobAlreadyExistsException> { blobs.writeNew("a/b", ByteArray(1)) }
        }

    private companion object {
        const val ROUNDS = 3
        const val MANY = 6
        const val LIMIT = 64L * 1024
        const val LARGE = 40_000
        const val LINE = 2_000
        const val SPLIT_NOTES = 12
    }
}
