package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.storage.HiddenReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidReason
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A revision counts by itself and its ancestors alone, and a device can lose its past. */
class ValidityTest {
    private val dir =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-valid-test-${Random.nextLong().toULong()}"
    private val stores = mutableListOf<NoteStore>()

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

    private suspend fun NoteStore.report(
        blobs: BlobStore,
        target: String = "b",
    ): SyncReport {
        val engine = SyncEngine(this, DeviceInfo("d", "test"), EmptyCoroutineContext)
        val result = engine.sync(SyncTarget(target, blobs))
        return assertIs<SyncResult.Completed>(result, "$result").report
    }

    private suspend fun plant(
        blobs: BlobStore,
        revisions: List<Revision>,
        folder: DeviceId = DeviceId.generate(),
        number: Long = 1,
    ) {
        val file = JsonGzipCodec().encode(Segment(folder, number, revisions))
        blobs.writeNew(BackendLayout.segmentPath(folder, number), file)
    }

    /** What a device decides from: all its revisions of [noteId], and what it lists. */
    private fun NoteStore.view(noteId: NoteId): List<Any?> {
        val revisions = sync.revisions(noteId)
        val graph = RevisionGraph(revisions)
        return listOf(
            revisions.toSet(),
            graph.liveHeads,
            notes.get(noteId),
            sync.invalidRevisions(),
            sync.hiddenHeads(),
        )
    }

    private val root = textRevision(1, 10, "a\nb\n")
    private val one = textRevision(2, 20, "A\nb\n", root)
    private val two = textRevision(3, 30, "a\nB\n", root, device = DEVICE_B)

    /** Ends up in every order of arrival at one view, with [segments] each in a file of its own. */
    private suspend fun assertSameInEveryOrder(
        noteId: NoteId,
        segments: List<List<Revision>>,
    ) {
        val views =
            listOf(segments, segments.reversed()).mapIndexed { index, order ->
                val store = device("order$index")
                val blobs = InMemoryBlobStore()
                order.forEach { revisions ->
                    plant(blobs, revisions)
                    store.report(blobs)
                }
                store.report(blobs)
                store.view(noteId)
            }
        assertEquals(views[0], views[1])
    }

    @Test
    fun aBadStampIsJudgedAloneInEveryOrderAndTakesNothingElse(): TestResult =
        runTest {
            val bad = textRevision(4, 5, "broken clock", root)
            val other = textRevision(5, 10, "unrelated").copy(noteId = NoteId.generate())
            assertSameInEveryOrder(root.noteId, listOf(listOf(root, one), listOf(bad, other)))
            val store = device("check")
            val blobs = InMemoryBlobStore()
            plant(blobs, listOf(bad, other, root))
            store.report(blobs)
            assertEquals("unrelated", store.notes.get(other.noteId)?.body)
            assertEquals("a\nb\n", store.notes.get(root.noteId)?.body)
        }

    @Test
    fun whatBuildsOnABadStampDoesNotCountAndItsTextIsListed(): TestResult =
        runTest {
            val bad = textRevision(4, 5, "bad", root)
            val onTop = textRevision(5, 90, "my own words", bad)
            val graph = RevisionGraph(listOf(root, bad, onTop))
            assertEquals(setOf(bad, onTop), graph.invalid.toSet())
            assertEquals(listOf(onTop), graph.invalidEnds)
            val store = device("flagged")
            val blobs = InMemoryBlobStore()
            plant(blobs, listOf(root, bad, onTop))
            store.report(blobs)
            val row = ConflictResolver(store).invalid().single()
            assertEquals(InvalidReason.BAD_HISTORY, row.reason)
            assertEquals("my own words", row.body)
            assertEquals(row, ConflictResolver(store).observeInvalid().first().single())
        }

    @Test
    fun aBadChildThatCameFirstIsShownUntilItsParentSaysOtherwise(): TestResult =
        runTest {
            val store = device("late")
            val blobs = InMemoryBlobStore()
            val bad = textRevision(4, 5, "bad", root)
            plant(blobs, listOf(bad))
            store.report(blobs)
            assertEquals("bad", store.notes.get(root.noteId)?.body)
            plant(blobs, listOf(root))
            store.report(blobs)
            assertEquals("a\nb\n", store.notes.get(root.noteId)?.body)
            assertEquals(1, store.sync.invalidRevisions().size)
        }

