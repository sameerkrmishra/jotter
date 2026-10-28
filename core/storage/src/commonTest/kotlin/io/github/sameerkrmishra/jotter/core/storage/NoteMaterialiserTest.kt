package io.github.sameerkrmishra.jotter.core.storage

import app.cash.sqldelight.db.SqlDriver
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.Stamp
import io.github.sameerkrmishra.jotter.core.model.Stamped
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.db.JotterDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class NoteMaterialiserTest {
    private val noteId = NoteId.parse("0192e6f0-8b4a-7c3e-9d2f-1a2b3c4d5e6f")
    private val device = DeviceId.parse("5f0c2a9e-1b3d-4e6f-8a7b-9c0d1e2f3a4b")
    private val shopping = TagName.parse("Shopping")
    private val urgent = TagName.parse("urgent")

    private val firstId = RevisionId.parse("0192e6f08b4a7c3e9d2f1a2b3c4d5e01")
    private val firstStamp = Stamp(HybridTimestamp(1_000, 0), device, firstId)
    private val created =
        RevisionContent.Snapshot(
            title = "Groceries",
            body = "- [ ] milk",
            tags = setOf(shopping),
            pinned = Stamped(false, firstStamp),
            state = Stamped(NoteState.ACTIVE, firstStamp),
        )
    private val first =
        Revision(
            id = firstId,
            noteId = noteId,
            parents = emptySet(),
            timestamp = firstStamp.timestamp,
            deviceId = device,
            kind = RevisionKind.User,
            content = created,
        )

    @Test
    fun aNewNoteIsCreatedFromItsFirstRevision() =
        withMaterialiser { store, materialiser, db, _ ->
            store.insert(first)
            materialiser.materialise(first)

            val expected =
                Note(
                    id = noteId,
                    revisionId = first.id,
                    title = "Groceries",
                    body = "- [ ] milk",
                    tags = setOf(shopping),
                    isPinned = false,
                    state = NoteState.ACTIVE,
                    createdAt = Instant.fromEpochMilliseconds(1_000),
                    updatedAt = HybridTimestamp(1_000, 0),
                )
            assertEquals(expected, db.readNote(noteId))
        }

    @Test
    fun aLaterHeadReplacesTheContentButKeepsTheCreationTime() =
        withMaterialiser { store, materialiser, db, _ ->
            store.insert(first)
            materialiser.materialise(first)

            val pinStamp = Stamp(HybridTimestamp(5_000, 3), device, first.id)
            val edited =
                created.copy(
                    title = "",
                    body = "- [x] milk",
                    tags = setOf(urgent),
                    pinned = Stamped(true, pinStamp),
                    state = Stamped(NoteState.ARCHIVED, pinStamp),
                )
            val second = nextRevision(first, HybridTimestamp(5_000, 3), edited)
            store.insert(second)
            materialiser.materialise(second)

            val note = db.readNote(noteId)
            assertEquals(second.id, note?.revisionId)
            assertEquals("", note?.title)
            assertEquals("- [x] milk", note?.body)
            assertEquals(setOf(urgent), note?.tags)
            assertEquals(true, note?.isPinned)
            assertEquals(NoteState.ARCHIVED, note?.state)
            assertEquals(Instant.fromEpochMilliseconds(1_000), note?.createdAt)
            assertEquals(HybridTimestamp(5_000, 3), note?.updatedAt)
        }

    @Test
    fun aTombstoneRemovesTheNoteAndItsTags() =
        withMaterialiser { store, materialiser, db, driver ->
            val tagged = first.copy(content = created.copy(tags = setOf(shopping, urgent)))
            store.insert(tagged)
            materialiser.materialise(tagged)
            assertEquals(2, driver.queryLong("SELECT count(*) FROM note_tag"))

            val tombstone =
                nextRevision(tagged, HybridTimestamp(2_000, 0), RevisionContent.Tombstone)
            store.insert(tombstone)
            materialiser.materialise(tombstone)

            assertNull(db.readNote(noteId))
            assertEquals(0, driver.queryLong("SELECT count(*) FROM note_tag"))
        }

    @Test
    fun aNoteMaterialisedFromALaterRevisionIsCreatedAtItsFirstRevision() =
        withMaterialiser { store, materialiser, db, _ ->
            // As after sync in M3: the note row is new, but its history is older.
            val second =
                nextRevision(first, HybridTimestamp(7_000, 0), created.copy(tags = emptySet()))
            store.insert(first)
            store.insert(second)
            materialiser.materialise(second)

            val note = db.readNote(noteId)
            assertEquals(Instant.fromEpochMilliseconds(1_000), note?.createdAt)
            assertEquals(emptySet(), note?.tags)
        }

    /** A user revision on top of [parent]. */
    private fun nextRevision(
        parent: Revision,
        timestamp: HybridTimestamp,
        content: RevisionContent,
    ): Revision =
        parent.copy(
            id = RevisionId.parse("0192e6f08b4a7c3e9d2f1a2b3c4d5e02"),
            parents = setOf(parent.id),
            timestamp = timestamp,
            content = content,
        )

    private fun withMaterialiser(
        block: (RevisionStore, NoteMaterialiser, JotterDatabase, SqlDriver) -> Unit,
    ) = withDatabase { db, driver ->
        block(RevisionStore(db.revisionQueries), NoteMaterialiser(db.noteQueries), db, driver)
    }
}

/** Reads the materialised note [id], or null if there is no `note` row for it. */
internal fun JotterDatabase.readNote(id: NoteId): Note? =
    noteQueries.selectNote(id.toString(), ::noteFromRow).executeAsOneOrNull()
