package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.storage.HiddenReason
import io.github.sameerkrmishra.jotter.core.storage.MAX_NOTE_CHARS
import io.github.sameerkrmishra.jotter.core.storage.NoteFilter
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.NoteTooLargeException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What happens around lost, forged and oversized history. Each test came from a review finding. */
class LostHistoryTest {
    private val dir =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-lost-test-${Random.nextLong().toULong()}"
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

    private suspend fun NoteStore.sync(
        blobs: BlobStore,
        target: String = "b",
    ): SyncReport {
        val engine = SyncEngine(this, DeviceInfo("d", "test"), EmptyCoroutineContext)
        val result = engine.sync(SyncTarget(target, blobs))
        return assertIs<SyncResult.Completed>(result, "$result").report
    }

    /** Closes [store] and opens its file again, with no SyncEngine or ConflictResolver on it. */
    private fun reopen(store: NoteStore): NoteStore {
        store.close()
        stores.remove(store)
        return NoteStore.open(store.path, "again", EmptyCoroutineContext).also { stores += it }
    }

    private fun NoteStore.live(noteId: NoteId) = RevisionGraph(sync.revisions(noteId)).liveHeads

    /** A reader that holds an edit, while the parent of what a dead writer wrote is lost. */
    private inner class DeadWriter {
        val writer = device("writer")
        val reader = device("reader")
        val blobs = LossyBlobStore()
        val note = writer.notes.create("", "a\nb\n")

        suspend fun run() {
            writer.sync(blobs)
            reader.sync(blobs)
            val first = writer.notes.edit(note.id, note.revisionId, "", "writer 1\nb\n")
            writer.sync(blobs)
            writer.notes.edit(note.id, first.revisionId, "", "writer 2\nb\n")
            writer.sync(blobs)
            blobs.lose(blobs.pathOfNumber(writer.deviceId, 2))
            reader.notes.edit(note.id, note.revisionId, "", "a\nreader\n")
            reader.sync(blobs)
        }
    }

    @Test
    fun aTombstoneBesideASurvivingEditLeavesTheConflictOpen() {
        val root = textRevision(1, 10, "line")
        val win = textRevision(2, 30, "winner", root)
        val lose = textRevision(3, 20, "loser", root)
        val conflict = mergeRevisions(RevisionGraph(listOf(root, win, lose)), win, lose)
        val deleted = userRevision(4, 40, conflict) { RevisionContent.Tombstone }
        val edit = textRevision(5, 50, "winner more", conflict)
        val all = listOf(root, win, lose, conflict, deleted, edit)
        assertEquals(listOf(conflict), RevisionGraph(all).openConflicts)
        assertTrue(foldHeads(all).isEmpty())
        assertEquals(edit, RevisionGraph(all).shownHead())
        assertEquals(deleted.id, RevisionGraph(all - edit).heads.single().id)
        assertTrue(RevisionGraph(all - edit).openConflicts.isEmpty())
    }

    /**
     * Found by the nightly simulator, seed 262796: the fold's own merge was the missing parent of a
     * bad stamp, which proved the merge built on that stamp invalid. The deletion under it became a
     * live head only then, and one fold never came back to it. No sync would look at the note
     * again, as the invalid merge stays its only stored head, so it kept two live heads for good.
     */
    @Test
    fun aHeadThatTheFoldItselfFreesIsFoldedToo() {
        val root = textRevision(1, 10, "a\nb\n")
        val left = textRevision(2, 20, "x\nb\n", root)
        val right = textRevision(3, 30, "a\ny\n", root, device = DEVICE_B)
        val merged = mergeRevisions(RevisionGraph(listOf(root, left, right)), left, right)
        // Stamped below its parent, which has not arrived yet, so it counts until the fold.
        val badStamp = textRevision(4, 5, "x\ny\nbad\n", merged)
        val deleted = userRevision(5, 40, root) { RevisionContent.Tombstone }
        val early = listOf(root, left, right, badStamp, deleted)
        val onBadStamp = mergeRevisions(RevisionGraph(early), deleted, badStamp)
        val all = early + onBadStamp

        val merges = foldHeads(all)

        assertEquals(merged, merges.first())
        val graph = RevisionGraph(all + merges)
        assertTrue(badStamp in graph.invalid && onBadStamp in graph.invalid)
        val head = graph.liveHeads.single()
        assertEquals(setOf(deleted.id), head.parents - merged.id)
        assertEquals("x\ny\n", head.text.body)
        assertEquals(NoteState.TRASHED, head.text.state.value)
        assertTrue(foldHeads(all + merges).isEmpty())
    }