    @Test
    fun aParentInAnotherNoteMakesARevisionInvalidOnceItIsKnown(): TestResult =
        runTest {
            val parent = textRevision(1, 10, "parent")
            val child =
                textRevision(2, 20, "child", parent).copy(noteId = NoteId.generate())
            assertSameInEveryOrder(child.noteId, listOf(listOf(parent), listOf(child)))
            val store = device("foreign")
            val blobs = InMemoryBlobStore()
            plant(blobs, listOf(child))
            store.report(blobs)
            plant(blobs, listOf(parent))
            store.report(blobs)
            assertNull(store.notes.get(child.noteId))
            assertEquals(
                "child",
                store.sync
                    .invalidRevisions()
                    .single()
                    .body,
            )
        }

    @Test
    fun aCycleDoesNotCount() {
        val a = textRevision(1, 10, "a")
        val b = textRevision(2, 20, "b", a)
        val aOnB = a.copy(parents = setOf(b.id))
        assertTrue(RevisionGraph(listOf(aOnB, b)).heads.isEmpty())
        assertEquals(2, RevisionGraph(listOf(aOnB, b)).invalid.size)
    }

    @Test
    fun aForgedMergeDoesNotCountWhicheverArrivesFirst(): TestResult =
        runTest {
            val honest = mergeRevisions(RevisionGraph(listOf(root, one, two)), one, two)
            val wrong = honest.copy(deviceId = DEVICE_A)
            val forged = wrong.copy(id = mergeRevisionId(wrong))
            assertNull(RevisionGraph(listOf(root, one, two, forged))[forged.id])
            assertSameInEveryOrder(root.noteId, listOf(listOf(root, one, two), listOf(forged)))
        }

    @Test
    fun aMergeNamingTheWrongLoserDoesNotCount() {
        val conflictOne = textRevision(2, 20, "one", root)
        val conflictTwo = textRevision(3, 30, "two", root)
        val all = listOf(root, conflictOne, conflictTwo)
        val honest = mergeRevisions(RevisionGraph(all), conflictOne, conflictTwo)
        assertEquals(RevisionKind.AutomaticMerge(conflictOne.id), honest.kind)
        val wrong = honest.copy(kind = RevisionKind.AutomaticMerge(conflictTwo.id))
        val forged = wrong.copy(id = mergeRevisionId(wrong))
        assertNotNull(RevisionGraph(all + honest)[honest.id])
        assertNull(RevisionGraph(all + forged)[forged.id])
    }

    @Test
    fun aMergeWithoutBothParentsIsAsIfItHadNotArrived(): TestResult =
        runTest {
            val merge = mergeRevisions(RevisionGraph(listOf(root, one, two)), one, two)
            val edit = textRevision(9, 40, "on the merge", merge)
            val graph = RevisionGraph(listOf(root, one, merge, edit))
            assertNull(graph[merge.id])
            assertNotNull(graph[edit.id])
            assertTrue(RevisionGraph(listOf(root, one, two, merge, edit))[merge.id] != null)
        }

    private fun stateOf(
        revision: Revision,
        state: String,
    ): String {
        val writer = "\"device\":\"${revision.deviceId}\",\"revision\":\"${revision.id}\""
        val stamp = "\"counter\":0,$writer"
        return "\"state\":\"$state\",\"stateStamp\":{\"physicalMillis\":10,$stamp}"
    }

    @Test
    fun aMalformedRevisionGoesAloneAndItsTextIsListed(): TestResult =
        runTest {
            val store = device("malformed")
            val blobs = InMemoryBlobStore()
            val good = textRevision(5, 10, "good").copy(noteId = NoteId.generate())
            val bad = textRevision(6, 10, "its words", root)
            val folder = DeviceId.generate()
            val json =
                segmentJson(Segment(folder, 1, listOf(good, bad)))
                    .replace(stateOf(bad, "ACTIVE"), stateOf(bad, "LOST"))
            val out = Buffer()
            GzipSink(out).buffer().use { it.writeUtf8(json) }
            blobs.writeNew(BackendLayout.segmentPath(folder, 1), out.readByteArray())
            assertTrue(store.report(blobs).issues.isEmpty())
            assertEquals("good", store.notes.get(good.noteId)?.body)
            val row = store.sync.invalidRevisions().single()
            assertEquals(InvalidReason.MALFORMED, row.reason)
            assertEquals("its words", row.body)
        }

