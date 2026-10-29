package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.Stamped
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.kotest.property.Arb
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class NoteServiceTest {
    private val clock = FakeClock(millis = 1_000)
    private val shopping = TagName.parse("Shopping")
    private val urgent = TagName.parse("urgent")

    @Test
    fun createdNoteCanBeReadBack() =
        withStore(clock) { store ->
            val note = store.notes.create("Groceries", "- [ ] milk", setOf(shopping), pinned = true)

            assertEquals("Groceries", note.title)
            assertEquals("- [ ] milk", note.body)
            assertEquals(setOf(shopping), note.tags)
            assertEquals(true, note.isPinned)
            assertEquals(NoteState.ACTIVE, note.state)
            assertEquals(Instant.fromEpochMilliseconds(1_000), note.createdAt)
            assertEquals(HybridTimestamp(1_000, 0), note.updatedAt)
            assertEquals(note, store.notes.get(note.id))
        }

    @Test
    fun createWritesAFirstUserRevisionThatStampsBothFields() =
        withStore(clock) { store ->
            val note = store.notes.create("", "body")

            val revision = store.notes.history(note.id).single()
            assertEquals(note.revisionId, revision.id)
            assertEquals(emptySet(), revision.parents)
            assertEquals(store.deviceId, revision.deviceId)
            assertEquals(RevisionKind.User, revision.kind)
            val content = assertIs<RevisionContent.Snapshot>(revision.content)
            assertEquals(Stamped(false, revision.stamp), content.pinned)
            assertEquals(Stamped(NoteState.ACTIVE, revision.stamp), content.state)
        }

    @Test
    fun eachChangeBuildsOnTheCurrentRevision() =
        withStore(clock) { store ->
            val notes = store.notes
            val created = notes.create("Groceries", "- [ ] milk")
            val edited = notes.edit(created.id, created.revisionId, "Groceries", "- [x] milk")
            val tagged = notes.addTag(created.id, shopping)
            val archived = notes.setState(created.id, NoteState.ARCHIVED)

            val history = notes.history(created.id)
            assertEquals(
                listOf(created, edited, tagged, archived).map(Note::revisionId),
                history.map { it.id },
            )
            history.zipWithNext { parent, child ->
                assertEquals(setOf(parent.id), child.parents)
            }
            assertEquals(archived, notes.get(created.id))
            assertEquals("- [x] milk", archived.body)
            assertEquals(setOf(shopping), archived.tags)
            assertEquals(created.createdAt, archived.createdAt)
        }

    @Test
    fun onlyTheRevisionThatWritesAFieldStampsIt() =
        withStore(clock) { store ->
            val notes = store.notes
            val created = notes.create("title", "body")
            val pinned = notes.setPinned(created.id, true)
            val edited = notes.edit(created.id, pinned.revisionId, "title", "new body")
            val tagged = notes.addTag(created.id, shopping)
            val trashed = notes.setState(created.id, NoteState.TRASHED)

            val revisions = notes.history(created.id).associateBy { it.id }

            fun stampOf(note: Note) = revisions.getValue(note.revisionId).stamp

            fun snapshotOf(note: Note) =
                assertIs<RevisionContent.Snapshot>(revisions.getValue(note.revisionId).content)

            val pinStamped = Stamped(true, stampOf(pinned))
            val createStamped = Stamped(NoteState.ACTIVE, stampOf(created))
            assertEquals(pinStamped, snapshotOf(pinned).pinned)
            assertEquals(createStamped, snapshotOf(pinned).state)
            // An edit and a tag change write neither field, so they carry both stamps over.
            listOf(edited, tagged).forEach { note ->
                assertEquals(pinStamped, snapshotOf(note).pinned)
                assertEquals(createStamped, snapshotOf(note).state)
            }
            assertEquals(pinStamped, snapshotOf(trashed).pinned)
            assertEquals(Stamped(NoteState.TRASHED, stampOf(trashed)), snapshotOf(trashed).state)
        }

    @Test
    fun changesThatChangeNothingCreateNoRevision() =
        withStore(clock) { store ->
            val notes = store.notes
            val note = notes.create("title", "body", setOf(shopping), pinned = true)

            val unchanged =
                listOf(
                    notes.edit(note.id, note.revisionId, "title", "body"),
                    notes.addTag(note.id, shopping),
                    notes.removeTag(note.id, urgent),
                    notes.setPinned(note.id, true),
                    notes.setState(note.id, NoteState.ACTIVE),
                )

            unchanged.forEach { assertEquals(note, it) }
            assertEquals(1, notes.history(note.id).size)
        }

    @Test
    fun anEditReplacesTitleAndBodyOnly() =
        withStore(clock) { store ->
            val notes = store.notes
            val note = notes.create("Groceries", "- [ ] milk", setOf(shopping), pinned = true)

            val edited =
                notes.edit(
                    note.id,
                    note.revisionId,
                    "Shopping list",
                    "- [ ] milk\n- [ ] eggs",
                )

            assertEquals("Shopping list", edited.title)
            assertEquals("- [ ] milk\n- [ ] eggs", edited.body)
            assertEquals(setOf(shopping), edited.tags)
            assertEquals(true, edited.isPinned)
            assertEquals(note.createdAt, edited.createdAt)
            assertEquals(edited, notes.get(note.id))
        }

    @Test
    fun tagsAreAddedAndRemoved() =
        withStore(clock) { store ->
            val notes = store.notes
            val id = notes.create("", "body", setOf(shopping)).id

            assertEquals(setOf(shopping, urgent), notes.addTag(id, urgent).tags)
            assertEquals(setOf(urgent), notes.removeTag(id, shopping).tags)
            assertEquals(setOf(urgent), notes.get(id)?.tags)
        }

    @Test
    fun anEditBasedOnAnOlderRevisionIsRejected() =
        withStore(clock) { store ->
            val notes = store.notes
            val opened = notes.create("title", "draft")
            val elsewhere = notes.edit(opened.id, opened.revisionId, "title", "edited elsewhere")

            val error =
                assertFailsWith<StaleEditException> {
                    notes.edit(opened.id, opened.revisionId, "title", "my edit")
                }

            assertEquals(opened.id, error.noteId)
            assertEquals(opened.revisionId, error.basedOn)
            assertEquals(elsewhere.revisionId, error.current)
            assertEquals(elsewhere, notes.get(opened.id))
            assertEquals(2, notes.history(opened.id).size)
        }

    @Test
    fun tagPinAndStateChangesNeedNoBaseRevision() =
        withStore(clock) { store ->
            val notes = store.notes
            val opened = notes.create("title", "body")
            notes.edit(opened.id, opened.revisionId, "title", "edited elsewhere")

            // The editor still shows `opened`, but these apply to the current revision.
            val pinned = notes.setPinned(opened.id, true)

            assertEquals("edited elsewhere", pinned.body)
            assertEquals(true, pinned.isPinned)
        }

    @Test
    fun aNoteIsDeletedForeverOnlyFromTheTrash() =
        withStore(clock) { store ->
            val notes = store.notes
            val id = notes.create("title", "body", setOf(shopping)).id

            val error = assertFailsWith<IllegalStateException> { notes.deleteForever(id) }
            assertEquals(
                "Only a note in the trash can be deleted forever, but note $id is ACTIVE",
                error.message,
            )

            notes.setState(id, NoteState.TRASHED)
            notes.deleteForever(id)

            assertNull(notes.get(id))
            assertEquals(emptyList(), notes.list(NoteFilter(NoteState.TRASHED)))
            val history = notes.history(id)
            assertEquals(3, history.size)
            assertEquals(RevisionContent.Tombstone, history.last().content)
            assertEquals(setOf(history[1].id), history.last().parents)
        }

    @Test
    fun aMissingNoteCannotBeChanged() =
        withStore(clock) { store ->
            val notes = store.notes
            val deleted = notes.create("title", "body")
            notes.setState(deleted.id, NoteState.TRASHED)
            notes.deleteForever(deleted.id)
            val neverExisted = NoteId.parse("0192e6f0-8b4a-7c3e-9d2f-1a2b3c4d5e6f")

            listOf(deleted.id, neverExisted).forEach { id ->
                val changes =
                    listOf(
                        { notes.edit(id, deleted.revisionId, "t", "b") },
                        { notes.addTag(id, shopping) },
                        { notes.removeTag(id, shopping) },
                        { notes.setPinned(id, true) },
                        { notes.setState(id, NoteState.ACTIVE) },
                        { notes.deleteForever(id) },
                    )
                changes.forEach { change ->
                    val error = assertFailsWith<NoSuchNoteException> { change() }
                    assertEquals(id, error.noteId)
                }
            }
            assertNull(notes.get(neverExisted))
            assertEquals(emptyList(), notes.history(neverExisted))
        }

    @Test
    fun unpairedSurrogatesBecomeReplacementCharacters() =
        withStore(clock) { store ->
            val notes = store.notes
            val note = notes.create("a\uD83D", "\uDE00b")

            assertEquals("a\uFFFD", note.title)
            assertEquals("\uFFFDb", note.body)
            assertEquals(note, notes.get(note.id))
            // The same text again changes nothing.
            assertEquals(note, notes.edit(note.id, note.revisionId, "a\uD83D", "\uDE00b"))
        }

    @Test
    fun whatIsStoredReadsBackEqualAndCanBeRedelivered() =
        runTest {
            withStore(clock) { store ->
                val revisions = RevisionStore(store.database.revisionQueries)
                checkAll(rawTexts, rawTexts, Arb.set(tagNames, 0..3)) { title, body, tags ->
                    val note = store.notes.create(title, body, tags)

                    assertEquals(title.toStorableText(), note.title)
                    assertEquals(body.toStorableText(), note.body)
                    assertEquals(note, store.notes.get(note.id))
                    // As sync in M3 would: the same revision again must be a no-op.
                    val revision = store.notes.history(note.id).single()
                    revisions.insert(revision.copy())
                    assertEquals(listOf(revision), store.notes.history(note.id))
                }
            }
        }
}