    /**
     * Found by the nightly simulator, seed 171987: deleting forever a note with a hidden head
     * removed its row, although the head with text shows (ADR-0013). Until the next sync the note
     * was gone from the list, and an answer to its hidden head had no shown head to name.
     */
    @Test
    fun deletingForeverBesideAHiddenHeadShowsThatHeadAtOnce(): TestResult =
        runTest {
            val world = DeadWriter()
            world.run()
            val reader = world.reader
            val resolver = ConflictResolver(reader)
            val hidden = resolver.hidden().single()
            reader.notes.setState(world.note.id, NoteState.TRASHED)

            reader.notes.deleteForever(world.note.id)

            val shown = assertNotNull(reader.notes.get(world.note.id))
            assertEquals(hidden.body, shown.body)
            assertEquals(NoteState.TRASHED, shown.state)
            val graph = storedGraph(reader.sync, world.note.id)
            assertEquals(graph.shownHead()?.id, shown.revisionId)
            assertEquals(1, graph.liveHeads.size)
            assertTrue(resolver.hidden().isEmpty())
            val before = reader.sync.revisions(world.note.id).size
            reader.sync(world.blobs)
            assertEquals(before, reader.sync.revisions(world.note.id).size)
            assertEquals(shown, reader.notes.get(world.note.id))
        }

    /** A process that never made a SyncEngine or a ConflictResolver leaves it to the next sync. */
    @Test
    fun withoutCoreSyncTheNextSyncShowsTheHeadADeletionLeft(): TestResult =
        runTest {
            val world = DeadWriter()
            world.run()
            val reader = reopen(world.reader)
            val hidden = reader.sync.hiddenHeads().single()
            reader.notes.setState(world.note.id, NoteState.TRASHED)

            reader.notes.deleteForever(world.note.id)

            assertNull(reader.notes.get(world.note.id))
            reader.sync(world.blobs)
            val shown = assertNotNull(reader.notes.get(world.note.id))
            assertEquals(hidden.body, shown.body)
            assertEquals(NoteState.TRASHED, shown.state)
        }

    /** Found by the simulator, seed 715: a tombstone twin with the lower id hid the text. */
    @Test
    fun ofTwinsTheOneWithTextGoesFirstWhateverItsId() {
        val root = textRevision(1, 10, "line")
        val edit = textRevision(2, 20, "kept", root)
        val deleted = userRevision(3, 30, root) { RevisionContent.Tombstone }
        val other = userRevision(4, 40, root, DEVICE_B) { RevisionContent.Tombstone }
        val edits = listOf(root, edit, deleted, other)
        val withText = mergeRevisions(RevisionGraph(edits), edit, other)
        val textTwin = mergeRevisions(RevisionGraph(edits + withText), withText, deleted)
        val bothDeleted = mergeRevisions(RevisionGraph(edits), deleted, other)
        // A tombstone merge of the same edits, with the lowest id there is.
        val lowest = RevisionId.fromMergeHash(ByteArray(RevisionId.MERGE_HASH_SIZE))
        val tombstoneTwin = bothDeleted.copy(id = lowest, parents = setOf(deleted.id, withText.id))
        val graph = RevisionGraph(edits + listOf(withText, textTwin, bothDeleted, tombstoneTwin))
        assertEquals(listOf(textTwin), graph.liveHeads)
    }

