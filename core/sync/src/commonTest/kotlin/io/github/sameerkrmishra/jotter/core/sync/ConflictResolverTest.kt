package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.storage.NoteFilter
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConflictResolverTest {
    private val dir =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "jotter-conflict-test-${Random.nextLong().toULong()}"
    private val blobs = InMemoryBlobStore()
    private val phone = open("phone")
    private val pc = open("pc")
    private val target = SyncTarget("b", blobs)

    private fun open(name: String): NoteStore {
        FileSystem.SYSTEM.createDirectories(dir)
        return NoteStore.open((dir / "$name.db").toString(), name, EmptyCoroutineContext)
    }

    @AfterTest
    fun cleanUp() {
        phone.close()
        pc.close()
        FileSystem.SYSTEM.deleteRecursively(dir)
    }

    private fun engine(store: NoteStore) =
        SyncEngine(store, DeviceInfo("d", "test"), EmptyCoroutineContext)

    private suspend fun exchange() {
        repeat(ROUNDS) {
            engine(phone).sync(target)
            engine(pc).sync(target)
        }
    }

    /** Both devices edit the one line of a note, so that the merge conflicts. */
    private suspend fun conflicted(): Pair<Note, ConflictResolver> {
        val note = phone.notes.create("title", "line")
        exchange()
        phone.notes.edit(note.id, note.revisionId, "title", "phone")
        pc.notes.edit(note.id, note.revisionId, "title", "pc")
        exchange()
        return note to ConflictResolver(phone)
    }

    @Test
    fun aConflictShowsOnBothDevicesWithTheLosingText(): TestResult =
        runTest {
            val (note, conflicts) = conflicted()
            val conflict = conflicts.open().single()
            assertEquals(note.id, conflict.noteId)
            assertEquals(conflict, ConflictResolver(pc).open().single())
            val shown = assertNotNull(phone.notes.get(note.id))
            assertEquals(shown, pc.notes.get(note.id))
            assertEquals(setOf("phone", "pc"), setOf(shown.body, conflict.losingBody))
            assertEquals(conflict.mergeId, shown.revisionId)
        }

    /** A revision on a parent in another note does not count, so it closes no conflict. */
    @Test
    fun aRevisionWithAParentInAnotherNoteDoesNotCloseAConflict(): TestResult =
        runTest {
            val other = phone.notes.create("other", "note")
            val foreign = phone.sync.revisions(other.id).single()
            val (note, conflicts) = conflicted()
            val mergeId = conflicts.open().single().mergeId
            val claim =
                textRevision(9, FAR_AHEAD, "claims to resolve it").copy(
                    noteId = note.id,
                    parents = setOf(foreign.id),
                    resolves = mergeId,
                )
            blobs.writeSegment(Segment(DeviceId.generate(), 1, listOf(claim)))
            engine(phone).sync(target)

            assertEquals(mergeId, conflicts.open().single().mergeId)
            assertEquals(mergeId, phone.notes.get(note.id)?.revisionId)
            assertNotNull(conflicts.details(mergeId))
            conflicts.resolve(mergeId, ConflictResolution.KeepShown)
            assertTrue(conflicts.open().isEmpty())
        }

    @Test
    fun theDetailsHoldTheTextWithMarkers(): TestResult =
        runTest {
            val (_, conflicts) = conflicted()
            val conflict = conflicts.open().single()
            val details = assertNotNull(conflicts.details(conflict.mergeId))
            assertEquals("title", details.markedTitle)
            assertTrue(details.markedBody.contains("<<<<<<< shown"))
            assertTrue(details.markedBody.contains(conflict.losingBody))
            assertTrue(details.markedBody.contains(details.shownBody))
            assertEquals(conflicts.observeOpen().first(), conflicts.open())
        }

    @Test
    fun keepingTheShownTextClosesTheConflictEverywhere(): TestResult =
        runTest {
            val (note, conflicts) = conflicted()
            val conflict = conflicts.open().single()
            val shown = phone.notes.get(note.id)?.body
            val result = conflicts.resolve(conflict.mergeId, ConflictResolution.KeepShown)
            assertEquals(shown, result?.body)
            exchange()
            assertTrue(conflicts.open().isEmpty())
            assertTrue(ConflictResolver(pc).open().isEmpty())
            assertEquals(phone.notes.get(note.id), pc.notes.get(note.id))
            assertNull(conflicts.details(conflict.mergeId))
        }

    @Test
    fun keepingTheLosingTextReplacesTheText(): TestResult =
        runTest {
            val (note, conflicts) = conflicted()
            val conflict = conflicts.open().single()
            conflicts.resolve(conflict.mergeId, ConflictResolution.KeepLosing)
            exchange()
            assertEquals(conflict.losingBody, pc.notes.get(note.id)?.body)
        }

    @Test
    fun keepingBothMakesANewNote(): TestResult =
        runTest {
            val (_, conflicts) = conflicted()
            val conflict = conflicts.open().single()
            conflicts.resolve(conflict.mergeId, ConflictResolution.KeepBoth)
            exchange()
            val bodies =
                pc.notes
                    .list()
                    .map { it.body }
                    .toSet()
            assertEquals(setOf("phone", "pc"), bodies)
        }

    @Test
    fun editedTextIsMadeStorable(): TestResult =
        runTest {
            val (note, conflicts) = conflicted()
            val conflict = conflicts.open().single()
            conflicts.resolve(conflict.mergeId, ConflictResolution.Edited("new\u0000", "both"))
            assertEquals("new�", phone.notes.get(note.id)?.title)
        }

    @Test
    fun aClosedConflictCannotBeResolvedAgain(): TestResult =
        runTest {
            val (_, conflicts) = conflicted()
            val mergeId = conflicts.open().single().mergeId
            conflicts.resolve(mergeId, ConflictResolution.KeepShown)
            assertFailsWith<NoSuchConflictException> {
                conflicts.resolve(mergeId, ConflictResolution.KeepShown)
            }
        }

    @Test
    fun editsDuringAConflictStayOnTopOfIt(): TestResult =
        runTest {
            val (note, conflicts) = conflicted()
            val mergeId = conflicts.open().single().mergeId
            val shown = assertNotNull(phone.notes.get(note.id))
            phone.notes.edit(note.id, shown.revisionId, "title", "edited meanwhile")
            assertEquals(mergeId, conflicts.open().single().mergeId)
            val details = assertNotNull(conflicts.details(mergeId))
            assertEquals("edited meanwhile", details.shownBody)
            conflicts.resolve(mergeId, ConflictResolution.KeepShown)
            assertEquals("edited meanwhile", phone.notes.get(note.id)?.body)
        }

    @Test
    fun otherHeadsWaitForTheConflictAndThenMerge(): TestResult =
        runTest {
            val third = open("third")
            val note = phone.notes.create("", "line\nother\n")
            exchange()
            engine(third).sync(target)
            phone.notes.edit(note.id, note.revisionId, "", "phone\nother\n")
            pc.notes.edit(note.id, note.revisionId, "", "pc\nother\n")
            exchange()
            third.notes.edit(note.id, note.revisionId, "", "line\nthird\n")
            engine(third).sync(target)
            exchange()
            val conflicts = ConflictResolver(phone)
            assertEquals(2, RevisionGraph(phone.sync.revisions(note.id)).liveHeads.size)
            conflicts.resolve(conflicts.open().single().mergeId, ConflictResolution.KeepShown)
            val merged = assertNotNull(phone.notes.get(note.id))
            assertTrue(merged.body.endsWith("third\n"))
            assertEquals(1, RevisionGraph(phone.sync.revisions(note.id)).liveHeads.size)
            third.close()
        }

    @Test
    fun twoDifferentResolutionsAreMergedLikeOtherEdits(): TestResult =
        runTest {
            val (note, conflicts) = conflicted()
            val mergeId = conflicts.open().single().mergeId
            conflicts.resolve(mergeId, ConflictResolution.Edited("title", "phone's answer"))
            ConflictResolver(pc).resolve(mergeId, ConflictResolution.Edited("title", "pc's answer"))
            exchange()
            assertEquals(phone.notes.get(note.id), pc.notes.get(note.id))
            assertEquals(1, conflicts.open().size)
            assertTrue(mergeId != conflicts.open().single().mergeId)
        }

    @Test
    fun deletingANoteClosesItsConflict(): TestResult =
        runTest {
            val (note, conflicts) = conflicted()
            phone.notes.setState(note.id, NoteState.TRASHED)
            phone.notes.deleteForever(note.id)
            assertTrue(conflicts.open().isEmpty())
            exchange()
            assertTrue(ConflictResolver(pc).open().isEmpty())
            assertTrue(pc.notes.list(NoteFilter(state = NoteState.TRASHED)).isEmpty())
        }

    @Test
    fun aHeadThatWaitedForADeletedConflictMergesOnTheNextSyncWithNothingNew(): TestResult =
        runTest {
            val third = open("third")
            val note = phone.notes.create("", "line\nother\n")
            exchange()
            engine(third).sync(target)
            phone.notes.edit(note.id, note.revisionId, "", "phone\nother\n")
            pc.notes.edit(note.id, note.revisionId, "", "pc\nother\n")
            exchange()
            third.notes.edit(note.id, note.revisionId, "", "line\nthird\n")
            engine(third).sync(target)
            exchange()
            phone.notes.setState(note.id, NoteState.TRASHED)
            phone.notes.deleteForever(note.id)
            assertNull(phone.notes.get(note.id))

            val report = engine(phone).sync(target)

            assertEquals(1, (report as SyncResult.Completed).report.merges)
            assertEquals("line\nthird\n", phone.notes.get(note.id)?.body)
            assertEquals(NoteState.TRASHED, phone.notes.get(note.id)?.state)
            third.close()
        }

    private companion object {
        const val ROUNDS = 3
        const val FAR_AHEAD = 4_000_000_000_000L
    }
}