    @Test
    fun twoRevisionsOfOneIdEndTheSameInEveryOrder(): TestResult =
        runTest {
            val first = textRevision(1, 10, "first copy")
            val second = textRevision(1, 10, "second copy")
            assertSameInEveryOrder(first.noteId, listOf(listOf(first), listOf(second)))
            val store = device("collision")
            val blobs = InMemoryBlobStore()
            plant(blobs, listOf(first, second))
            store.report(blobs)
            val kept = store.notes.get(first.noteId)?.body
            val listed = store.sync.invalidRevisions().single()
            assertEquals(InvalidReason.ID_COLLISION, listed.reason)
            assertEquals(setOf("first copy", "second copy"), setOf(kept, listed.body))
        }

    @Test
    fun theWinningCopyOfAnIdIsPassedOn(): TestResult =
        runTest {
            val relay = device("relay")
            val left = InMemoryBlobStore()
            val right = InMemoryBlobStore()
            val copies = listOf(textRevision(1, 10, "a copy"), textRevision(1, 10, "b copy"))
            val (winner, loser) = copies.sortedBy(::revisionJson)
            plant(left, listOf(loser))
            relay.report(left, "left")
            relay.report(right, "right")
            plant(left, listOf(winner))
            relay.report(left, "left")
            relay.report(right, "right")
            val pc = device("pc")
            pc.report(right)
            assertEquals(winner.text.body, pc.notes.get(winner.noteId)?.body)
        }

    @Test
    fun aMergeBaseStopsAtATombstone() {
        val base = textRevision(1, 10, "r")
        val withLine = textRevision(2, 20, "r\nw", base)
        val deleted = userRevision(3, 30, withLine) { RevisionContent.Tombstone }
        val edit = textRevision(4, 40, "r\nw\ne", withLine)
        val other = textRevision(5, 50, "r\nz", base)
        val all = listOf(base, withLine, deleted, edit, other)
        val back = mergeRevisions(RevisionGraph(all), deleted, other)
        val graph = RevisionGraph(all + back)
        assertEquals(base, graph.mergeBase(back.id, edit.id))
        val merged = mergeRevisions(graph, back, edit)
        assertTrue("w" in merged.text.body.lines() || merged.isConflictedMerge(), merged.text.body)
    }

    @Test
    fun aTagAddedBehindATombstoneIsNotAnAddition() {
        val base = textRevision(1, 10, "text", tags = setOf("t"))
        val removed = textRevision(2, 20, "text", base, tags = emptySet())
        val addedBack = textRevision(3, 30, "text", removed, tags = setOf("t"))
        val deleted = userRevision(4, 40, addedBack) { RevisionContent.Tombstone }
        val other = textRevision(5, 50, "other", base, tags = setOf("t"))
        val stillRemoved = textRevision(6, 60, "text", removed, tags = emptySet())
        val all = listOf(base, removed, addedBack, deleted, other, stillRemoved)
        val back = mergeRevisions(RevisionGraph(all), deleted, other)
        val graph = RevisionGraph(all + back)
        assertTrue(graph.textAncestors(deleted.id).isEmpty())
        assertEquals(emptySet(), mergeRevisions(graph, back, stillRemoved).text.tags)
    }

    @Test
    fun aHeadBesideAConflictCannotBeSettled(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val third = device("third")
            val blobs = InMemoryBlobStore()
            val note = phone.notes.create("", "line\nother\n")
            listOf(phone, pc, third).forEach { store -> store.report(blobs) }
            phone.notes.edit(note.id, note.revisionId, "", "phone\nother\n")
            pc.notes.edit(note.id, note.revisionId, "", "pc\nother\n")
            repeat(2) { listOf(phone, pc).forEach { store -> store.report(blobs) } }
            third.notes.edit(note.id, note.revisionId, "", "line\nthird\n")
            third.report(blobs)
            phone.report(blobs)
            val resolver = ConflictResolver(phone)
            val hidden = resolver.hidden().single()
            assertEquals(HiddenReason.OPEN_CONFLICT, hidden.reason)
            assertFailsWith<HiddenHeadWaitsForConflictException> {
                resolver.settleHidden(hidden.revisionId, ConflictResolution.KeepShown)
            }
            assertEquals(1, resolver.open().size)
        }