    @Test
    fun aConflictClosedByADeletionBesideAnotherHeadIsNotResolvedAgain(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val third = device("third")
            val blobs = InMemoryBlobStore()
            val note = phone.notes.create("", "line\nother\n")
            listOf(phone, pc, third).forEach { store -> store.sync(blobs) }
            phone.notes.edit(note.id, note.revisionId, "", "phone\nother\n")
            pc.notes.edit(note.id, note.revisionId, "", "pc\nother\n")
            repeat(2) { listOf(phone, pc).forEach { store -> store.sync(blobs) } }
            third.notes.edit(note.id, note.revisionId, "", "line\nthird\n")
            third.sync(blobs)
            phone.sync(blobs)
            val mergeId = ConflictResolver(phone).open().single().mergeId
            // Deleted where no core:sync settles it, such as an older version: the row stays.
            val reopened = reopen(phone)
            reopened.notes.setState(note.id, NoteState.TRASHED)
            reopened.notes.deleteForever(note.id)
            val resolver = ConflictResolver(reopened)
            assertEquals(mergeId, resolver.open().single().mergeId)

            assertNull(resolver.details(mergeId))
            resolver.resolveNow(mergeId, ConflictResolution.KeepShown)

            assertTrue(resolver.open().isEmpty())
            assertEquals(
                "line\nthird\n",
                resolver.hidden().singleOrNull()?.body ?: reopened.notes.get(note.id)?.body,
            )
        }

    @Test
    fun aBackendAddedAgainTakesNumbersAboveItsFiles(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            phone.notes.create("t", "one")
            phone.sync(blobs)
            pc.sync(blobs)
            SyncEngine(phone, DeviceInfo("d", "test"), EmptyCoroutineContext).forget("b")
            val second = phone.notes.create("t", "two")
            val report = phone.sync(blobs)
            assertTrue(report.issues.isEmpty(), "${report.issues}")
            assertEquals(setOf(2L), phone.sync.published("b").keys)
            pc.sync(blobs)
            assertNotNull(pc.notes.get(second.id))
        }

    @Test
    fun aStoreAddedUnderANewIdTakesNumbersAboveItsFiles(): TestResult =
        runTest {
            val phone = device("phone")
            val blobs = InMemoryBlobStore()
            phone.notes.create("t", "one")
            phone.sync(blobs, target = "old")
            phone.notes.create("t", "two")
            val report = phone.sync(blobs, target = "new")
            assertTrue(report.issues.isEmpty(), "${report.issues}")
            assertEquals(setOf(2L), phone.sync.published("new").keys)
        }

    @Test
    fun aFileLostBeforeTheBackendWasForgottenLosesNothing(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = LossyBlobStore()
            phone.notes.create("t", "one")
            phone.sync(blobs)
            pc.sync(blobs)
            blobs.lose(blobs.pathOfNumber(phone.deviceId, 1))
            SyncEngine(phone, DeviceInfo("d", "test"), EmptyCoroutineContext).forget("b")
            val second = phone.notes.create("t", "two")
            phone.sync(blobs)
            pc.sync(blobs)
            assertNotNull(pc.notes.get(second.id))
        }

    @Test
    fun aReaderPublishesWhatADeadWritersLostSegmentHeld(): TestResult =
        runTest {
            val writer = device("writer")
            val reader = device("reader")
            val late = device("late")
            val blobs = LossyBlobStore()
            val note = writer.notes.create("t", "body")
            writer.sync(blobs)
            reader.sync(blobs)
            val lost = blobs.pathOfNumber(writer.deviceId, 1)
            assertTrue(reader.sync.readFiles("b").any { lost.endsWith(it.second) })
            blobs.lose(lost)
            assertEquals(1, reader.sync(blobs).pushedSegments)
            assertTrue(reader.sync.readFiles("b").none { lost.endsWith(it.second) })
            late.sync(blobs)
            assertEquals("body", late.notes.get(note.id)?.body)
        }

