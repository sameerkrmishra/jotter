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
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.BackendRecord
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
import kotlin.time.Instant

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
            val segment = blobs.pathOfNumber(phone.deviceId, 1)
            assertTrue(segment.contains(Regex("/000000000001-[0-9a-f]{32}\\.json\\.gz$")), segment)
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
    fun aWriterDoesNotReadItsOwnFileBack(): TestResult =
        runTest {
            val phone = device("phone")
            phone.notes.create("t", "body")
            val blobs = InMemoryBlobStore()
            assertEquals(1, phone.sync(blobs).pushedSegments)
            assertEquals(0, phone.sync(blobs).appliedSegments)
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
            val read = pc.sync.readFiles("b")
            assertEquals(1, read.size)
            phone.notes.create("second", "note")
            phone.sync(blobs)
            val own = pc.sync.ownSegments("b")

            blobs.listFailure = BlobStoreFailureException("disk error")
            val result = pc.engine().sync(SyncTarget("b", blobs))
            assertIs<SyncResult.Interrupted>(result)
            assertEquals(read, pc.sync.readFiles("b"))
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
                        if (path.contains("/000000000002-") && reads++ == 0) throw outage
                        return memory.read(path)
                    }
                }
            assertIs<SyncResult.Interrupted>(pc.engine().sync(SyncTarget("b", flaky)))
            assertEquals(1, pc.sync.readFiles("b").size)
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
            assertEquals(mapOf(1L to true, 2L to false), phone.sync.published("b"))
            phone.notes.create("third", "note")
            val report = phone.sync(blobs)
            assertEquals(mapOf(1L to true, 2L to true, 3L to true), phone.sync.published("b"))
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
            assertEquals(mapOf(1L to false), phone.sync.published("b"))
            assertTrue(phone.sync(blobs).issues.isEmpty())
            assertEquals(mapOf(1L to true), phone.sync.published("b"))
        }

    /** `writeNew` failed, and left a damaged file: found there later, it cannot count. */
    @Test
    fun aDamagedFileAtAPendingPathIsNotPublishedAndTheNoteGoesOutAgain(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val memory = InMemoryBlobStore()
            var damaged: String? = null
            val damaging =
                object : BlobStore by memory {
                    override suspend fun writeNew(
                        path: String,
                        bytes: ByteArray,
                    ) {
                        if (damaged == null && path.endsWith(".json.gz")) {
                            damaged = path
                            memory.writeNew(path, bytes.copyOf(bytes.size / 2))
                            throw BlobStoreFailureException("fsync failed")
                        }
                        memory.writeNew(path, bytes)
                    }
                }
            val note = phone.notes.create("t", "body")
            assertIs<SyncResult.Interrupted>(phone.engine().sync(SyncTarget("b", damaging)))

            phone.sync(damaging)

            assertEquals(mapOf(2L to true), phone.sync.published("b"))
            assertTrue(phone.sync.unpushed("b").isEmpty())
            val issue = assertIs<SyncIssue.SegmentNotApplied>(pc.sync(memory).issues.single())
            assertEquals(SegmentProblem.CORRUPT, issue.problem)
            assertEquals(1, issue.number)
            assertEquals("body", pc.notes.get(note.id)?.body)
        }

    /** A sound segment, but not the one the name says: only the hash tells. */
    @Test
    fun anotherSoundSegmentAtAPendingPathIsNotPublished(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val memory = InMemoryBlobStore()
            val stranger = Segment(phone.deviceId, 1, listOf(textRevision(9, 10, "not ours")))
            val swapping =
                object : BlobStore by memory {
                    override suspend fun writeNew(
                        path: String,
                        bytes: ByteArray,
                    ) {
                        val isFree = memory.list(BackendLayout.DEVICES).none { it.path == path }
                        if (path.endsWith(".json.gz") && isFree) {
                            memory.writeNew(path, JsonGzipCodec().encode(stranger))
                            throw BlobAlreadyExistsException(path)
                        }
                        memory.writeNew(path, bytes)
                    }
                }
            val note = phone.notes.create("t", "body")
            assertEquals(0, phone.sync(swapping).pushedSegments)
            assertTrue(phone.sync.published("b").isEmpty())
            phone.sync(memory)
            pc.sync(memory)
            assertEquals("body", pc.notes.get(note.id)?.body)
        }

    /** The same number would give the same name again, for good. */
    @Test
    fun aDamagedFileTheListingDoesNotShowIsNotWrittenToAgain(): TestResult =
        runTest {
            val phone = device("phone")
            val memory = InMemoryBlobStore()
            val note = phone.notes.create("t", "body")
            val first = phone.sync.unpushed("b").map { checkNotNull(phone.sync.revision(it)) }
            val segment = Segment(phone.deviceId, 1, first)
            val path = pathOf(segment)
            val blind =
                object : BlobStore by memory {
                    override suspend fun list(prefix: String): List<BlobInfo> =
                        memory.list(prefix).filter { it.path != path }
                }
            // The first cycle records and fails, and the damaged file is there for the second.
            var failing = true
            val failingOnce =
                object : BlobStore by blind {
                    override suspend fun writeNew(
                        path: String,
                        bytes: ByteArray,
                    ) {
                        if (failing && path.endsWith(".json.gz")) {
                            failing = false
                            memory.writeNew(path, bytes.copyOf(bytes.size / 2))
                            throw BlobStoreFailureException("fsync failed")
                        }
                        memory.writeNew(path, bytes)
                    }
                }
            assertIs<SyncResult.Interrupted>(phone.engine().sync(SyncTarget("b", failingOnce)))
            assertEquals(1, phone.sync(failingOnce).pushedSegments)
            val pc = device("pc")
            pc.sync(memory)
            assertEquals("body", pc.notes.get(note.id)?.body)
        }

    /** A store can answer that a path exists when no file is there: a directory, for example. */
    @Test
    fun aPendingPathThatHoldsNoFileIsGivenUpToo(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val memory = InMemoryBlobStore()
            var isTaken = true
            val taken =
                object : BlobStore by memory {
                    override suspend fun writeNew(
                        path: String,
                        bytes: ByteArray,
                    ) {
                        if (isTaken && path.endsWith(".json.gz")) {
                            isTaken = false
                            throw BlobAlreadyExistsException(path)
                        }
                        memory.writeNew(path, bytes)
                    }
                }
            val note = phone.notes.create("t", "body")
            assertEquals(0, phone.sync(taken).pushedSegments)
            assertEquals(1, phone.sync(taken).pushedSegments)
            pc.sync(memory)
            assertEquals("body", pc.notes.get(note.id)?.body)
        }

    /** ADR-0014: a known limit. It stays in sight, and its writer can still recover it. */
    @Test
    fun aFileRottedAfterPublishingIsReportedOnEveryCycleAndBlocksNothing(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = LossyBlobStore()
            val rotted = phone.notes.create("rotted", "body")
            phone.sync(blobs)
            val next = phone.notes.create("next", "body")
            phone.sync(blobs)
            blobs.damage(blobs.pathOfNumber(phone.deviceId, 1))

            repeat(2) {
                val issue = assertIs<SyncIssue.SegmentNotApplied>(pc.sync(blobs).issues.single())
                assertEquals(SegmentProblem.CORRUPT, issue.problem)
                assertEquals(phone.deviceId, issue.device)
                assertEquals(1, issue.number)
            }
            assertNull(pc.notes.get(rotted.id))
            assertNotNull(pc.notes.get(next.id))

            phone.engine().forget("b")
            phone.sync(blobs)
            assertEquals(1, pc.sync(blobs).issues.size)
            assertEquals("body", pc.notes.get(rotted.id)?.body)
        }

    @Test
    fun forgettingABackendForgetsWhatWasReadThere(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            phone.notes.create("t", "body")
            phone.sync(blobs)
            pc.sync(blobs)
            pc.engine().forget("b")
            assertTrue(pc.sync.readFiles("b").isEmpty())
            assertEquals(1, pc.sync(blobs).appliedSegments)
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
            val path = blobs.pathOfNumber(phone.deviceId, 1)
            assertFalse(
                JsonGzipCodec().decode(blobs.read(path)).revisions.any {
                    it.noteId ==
                        later.id
                },
            )
            val second = blobs.pathOfNumber(phone.deviceId, 2)
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
            assertEquals(mapOf(1L to false), phone.sync.published("b"))
            assertTrue(phone.sync(blobs).issues.isEmpty())
            assertEquals(mapOf(1L to true), phone.sync.published("b"))
            assertEquals(1, segmentFiles(blobs).size)
        }

    @Test
    fun aCrashDuringApplyRollsBackRevisionsAndWhatWasRead(): TestResult =
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
            assertTrue(pc.sync.readFiles("b").isEmpty())
            assertTrue(pc.all().isEmpty())
            pc.sync(blobs)
            assertEquals(1, pc.all().size)
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
            val path = blobs.pathOfNumber(phone.deviceId, 1)
            blobs.lose(path)
            pc.sync(blobs)
            assertNull(pc.notes.get(first.id))
            assertNotNull(pc.notes.get(second.id))
            assertEquals(1, phone.sync(blobs).pushedSegments)
            assertEquals(path, blobs.pathOfNumber(phone.deviceId, 1))
            pc.sync(blobs)
            assertNotNull(pc.notes.get(first.id))
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
            blobs.lose(blobs.pathOfNumber(phone.deviceId, 1))
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
            blobs.lose(blobs.pathOfNumber(phone.deviceId, 2))
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
            blobs.writeNew(junkPath(stranger, 1), "junk".encodeToByteArray())
            blobs.writeSegment(Segment(stranger, 2, listOf(textRevision(5, 10, "good"))))
            val report = pc.sync(blobs)
            val issue = assertIs<SyncIssue.SegmentNotApplied>(report.issues.single())
            assertEquals(SegmentProblem.CORRUPT, issue.problem)
            assertEquals(3, pc.all().size)
            assertEquals(1, pc.sync.readFiles("b").count { it.first == stranger })
            assertEquals(1, pc.sync(blobs).issues.size)
        }

    @Test
    fun aFileWhoseContentIsNotItsHashIsRefusedAndHarmsNothing(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val stranger = DeviceId.generate()
            val forged = Segment(stranger, 1, listOf(textRevision(1, 10, "not what the name says")))
            blobs.writeNew(junkPath(stranger, 1), JsonGzipCodec().encode(forged))

            repeat(2) {
                val issue = assertIs<SyncIssue.SegmentNotApplied>(pc.sync(blobs).issues.single())
                assertEquals(SegmentProblem.CORRUPT, issue.problem)
                assertTrue(issue.detail.contains("hash"), issue.detail)
                assertTrue(pc.sync.revisions(NOTE).isEmpty())
                assertTrue(pc.sync.readFiles("b").isEmpty())
            }

            blobs.writeSegment(forged)
            assertEquals(1, pc.sync(blobs).issues.size)
            assertEquals("not what the name says", pc.notes.get(NOTE)?.body)
        }

    @Test
    fun twoSegmentsWithOneNumberAreBothApplied(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val stranger = DeviceId.generate()
            val one = textRevision(1, 10, "same length a").copy(noteId = NoteId.generate())
            val two = textRevision(2, 10, "same length b").copy(noteId = NoteId.generate())
            blobs.writeSegment(Segment(stranger, 1, listOf(one)))
            pc.sync(blobs)
            blobs.writeSegment(Segment(stranger, 1, listOf(two)))
            val report = pc.sync(blobs)
            assertEquals(1, report.appliedSegments)
            assertTrue(report.issues.isEmpty(), "${report.issues}")
            assertEquals(2, pc.sync.readFiles("b").size)
            assertEquals(one.text.body, pc.notes.get(one.noteId)?.body)
            assertEquals(two.text.body, pc.notes.get(two.noteId)?.body)
        }

    @Test
    fun aFutureSegmentIsAppliedOnlyByAVersionThatKnowsIt(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val stranger = DeviceId.generate()
            val bytes = gzip("{\"format\":99,\"device\":\"$stranger\"}")
            blobs.writeNew(junkPath(stranger, 1), bytes)
            val issue = assertIs<SyncIssue.SegmentNotApplied>(pc.sync(blobs).issues.single())
            assertEquals(SegmentProblem.FUTURE_FORMAT, issue.problem)
            assertTrue(pc.sync.readFiles("b").isEmpty())
        }

    @Test
    fun aSegmentInTheWrongFolderIsRefused(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val stranger = DeviceId.generate()
            val segment = Segment(DeviceId.generate(), 1, listOf(textRevision(1, 10, "moved")))
            val name = BackendLayout.segmentName(1, JsonGzipCodec().hash(segment))
            val path = BackendLayout.segmentPath(stranger, name)
            blobs.writeNew(path, JsonGzipCodec().encode(segment))
            val issue = assertIs<SyncIssue.SegmentNotApplied>(pc.sync(blobs).issues.single())
            assertEquals(SegmentProblem.CORRUPT, issue.problem)
            assertTrue(pc.all().isEmpty())
        }

    @Test
    fun aFileTooLargeIsNotRead(): TestResult =
        runTest {
            val pc = device("pc")
            val stranger = DeviceId.generate()
            val path = junkPath(stranger, 1)
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
    fun aFileThatCannotBeReadIsTriedAgain(): TestResult =
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
    fun otherFilesInTheFoldersAreIgnored(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val id = DeviceId.generate()
            val hash = "0".repeat(HASH_DIGITS)
            listOf(
                "jotter/devices/not-a-device/000000000001-$hash.json.gz",
                "jotter/devices/${id.toString().uppercase()}/000000000001-$hash.json.gz",
                "jotter/devices/$id/1-$hash.json.gz",
                "jotter/devices/$id/000000000000-$hash.json.gz",
                "jotter/devices/$id/00000000000x-$hash.json.gz",
                "jotter/devices/$id/000000000001.json.gz",
                "jotter/devices/$id/000000000001-.json.gz",
                "jotter/devices/$id/000000000001-${hash.drop(1)}.json.gz",
                "jotter/devices/$id/000000000001-${"A".repeat(HASH_DIGITS)}.json.gz",
                "jotter/devices/$id/000000000001-$hash.json",
                "jotter/devices/$id/sub/000000000001-$hash.json.gz",
            ).forEach { blobs.writeNew(it, ByteArray(1)) }
            assertTrue(pc.sync(blobs).issues.isEmpty())
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
    fun onlyBackendsMissingFromTheBackendTableAreForgottenAsUnlisted(): TestResult =
        runTest {
            val phone = device("phone")
            phone.notes.create("t", "b")
            val engine = phone.engine()
            phone.backends.add(
                BackendRecord("listed", "local-folder", "x", "{}", Instant.DISTANT_PAST),
            )
            engine.sync(SyncTarget("listed", InMemoryBlobStore()))
            engine.sync(SyncTarget("removed", InMemoryBlobStore()))
            assertEquals(listOf("removed"), engine.forgetUnlisted())
            assertTrue(phone.sync.ownSegments("removed").isEmpty())
            assertEquals(1, phone.sync.unpushed("removed").size)
            assertEquals(1, phone.sync.ownSegments("listed").size)
            assertEquals(emptyList(), engine.forgetUnlisted())
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
            // A limit of one byte refuses every file: nothing is applied, and each stays unread.
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
            blobs.writeSegment(Segment(stranger, 1, listOf(future)))
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
        const val HASH_DIGITS = 32
    }
}