    @Test
    fun aMergeNeedsOnlyWhatIsAboveTheCommonAncestor() {
        val lost = textRevision(1, 10, "lost")
        val kept = textRevision(2, 20, "a\nb\n", lost)
        val left = textRevision(3, 30, "A\nb\n", kept)
        val right = textRevision(4, 40, "a\nB\n", kept)
        val graph = RevisionGraph(listOf(kept, left, right))
        assertFalse(graph.isComplete)
        assertTrue(graph.canMerge(left, right))
        val above = textRevision(5, 50, "x", textRevision(9, 15, "gone", kept))
        assertFalse(RevisionGraph(listOf(kept, left, above)).canMerge(left, above))
        assertEquals(1, foldHeads(listOf(kept, left, right)).size)
    }

    @Test
    fun concurrentEditsAfterSettlingMerge(): TestResult =
        runTest {
            val writer = device("writer")
            val reader = device("reader")
            val other = device("other")
            val blobs = LossyBlobStore()
            val note = writer.notes.create("", "a\nb\n")
            listOf(writer, reader, other).forEach { store -> store.report(blobs) }
            val first = writer.notes.edit(note.id, note.revisionId, "", "writer 1\nb\n")
            writer.report(blobs)
            writer.notes.edit(note.id, first.revisionId, "", "writer 2\nb\n")
            writer.report(blobs)
            blobs.lose(BackendLayout.segmentPath(writer.deviceId, 2))
            reader.notes.edit(note.id, note.revisionId, "", "a\nreader\n")
            reader.report(blobs)
            val resolver = ConflictResolver(reader)
            resolver.settleHidden(
                resolver.hidden().single().revisionId,
                ConflictResolution.KeepShown,
            )
            reader.report(blobs)
            other.report(blobs)
            val shared = assertNotNull(other.notes.get(note.id))
            val settled = assertNotNull(reader.notes.get(note.id))
            assertEquals(settled.revisionId, shared.revisionId)
            reader.notes.edit(note.id, settled.revisionId, "", "${settled.body}reader 2\n")
            other.notes.edit(note.id, shared.revisionId, "", "other\n${shared.body}")
            reader.report(blobs)
            other.report(blobs)
            reader.report(blobs)
            assertTrue(resolver.hidden().isEmpty())
            assertEquals(1, RevisionGraph(reader.sync.revisions(note.id)).liveHeads.size)
        }

    @Test
    fun aFolderStartingFarUpLocksNobodyOut(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val folder = DeviceId.generate()
            val number = 999_999_999_999
            plant(blobs, listOf(root), folder, number)
            pc.report(blobs)
            assertEquals("a\nb\n", pc.notes.get(root.noteId)?.body)
            assertEquals(listOf(1L..number - 1), pc.sync.gaps("b", folder))
        }

    @Test
    fun aLateNumberInsideARangeIsReadAndSplitsIt(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val folder = DeviceId.generate()
            plant(blobs, listOf(root), folder, 5001)
            pc.report(blobs)
            plant(blobs, listOf(one), folder, 5)
            pc.report(blobs)
            assertEquals(listOf(1L..4L, 6L..5000L), pc.sync.gaps("b", folder))
            assertEquals("A\nb\n", pc.notes.get(root.noteId)?.body)
        }

    /** Closes [store], copies its file, and opens it again. Returns the copy's path. */
    private fun backUp(
        store: NoteStore,
        name: String,
    ): NoteStore {
        store.close()
        stores -= store
        FileSystem.SYSTEM.copy(dir / "$name.db", dir / "$name-backup.db")
        return device(name)
    }

    private fun restore(
        store: NoteStore,
        name: String,
    ): NoteStore {
        store.close()
        stores -= store
        FileSystem.SYSTEM.delete(dir / "$name.db")
        FileSystem.SYSTEM.copy(dir / "$name-backup.db", dir / "$name.db")
        return device(name)
    }

