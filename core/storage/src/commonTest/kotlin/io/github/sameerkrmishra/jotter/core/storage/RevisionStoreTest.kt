package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RevisionStoreTest {
    @Test
    fun everyShapeOfRevisionRoundTrips() =
        runTest {
            withDatabase { db, _ ->
                val store = RevisionStore(db.revisionQueries)
                checkAll(revisions) { revision ->
                    store.insert(revision)

                    assertEquals(revision, store.get(revision.id))
                    // Note ids are random, so this is the note's only revision.
                    assertEquals(listOf(revision), store.forNote(revision.noteId))
                }
            }
        }

    @Test
    fun insertingTheSameRevisionAgainDoesNothing() =
        runTest {
            withDatabase { db, _ ->
                val store = RevisionStore(db.revisionQueries)
                checkAll(revisions) { revision ->
                    store.insert(revision)
                    store.insert(revision.copy())

                    assertEquals(listOf(revision), store.forNote(revision.noteId))
                }
            }
        }

    @Test
    fun aDifferentRevisionUnderAStoredIdIsRejected() =
        runTest {
            withDatabase { db, _ ->
                val store = RevisionStore(db.revisionQueries)
                checkAll(revisions, timestamps) { revision, timestamp ->
                    store.insert(revision)
                    if (timestamp == revision.timestamp) return@checkAll

                    val error =
                        assertFailsWith<IllegalArgumentException> {
                            store.insert(revision.copy(timestamp = timestamp))
                        }
                    assertEquals(
                        "Revision ${revision.id} is already stored with different content",
                        error.message,
                    )
                    assertEquals(revision, store.get(revision.id))
                }
            }
        }

    @Test
    fun revisionsOfANoteComeInStampOrder() =
        runTest {
            // Few distinct timestamps and devices, so every tiebreaker of Stamp is needed.
            val tiedTimestamps = Arb.bind(Arb.long(0L..1L), Arb.int(0..1), ::HybridTimestamp)
            val devices = Arb.list(deviceIds, 2..2)
            withDatabase { db, _ ->
                val store = RevisionStore(db.revisionQueries)
                checkAll(
                    Arb.list(revisions, 1..8),
                    noteIds,
                    devices,
                    Arb.list(tiedTimestamps, 8..8),
                ) {
                    generated,
                    noteId,
                    devicePool,
                    timestampPool,
                    ->
                    // Kotest's list edge cases repeat elements.
                    val ofOneNote =
                        generated.distinctBy(Revision::id).mapIndexed { index, revision ->
                            revision.copy(
                                noteId = noteId,
                                timestamp = timestampPool[index],
                                deviceId = devicePool[index % 2],
                            )
                        }
                    ofOneNote.forEach(store::insert)

                    assertEquals(ofOneNote.sortedBy(Revision::stamp), store.forNote(noteId))
                }
            }
        }

    @Test
    fun unknownIdsGiveNothing() =
        runTest {
            withDatabase { db, _ ->
                val store = RevisionStore(db.revisionQueries)
                checkAll(revisionIds, noteIds) { id, noteId ->
                    assertNull(store.get(id))
                    assertEquals(emptyList(), store.forNote(noteId))
                }
            }
        }

    @Test
    fun textWithAnUnpairedSurrogateIsRejected() =
        runTest {
            withDatabase { db, _ ->
                val store = RevisionStore(db.revisionQueries)
                checkAll(revisions) { revision ->
                    val content = revision.content as? RevisionContent.Snapshot ?: return@checkAll
                    val brokenTitle = content.copy(title = "\uDE00" + content.title)
                    val brokenBody = content.copy(body = content.body + "\uD83D")

                    assertFailsWith<IllegalArgumentException> {
                        store.insert(revision.copy(content = brokenTitle))
                    }
                    assertFailsWith<IllegalArgumentException> {
                        store.insert(revision.copy(content = brokenBody))
                    }
                    assertNull(store.get(revision.id))
                }
            }
        }
}
