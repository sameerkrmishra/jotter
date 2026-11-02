package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A revision counts by itself and its ancestors alone, and a device can lose its past. */
@Suppress("LargeClass") // One rule per test, and they share the fixtures above.
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
        blobs.writeSegment(Segment(folder, number, revisions))
    }

    /**
     * What a device decides from: all its revisions of [noteId], and what it lists. The stored copy
     * of a collided id is left out: which one came first says nothing, and neither counts.
     */
    private fun NoteStore.view(noteId: NoteId): List<Any?> {
        val collided = sync.collidedIds()
        return listOf(
            sync.revisions(noteId).filterNot { it.id in collided }.toSet(),
            storedGraph(sync, noteId).liveHeads,
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

    /** An open conflict with a tombstone as its loser could never be shown or resolved. */
    @Test
    fun aMergeNamingATombstoneAsLoserDoesNotCount() {
        val deleted = userRevision(2, 20, root) { RevisionContent.Tombstone }
        val edit = textRevision(3, 30, "kept", root)
        val all = listOf(root, deleted, edit)
        val honest = mergeRevisions(RevisionGraph(all), edit, deleted)
        val wrong = honest.copy(kind = RevisionKind.AutomaticMerge(deleted.id))
        val forged = wrong.copy(id = mergeRevisionId(wrong))
        val graph = RevisionGraph(all + forged)
        assertNull(graph[forged.id])
        assertTrue(graph.openConflicts.isEmpty())
    }

    /** No clock goes above the last timestamp, so an edit on such a head could not be made. */
    @Test
    fun theLastTimestampDoesNotCountAndTheNoteCanStillBeEdited(): TestResult =
        runTest {
            val last = root.copy(timestamp = HybridTimestamp.MAX)
            assertNull(RevisionGraph(listOf(last))[last.id])
            val top = userRevision(4, 0, root) { snapshot(body = "the top", writer = it) }
            val store = device("edit")
            val blobs = InMemoryBlobStore()
            plant(blobs, listOf(root, top.copy(timestamp = HybridTimestamp.MAX)))
            store.report(blobs)
            val shown = assertNotNull(store.notes.get(root.noteId))
            assertEquals(root.text.body, shown.body)
            val edited = store.notes.edit(root.noteId, shown.revisionId, "", "edited")
            assertEquals("edited", edited.body)
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
            val name = BackendLayout.segmentName(1, contentHash(json))
            blobs.writeNew(BackendLayout.segmentPath(folder, name), out.readByteArray())
            assertTrue(store.report(blobs).issues.isEmpty())
            assertEquals("good", store.notes.get(good.noteId)?.body)
            val row = store.sync.invalidRevisions().single()
            assertEquals(InvalidReason.MALFORMED, row.reason)
            assertEquals("its words", row.body)
        }

    @Test
    fun neitherOfTwoRevisionsOfOneIdCountsInEitherOrder(): TestResult =
        runTest {
            val first = textRevision(1, 10, "first copy")
            val second = textRevision(1, 10, "second copy")
            assertSameInEveryOrder(first.noteId, listOf(listOf(first), listOf(second)))
            val store = device("collision")
            val blobs = InMemoryBlobStore()
            plant(blobs, listOf(first, second))
            store.report(blobs)
            assertNull(store.notes.get(first.noteId))
            val listed = store.sync.invalidRevisions()
            assertEquals(setOf(InvalidReason.ID_COLLISION), listed.map { it.reason }.toSet())
            assertEquals(setOf("first copy", "second copy"), listed.map { it.body }.toSet())
        }

    /** The second copy is of another note, so only the stored copy says that this one changed. */
    @Test
    fun aCollisionFromAnotherNoteStillEndsTheNoteOfTheStoredCopy(): TestResult =
        runTest {
            val first = textRevision(1, 10, "in the first note")
            val second = first.copy(noteId = NoteId.generate())
            val store = device("elsewhere")
            val blobs = InMemoryBlobStore()
            plant(blobs, listOf(first))
            store.report(blobs)
            assertEquals("in the first note", store.notes.get(first.noteId)?.body)
            plant(blobs, listOf(second))
            store.report(blobs)
            assertNull(store.notes.get(first.noteId))
        }

    @Test
    fun aDeletionSharingAnIdWithAnEditEndsNothing(): TestResult =
        runTest {
            val deleted = userRevision(2, 15, root) { RevisionContent.Tombstone }
            val edit = textRevision(2, 20, "my words", root)
            assertSameInEveryOrder(root.noteId, listOf(listOf(root, edit), listOf(deleted)))
            val store = device("deleted")
            val blobs = InMemoryBlobStore()
            plant(blobs, listOf(root, edit))
            store.report(blobs)
            plant(blobs, listOf(deleted))
            store.report(blobs)
            assertEquals(root.text.body, store.notes.get(root.noteId)?.body)
            assertTrue(store.sync.invalidRevisions().any { it.body == "my words" })
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
            blobs.lose(blobs.pathOfNumber(writer.deviceId, 2))
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
        }

    @Test
    fun aLateFileWithALowNumberIsRead(): TestResult =
        runTest {
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val folder = DeviceId.generate()
            plant(blobs, listOf(root), folder, 5001)
            pc.report(blobs)
            plant(blobs, listOf(one), folder, 5)
            pc.report(blobs)
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
    fun aRestoredBackupGetsItsTailBackFromItsOwnFolder(): TestResult =
        runTest {
            val blobs = InMemoryBlobStore()
            var phone = device("phone")
            val early = phone.notes.create("", "early")
            phone.report(blobs)
            phone = backUp(phone, "phone")
            val id = phone.deviceId
            val late = phone.notes.create("", "late")
            phone.report(blobs)
            val files = blobs.list(BackendLayout.deviceFolder(id))

            phone = restore(phone, "phone")
            assertNull(phone.notes.get(late.id))
            assertTrue(phone.report(blobs).issues.isEmpty())

            assertEquals(id, phone.deviceId)
            assertEquals("late", phone.notes.get(late.id)?.body)
            assertEquals("early", phone.notes.get(early.id)?.body)
            val added = phone.notes.create("", "new")
            assertEquals(1, phone.report(blobs).pushedSegments)
            val folder = blobs.list(BackendLayout.deviceFolder(id))
            assertTrue(folder.containsAll(files))
            val pushed = (folder - files.toSet()).single()
            val revisions = JsonGzipCodec().decode(blobs.read(pushed.path)).revisions
            assertEquals(setOf(added.id), revisions.map { it.noteId }.toSet())
        }

    @Test
    fun aRestoredBackupEditingBeforeItSyncsLosesNothing(): TestResult =
        runTest {
            val blobs = InMemoryBlobStore()
            val pc = device("pc")
            var phone = device("phone")
            phone.report(blobs)
            phone = backUp(phone, "phone")
            val tail = phone.notes.create("", "same length 1")
            phone.report(blobs)
            pc.report(blobs)
            phone = restore(phone, "phone")
            val after = phone.notes.create("", "same length 2")
            repeat(2) {
                phone.report(blobs)
                pc.report(blobs)
            }
            listOf(phone, pc).forEach { store ->
                assertEquals(tail.body, store.notes.get(tail.id)?.body)
                assertEquals(after.body, store.notes.get(after.id)?.body)
            }
        }

    @Test
    fun aSegmentLostAndWrittenAnewByABackupIsReadAndTheLostOneComesBack(): TestResult =
        runTest {
            val blobs = LossyBlobStore()
            val pc = device("pc")
            var phone = device("phone")
            phone.report(blobs)
            phone = backUp(phone, "phone")
            val lost = phone.notes.create("", "same length 1")
            phone.report(blobs)
            pc.report(blobs)
            blobs.lose(blobs.pathOfNumber(phone.deviceId, 1))
            phone = restore(phone, "phone")
            val again = phone.notes.create("", "same length 2")
            repeat(2) {
                phone.report(blobs)
                pc.report(blobs)
            }
            listOf(phone, pc).forEach { store ->
                assertEquals(lost.body, store.notes.get(lost.id)?.body)
                assertEquals(again.body, store.notes.get(again.id)?.body)
            }
        }

    /** Text of one length: the size of a file cannot tell what a restored device wrote again. */
    @Test
    fun aWholeDiskRestoreOfDatabaseAndBackendLosesNothing(): TestResult =
        runTest {
            val blobs = LossyBlobStore()
            val pc = device("pc")
            var hub = device("hub")
            hub.report(blobs)
            pc.report(blobs)
            hub = backUp(hub, "hub")
            val image = blobs.image()
            val forgotten = hub.notes.create("", "same length 1")
            hub.report(blobs)
            pc.report(blobs)

            hub = restore(hub, "hub")
            blobs.rollBack(image)
            val written = hub.notes.create("", "same length 2")
            repeat(2) {
                hub.report(blobs)
                pc.report(blobs)
            }

            listOf(hub, pc).forEach { store ->
                assertEquals(forgotten.body, store.notes.get(forgotten.id)?.body)
                assertEquals(written.body, store.notes.get(written.id)?.body)
            }
        }

    @Test
    fun aFileLostAndWrittenAnewThatBroughtNothingNewAtFirstIsStillRead(): TestResult =
        runTest {
            val blobs = LossyBlobStore()
            val pc = device("pc")
            val writer = DeviceId.generate()
            plant(blobs, listOf(root))
            pc.report(blobs)
            plant(blobs, listOf(root), writer)
            pc.report(blobs)
            blobs.lose(pathOf(Segment(writer, 1, listOf(root))))
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
            val late = phone.notes.create("", "late")
            phone.report(second, "second")

            phone = restore(phone, "phone")
            assertTrue(phone.report(first, "first").issues.isEmpty())
            assertTrue(phone.report(second, "second").issues.isEmpty())
            assertEquals("late", phone.notes.get(late.id)?.body)
            phone.report(first, "first")
            val pc = device("pc")
            pc.report(first)
            assertEquals("late", pc.notes.get(late.id)?.body)
        }

    @Test
    fun twoCopiesOfADatabaseShareAFolderAndLoseNothing(): TestResult =
        runTest {
            val blobs = InMemoryBlobStore()
            var phone = device("phone")
            phone.report(blobs)
            phone = backUp(phone, "phone")
            val tail = phone.notes.create("", "tail")
            phone.report(blobs)
            FileSystem.SYSTEM.copy(dir / "phone-backup.db", dir / "copy.db")
            val copy = device("copy")
            phone = restore(phone, "phone")
            val first = phone.notes.create("", "on the first copy")
            val second = copy.notes.create("", "on the second copy")
            repeat(2) {
                phone.report(blobs)
                copy.report(blobs)
            }
            assertEquals(phone.deviceId, copy.deviceId)
            val pc = device("pc")
            pc.report(blobs)
            listOf(phone, copy, pc).forEach { store ->
                listOf(tail, first, second).forEach { note ->
                    assertEquals(note.body, store.notes.get(note.id)?.body)
                }
            }
        }

    /** The listing was taken before a copy of this database wrote the same number. */
    @Test
    fun aCopysFileWithTheSameNumberIsNeitherOverwrittenNorMissed(): TestResult =
        runTest {
            val phone = device("phone")
            val inner = InMemoryBlobStore()
            phone.report(inner)
            val note = phone.notes.create("t", "body")
            val foreign = textRevision(9, 10, "a copied database wrote this")
            val segment = Segment(phone.deviceId, 1, listOf(foreign))
            val path = pathOf(segment)
            val bytes = JsonGzipCodec().encode(segment)
            val racing =
                object : BlobStore by inner {
                    override suspend fun list(prefix: String) =
                        inner.list(prefix).filter { it.path != path }
                }
            inner.writeNew(path, bytes)
            assertTrue(phone.report(racing).issues.isEmpty())
            assertTrue(inner.read(path).contentEquals(bytes))
            phone.report(inner)
            assertEquals("a copied database wrote this", phone.notes.get(foreign.noteId)?.body)
            val pc = device("pc")
            pc.report(inner)
            assertEquals("body", pc.notes.get(note.id)?.body)
            assertEquals("a copied database wrote this", pc.notes.get(foreign.noteId)?.body)
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