    @Test
    fun aRestoredBackupTakesANewIdAndGetsItsTailBack(): TestResult =
        runTest {
            val blobs = InMemoryBlobStore()
            var phone = device("phone")
            val early = phone.notes.create("", "early")
            phone.report(blobs)
            phone = backUp(phone, "phone")
            val old = phone.deviceId
            val late = phone.notes.create("", "late")
            phone.report(blobs)
            val files = blobs.list(BackendLayout.deviceFolder(old))

            phone = restore(phone, "phone")
            assertNull(phone.notes.get(late.id))
            val report = phone.report(blobs)

            val renewed = assertIs<SyncIssue.DeviceRenewed>(report.issues.single())
            assertEquals(old, renewed.old)
            assertEquals(renewed.new, phone.deviceId)
            assertEquals("late", phone.notes.get(late.id)?.body)
            assertEquals("early", phone.notes.get(early.id)?.body)
            phone.notes.create("", "new")
            phone.report(blobs)
            assertEquals(files, blobs.list(BackendLayout.deviceFolder(old)))
            val path = BackendLayout.segmentPath(renewed.new, 1)
            val pushed = JsonGzipCodec().decode(blobs.read(path)).revisions
            assertTrue(
                pushed.none { it.noteId == early.id || it.noteId == late.id },
                "What the old folder holds needs no second copy: $pushed",
            )
        }

    @Test
    fun forgettingAPendingNumberLeavesItToBeChecked(): TestResult =
        runTest {
            val blobs = InMemoryBlobStore()
            var phone = device("phone")
            phone.report(blobs)
            phone = backUp(phone, "phone")
            val id = phone.deviceId
            val tail = phone.notes.create("", "the tail")
            phone.report(blobs)
            phone = restore(phone, "phone")
            phone.notes.create("", "after the restore")
            // The listing does not show the tail, and the write of the same number fails.
            val blind =
                object : BlobStore by blobs {
                    override suspend fun list(prefix: String) =
                        blobs.list(prefix).filterNot { it.path.endsWith(".json.gz") }

                    override suspend fun writeNew(
                        path: String,
                        bytes: ByteArray,
                    ): Unit = throw BlobStoreFailureException("disk full")
                }
            val engine = SyncEngine(phone, DeviceInfo("d", "test"), EmptyCoroutineContext)
            assertIs<SyncResult.Interrupted>(engine.sync(SyncTarget("b", blind)))
            assertEquals(mapOf(1L to false), phone.sync.ownSegments("b"))
            engine.forget("b")
            assertEquals(0, phone.sync.lastSegmentNumber("b"))

            assertIs<SyncIssue.DeviceRenewed>(phone.report(blobs).issues.single())
            assertNotEquals(id, phone.deviceId)
            assertEquals("the tail", phone.notes.get(tail.id)?.body)
        }

    @Test
    fun aSegmentLostAndRewrittenByABackupIsReadAgain(): TestResult =
        runTest {
            val blobs = LossyBlobStore()
            val pc = device("pc")
            var phone = device("phone")
            phone.report(blobs)
            phone = backUp(phone, "phone")
            phone.notes.create("", "lost with the backend")
            phone.report(blobs)
            pc.report(blobs)
            blobs.lose(BackendLayout.segmentPath(phone.deviceId, 1))
            phone = restore(phone, "phone")
            val again = phone.notes.create("", "written again under the same number, longer")
            phone.report(blobs)
            pc.report(blobs)
            assertEquals(again.body, pc.notes.get(again.id)?.body)
        }

    @Test
    fun aRewrittenSegmentThatBroughtNothingNewIsStillNoticed(): TestResult =
        runTest {
            val blobs = LossyBlobStore()
            val pc = device("pc")
            val writer = DeviceId.generate()
            val path = BackendLayout.segmentPath(writer, 1)
            plant(blobs, listOf(root))
            pc.report(blobs)
            plant(blobs, listOf(root), writer)
            pc.report(blobs)
            blobs.lose(path)
            plant(blobs, listOf(root, one), writer)
            pc.report(blobs)
            assertEquals("A\nb\n", pc.notes.get(root.noteId)?.body)
        }