    @Test
    fun anEditBesideAMissingAncestorIsSurfacedAndStaysSo(): TestResult =
        runTest {
            val world = DeadWriter()
            world.run()
            val reader = world.reader
            val resolver = ConflictResolver(reader)
            repeat(2) {
                val hidden = resolver.hidden().single()
                assertEquals(HiddenReason.MISSING_ANCESTOR, hidden.reason)
                assertEquals(hidden, resolver.observeHidden().first().single())
                val shown = reader.notes.get(world.note.id)?.body
                assertEquals(setOf("writer 2\nb\n", "a\nreader\n"), setOf(shown, hidden.body))
                val issues = reader.sync(world.blobs).issues
                assertTrue(
                    issues.any { issue -> (issue as? SyncIssue.SegmentNotApplied)?.number == 2L },
                    "$issues",
                )
            }
        }

    @Test
    fun theUserCanSettleAHeadWhoseAncestorWillNeverCome(): TestResult =
        runTest {
            val world = DeadWriter()
            world.run()
            val reader = world.reader
            val resolver = ConflictResolver(reader)
            val hidden = resolver.hidden().single()
            resolver.settleNow(reader, hidden.revisionId, ConflictResolution.KeepBoth)
            assertTrue(resolver.hidden().isEmpty())
            assertEquals(1, reader.live(world.note.id).size)
            assertTrue(reader.notes.list().any { it.body == hidden.body })
            reader.sync(world.blobs)
            assertTrue(resolver.hidden().isEmpty())
            assertFailsWith<NoSuchHiddenHeadException> {
                resolver.settleNow(reader, hidden.revisionId, ConflictResolution.KeepShown)
            }
        }

    /** Settling builds on the shown head, names the hidden one, and takes its text if asked. */
    @Test
    fun settlingAHiddenHeadWithItsOwnTextBuildsOnTheShownHead(): TestResult =
        runTest {
            val world = DeadWriter()
            world.run()
            val reader = world.reader
            val shown = assertNotNull(reader.notes.get(world.note.id))
            val resolver = ConflictResolver(reader)
            val hidden = resolver.hidden().single()

            val settled =
                resolver.settleNow(
                    reader,
                    hidden.revisionId,
                    ConflictResolution.KeepLosing,
                )

            assertEquals(hidden.body, settled?.body)
            val revision = assertNotNull(settled?.revisionId?.let(reader.sync::revision))
            assertEquals(setOf(shown.revisionId), revision.parents)
            assertEquals(hidden.revisionId, revision.resolves)
        }

    @Test
    fun settlingOnAnOlderShownVersionIsRefused(): TestResult =
        runTest {
            val world = DeadWriter()
            world.run()
            val reader = world.reader
            val shown = assertNotNull(reader.notes.get(world.note.id))
            val resolver = ConflictResolver(reader)
            val hidden = resolver.hidden().single()
            val newer = reader.notes.edit(shown.id, shown.revisionId, shown.title, "NEW LINE")

            val result =
                resolver.settleHidden(
                    hidden.revisionId,
                    ConflictResolution.KeepLosing,
                    shown.revisionId,
                )

            assertEquals(ResolveResult.ShownChanged(newer.revisionId), result)
            assertEquals("NEW LINE", reader.notes.get(shown.id)?.body)
            assertEquals(hidden, resolver.hidden().single())
        }

    @Test
    fun settlingAHiddenHeadByKeepingTheShownTextChangesNoText(): TestResult =
        runTest {
            val world = DeadWriter()
            world.run()
            val shown = assertNotNull(world.reader.notes.get(world.note.id))
            val resolver = ConflictResolver(world.reader)
            val hidden = resolver.hidden().single()
            val settled =
                resolver.settleNow(
                    world.reader,
                    hidden.revisionId,
                    ConflictResolution.KeepShown,
                )
            assertEquals(shown.body, settled?.body)
            assertTrue(settled?.revisionId != shown.revisionId)
        }

