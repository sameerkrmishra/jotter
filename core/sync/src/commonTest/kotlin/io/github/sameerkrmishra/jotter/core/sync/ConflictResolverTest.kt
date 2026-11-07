package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.storage.MAX_NOTE_CHARS
import io.github.sameerkrmishra.jotter.core.storage.NoteFilter
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.NoteTooLargeException
import io.github.sameerkrmishra.jotter.core.storage.ResolutionDraft
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

class ConflictResolverTest {
    private val dir =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "jotter-conflict-test-${Random.nextLong().toULong()}"
    private val blobs = InMemoryBlobStore()
    private var phone = open("phone")
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
            conflicts.resolveNow(mergeId, ConflictResolution.KeepShown)
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
            val result = conflicts.resolveNow(conflict.mergeId, ConflictResolution.KeepShown)
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
            conflicts.resolveNow(conflict.mergeId, ConflictResolution.KeepLosing)
            exchange()
            assertEquals(conflict.losingBody, pc.notes.get(note.id)?.body)
        }

    @Test
    fun keepingBothMakesANewNote(): TestResult =
        runTest {
            val (_, conflicts) = conflicted()
            val conflict = conflicts.open().single()
            conflicts.resolveNow(conflict.mergeId, ConflictResolution.KeepBoth)
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
            conflicts.resolveNow(conflict.mergeId, ConflictResolution.Edited("new\u0000", "both"))
            assertEquals("new�", phone.notes.get(note.id)?.title)
        }

    @Test
    fun anEditedResolutionOverTheCapIsRefusedAndTheConflictStays(): TestResult =
        runTest {
            val (_, conflicts) = conflicted()
            val mergeId = conflicts.open().single().mergeId
            val tooLong = ConflictResolution.Edited("", "x".repeat(MAX_NOTE_CHARS + 1))
            assertFailsWith<NoteTooLargeException> { conflicts.resolveNow(mergeId, tooLong) }
            assertEquals(mergeId, conflicts.open().single().mergeId)
        }

    /** One device deletes and another edits on top of a conflict, in either order of time. */
    @Test
    fun theHeadOfAConflictHasTextWhicheverHeadComesFirst(): TestResult =
        runTest {
            listOf(true, false).forEach { deletesFirst ->
                val (note, conflicts) = conflicted()
                val conflict = conflicts.open().single { it.noteId == note.id }
                val shown = assertNotNull(pc.notes.get(note.id))
                val delete = {
                    phone.notes.setState(note.id, NoteState.TRASHED)
                    phone.notes.deleteForever(note.id)
                }
                if (deletesFirst) delete()
                pc.notes.edit(note.id, shown.revisionId, "title", "kept")
                if (!deletesFirst) delete()
                exchange()

                assertEquals("kept", conflicts.details(conflict.mergeId)?.shownBody)
                conflicts.resolveNow(conflict.mergeId, ConflictResolution.KeepShown)
                assertEquals("kept", phone.notes.get(note.id)?.body)
                exchange()
            }
        }

    @Test
    fun aClosedConflictCannotBeResolvedAgain(): TestResult =
        runTest {
            val (_, conflicts) = conflicted()
            val mergeId = conflicts.open().single().mergeId
            conflicts.resolveNow(mergeId, ConflictResolution.KeepShown)
            assertFailsWith<NoSuchConflictException> {
                conflicts.resolveNow(mergeId, ConflictResolution.KeepShown)
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
            conflicts.resolveNow(mergeId, ConflictResolution.KeepShown)
            assertEquals("edited meanwhile", phone.notes.get(note.id)?.body)
        }

    /** The UI's answer was based on what it showed: an edit made since must not vanish unseen. */
    @Test
    fun anAnswerBasedOnAnOlderShownVersionIsRefused(): TestResult =
        runTest {
            val (note, conflicts) = conflicted()
            val mergeId = conflicts.open().single().mergeId
            val seen = assertNotNull(conflicts.details(mergeId))
            val shown = assertNotNull(phone.notes.get(note.id))
            val newer = phone.notes.edit(note.id, shown.revisionId, "title", "NEW LINE")

            listOf(
                ConflictResolution.Edited("title", "combined from the old version"),
                ConflictResolution.KeepLosing,
            ).forEach { answer ->
                val result = conflicts.resolve(mergeId, answer, seen.shownHeadId)
                assertEquals(ResolveResult.ShownChanged(newer.revisionId), result)
            }
            assertEquals("NEW LINE", phone.notes.get(note.id)?.body)
            assertEquals(mergeId, conflicts.open().single().mergeId)
            val current = assertNotNull(conflicts.details(mergeId))
            assertEquals(newer.revisionId, current.shownHeadId)

            val answered =
                conflicts.resolve(
                    mergeId,
                    ConflictResolution.Edited("title", "both"),
                    newer.revisionId,
                )

            assertEquals("both", assertIs<ResolveResult.Resolved>(answered).note?.body)
            assertTrue(conflicts.open().isEmpty())
        }

    @Test
    fun aDraftOutlivesTheAppAndGoesWithItsAnswer(): TestResult =
        runTest {
            val (note, conflicts) = conflicted()
            val details = assertNotNull(conflicts.details(conflicts.open().single().mergeId))
            val mergeId = details.conflict.mergeId
            val draft = ResolutionDraft(mergeId, note.id, true, details.shownHeadId, "t", "typed")
            conflicts.putDraft(draft)
            conflicts.putDraft(draft.copy(body = "typed more"))
            phone.close()
            phone = open("phone")
            val reopened = ConflictResolver(phone)

            assertEquals(listOf(draft.copy(body = "typed more")), reopened.drafts())
            assertEquals(reopened.drafts(), reopened.observeDrafts().first())
            reopened.resolve(
                mergeId,
                ConflictResolution.Edited("t", "typed more"),
                details.shownHeadId,
            )
            assertEquals(emptyList(), reopened.drafts())
        }

    @Test
    fun aDraftStaysWhenItsConflictIsResolvedElsewhereUntilDiscarded(): TestResult =
        runTest {
            val (note, conflicts) = conflicted()
            val details = assertNotNull(conflicts.details(conflicts.open().single().mergeId))
            val mergeId = details.conflict.mergeId
            val draft = ResolutionDraft(mergeId, note.id, true, details.shownHeadId, "t", "mine")
            conflicts.putDraft(draft)
            ConflictResolver(pc).resolveNow(mergeId, ConflictResolution.KeepShown)
            exchange()

            assertTrue(conflicts.open().isEmpty())
            assertEquals(listOf(draft), conflicts.drafts())
            conflicts.discardDraft(mergeId)
            assertEquals(emptyList(), conflicts.drafts())
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
            conflicts.resolveNow(conflicts.open().single().mergeId, ConflictResolution.KeepShown)
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
            conflicts.resolveNow(mergeId, ConflictResolution.Edited("title", "phone's answer"))
            ConflictResolver(
                pc,
            ).resolveNow(mergeId, ConflictResolution.Edited("title", "pc's answer"))
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
    fun aHeadThatWaitedForADeletedConflictMergesAtOnce(): TestResult =
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

            assertEquals("line\nthird\n", phone.notes.get(note.id)?.body)
            assertEquals(NoteState.TRASHED, phone.notes.get(note.id)?.state)
            assertTrue(ConflictResolver(phone).open().isEmpty())
            val report = engine(phone).sync(target)
            assertEquals(0, (report as SyncResult.Completed).report.merges)
            assertEquals("line\nthird\n", phone.notes.get(note.id)?.body)
            third.close()
        }

    private companion object {
        const val ROUNDS = 3
        const val FAR_AHEAD = 4_000_000_000_000L
    }
}