    @Test
    fun aRestoredBackupSeeingNoTailHereFindsItOnTheNextBackend(): TestResult =
        runTest {
            val first = InMemoryBlobStore()
            val second = InMemoryBlobStore()
            var phone = device("phone")
            phone.notes.create("", "early")
            phone.report(first, "first")
            phone.report(second, "second")
            phone = backUp(phone, "phone")
            val old = phone.deviceId
            val late = phone.notes.create("", "late")
            phone.report(second, "second")

            phone = restore(phone, "phone")
            assertTrue(phone.report(first, "first").issues.isEmpty())
            assertEquals(old, phone.deviceId)
            assertIs<SyncIssue.DeviceRenewed>(phone.report(second, "second").issues.single())
            assertNotEquals(old, phone.deviceId)
            assertEquals("late", phone.notes.get(late.id)?.body)
            phone.report(first, "first")
            val pc = device("pc")
            pc.report(first)
            assertEquals("late", pc.notes.get(late.id)?.body)
        }

    @Test
    fun twoCopiesOfABackupEachTakeTheirOwnId(): TestResult =
        runTest {
            val blobs = InMemoryBlobStore()
            var phone = device("phone")
            phone.report(blobs)
            phone = backUp(phone, "phone")
            val old = phone.deviceId
            val tail = phone.notes.create("", "tail")
            phone.report(blobs)
            FileSystem.SYSTEM.copy(dir / "phone-backup.db", dir / "copy.db")
            val copy = device("copy")
            phone = restore(phone, "phone")
            phone.notes.create("", "on the first copy")
            copy.notes.create("", "on the second copy")
            repeat(2) {
                phone.report(blobs)
                copy.report(blobs)
            }
            assertEquals(3, phone.notes.list().size)
            assertEquals(3, copy.notes.list().size)
            assertEquals(
                setOf("tail"),
                phone.notes
                    .list()
                    .map { it.body }
                    .filter { it == tail.body }
                    .toSet(),
            )
            assertEquals(3, setOf(old, phone.deviceId, copy.deviceId).size)
        }

    @Test
    fun aDifferentFileAtAPendingPathEndsTheIdAndOverwritesNothing(): TestResult =
        runTest {
            val phone = device("phone")
            val inner = InMemoryBlobStore()
            phone.report(inner)
            val old = phone.deviceId
            val note = phone.notes.create("t", "body")
            val path = BackendLayout.segmentPath(old, 1)
            val foreign = textRevision(9, 10, "a copied database wrote this")
            val bytes = JsonGzipCodec().encode(Segment(old, 1, listOf(foreign)))
            val racing =
                object : BlobStore by inner {
                    // The listing was taken before the copy wrote its segment.
                    override suspend fun list(prefix: String) =
                        inner.list(prefix).filter {
                            it.path !=
                                path
                        }
                }
            inner.writeNew(path, bytes)
            val renewed = phone.report(racing).issues.single()
            assertIs<SyncIssue.DeviceRenewed>(renewed)
            assertTrue(inner.read(path).contentEquals(bytes))
            phone.report(inner)
            assertTrue(inner.list(BackendLayout.deviceFolder(phone.deviceId)).size > 1)
            assertEquals(
                "body",
                device("pc")
                    .also { it.report(inner) }
                    .notes
                    .get(note.id)
                    ?.body,
            )
            assertEquals("a copied database wrote this", phone.notes.get(foreign.noteId)?.body)
        }

    @Test
    fun theSameStoreUnderANewBackendIdIsNoRestoredBackup(): TestResult =
        runTest {
            val phone = device("phone")
            val blobs = InMemoryBlobStore()
            phone.notes.create("t", "one")
            phone.report(blobs, "old")
            val id = phone.deviceId
            assertTrue(phone.report(blobs, "new").issues.isEmpty())
            assertEquals(id, phone.deviceId)
        }

    @Test
    fun aFarFutureStampOfTheClockStillCounts() {
        val parent = textRevision(1, Long.MAX_VALUE / 2, "future")
        val child = textRevision(2, Long.MAX_VALUE / 2 + 1, "later", parent)
        assertNotNull(RevisionGraph(listOf(parent, child))[child.id])
        assertEquals(HybridTimestamp(1, 0), textRevision(3, 1, "x").timestamp)
    }
}