    @Test
    fun aHiddenHeadCannotBeSettledWithTextOverTheCap(): TestResult =
        runTest {
            val world = DeadWriter()
            world.run()
            val resolver = ConflictResolver(world.reader)
            val hidden = resolver.hidden().single()
            val tooLong = ConflictResolution.Edited("", "x".repeat(MAX_NOTE_CHARS + 1))
            assertFailsWith<NoteTooLargeException> {
                resolver.settleNow(world.reader, hidden.revisionId, tooLong)
            }
            assertEquals(hidden, resolver.hidden().single())
        }

    @Test
    fun theIssueEndsWhenTheAncestorComes(): TestResult =
        runTest {
            val world = DeadWriter()
            world.run()
            val resolver = ConflictResolver(world.reader)
            assertEquals(1, resolver.hidden().size)
            world.writer.sync(world.blobs)
            world.reader.sync(world.blobs)
            assertTrue(resolver.hidden().isEmpty())
            assertEquals(
                "writer 2\nreader\n",
                world.reader.notes
                    .get(world.note.id)
                    ?.body,
            )
        }

    @Test
    fun aHeadBesideAnOpenConflictIsSurfaced(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val third = device("third")
            val blobs = InMemoryBlobStore()
            val note = phone.notes.create("", "line\nother\n")
            listOf(phone, pc, third).forEach { store -> store.sync(blobs) }
            phone.notes.edit(note.id, note.revisionId, "", "phone\nother\n")
            pc.notes.edit(note.id, note.revisionId, "", "pc\nother\n")
            repeat(2) { listOf(phone, pc).forEach { store -> store.sync(blobs) } }
            third.notes.edit(note.id, note.revisionId, "", "line\nthird\n")
            third.sync(blobs)
            phone.sync(blobs)
            val hidden = ConflictResolver(phone).hidden().single()
            assertEquals(HiddenReason.OPEN_CONFLICT, hidden.reason)
            assertEquals("line\nthird\n", hidden.body)
        }

    @Test
    fun aMergePastTheCapIsAConflict(): TestResult =
        runTest {
            // Long lines, so that the diff stays quick on the Native debug binary.
            val large = "x".repeat(MAX_NOTE_CHARS * 2 / 3)
            val root = textRevision(1, 10, "line\n")
            val one = textRevision(2, 20, "${large}a\nline\n", root)
            val two = textRevision(3, 30, "line\n${large}b\n", root)
            val merge = mergeRevisions(RevisionGraph(listOf(root, one, two)), one, two)
            assertTrue(merge.isConflictedMerge())
            assertEquals(two.text.body, merge.text.body)
        }

    @Test
    fun aNoteAtTheCapFitsASegment(): TestResult =
        runTest {
            val note = textRevision(1, 10, "x".repeat(MAX_NOTE_CHARS))
            val (batches, tooLarge) = SegmentBatches(MAX_SEGMENT_BYTES).split(listOf(note))
            assertEquals(1, batches.size)
            assertTrue(tooLarge.isEmpty())
        }

    @Test
    fun aNoteOverTheCapFromBeforeItBlocksNoOtherNote(): TestResult =
        runTest {
            val phone = device("phone")
            val pc = device("pc")
            val blobs = InMemoryBlobStore()
            val oversized = textRevision(1, 10, "x".repeat(MAX_NOTE_CHARS * 2))
            phone.sync.write { phone.sync.insert(oversized) }
            val other = phone.notes.create("t", "small")
            val report = phone.sync(blobs)
            assertEquals(listOf<SyncIssue>(SyncIssue.RevisionTooLarge(oversized.id)), report.issues)
            pc.sync(blobs)
            assertNotNull(pc.notes.get(other.id))
            assertEquals(1, pc.notes.list(NoteFilter(state = NoteState.ACTIVE)).size)
        }
}
