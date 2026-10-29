package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The device's hybrid logical clock, which every write reads from and stores in the database. */
class ClockTest {
    private enum class Operation { CREATE, EDIT, TAG, PIN, ARCHIVE }

    @Test
    fun timestampsIncreaseWhateverTheWallClockDoes() =
        runTest {
            val clock = FakeClock(millis = 1_000_000)
            withStore(clock) { store ->
                val written = mutableListOf<HybridTimestamp>()
                // Each step moves the wall clock by up to 5 s either way, then writes.
                val steps =
                    Arb.list(
                        Arb.bind(Arb.long(-5_000L..5_000L), Arb.enum<Operation>(), ::Pair),
                    )
                checkAll(steps) { operations ->
                    var note = store.notes.create("", "")
                    written += note.updatedAt
                    for ((jump, operation) in operations) {
                        clock.millis += jump
                        val next = apply(store.notes, note, operation)
                        if (next.revisionId != note.revisionId) written += next.updatedAt
                        note = next
                    }
                }
                assertTrue(
                    written.zipWithNext().all { (a, b) ->
                        a < b
                    },
                    "Not increasing: $written",
                )
            }
        }

    @Test
    fun theClockSurvivesReopeningAfterTheWallClockWentBack() =
        withTempDir { dir ->
            val clock = FakeClock(millis = 5_000)
            val first = openStore(dir.path, clock).use { it.notes.create("", "first") }

            clock.millis = 1_000
            val second = openStore(dir.path, clock).use { it.notes.create("", "second") }

            assertEquals(HybridTimestamp(5_000, 0), first.updatedAt)
            assertEquals(HybridTimestamp(5_000, 1), second.updatedAt)
        }

    @Test
    fun twoStoresOnOneFileShareTheClock() =
        withTempDir { dir ->
            // As the desktop app and the CLI: one database, two connections, two wall clocks.
            val aheadClock = FakeClock(millis = 10_000)
            val behindClock = FakeClock(millis = 1_000)
            openStore(dir.path, aheadClock).use { ahead ->
                openStore(dir.path, behindClock).use { behind ->
                    val note = ahead.notes.create("", "")
                    val written = mutableListOf(note.updatedAt)
                    repeat(INTERLEAVED_WRITES) { index ->
                        val store = if (index % 2 == 0) behind else ahead
                        written +=
                            store.notes
                                .edit(
                                    note.id,
                                    store.currentRevision(note),
                                    "",
                                    "$index",
                                ).updatedAt
                    }

                    assertEquals(
                        (0..INTERLEAVED_WRITES).map { HybridTimestamp(10_000, it) },
                        written,
                    )
                    assertEquals(ahead.deviceId, behind.deviceId)
                }
            }
        }

    @Test
    fun concurrentWritersFromTwoStoresNeverReuseATimestamp() =
        withTempDir { dir ->
            val clock = FakeClock(millis = 1_000)
            openStore(dir.path, clock).use { first ->
                openStore(dir.path, clock).use { second ->
                    val created =
                        runBlocking {
                            listOf(first, second)
                                .map { store ->
                                    async(testThreads) {
                                        List(CONCURRENT_WRITES) { store.notes.create("", "") }
                                    }
                                }.awaitAll()
                                .flatten()
                        }

                    // Every write read the clock the previous one stored, so with the wall clock
                    // standing still the counter counts up without a gap or a repeat.
                    val timestamps = created.map(Note::updatedAt).sorted()
                    assertEquals(
                        List(2 * CONCURRENT_WRITES) { HybridTimestamp(1_000, it) },
                        timestamps,
                    )
                    assertEquals(2 * CONCURRENT_WRITES, first.notes.list().size)
                }
            }
        }

    private fun apply(
        notes: NoteService,
        note: Note,
        operation: Operation,
    ): Note =
        when (operation) {
            Operation.CREATE -> notes.create("", "")
            Operation.EDIT -> notes.edit(note.id, note.revisionId, "", note.body + "x")
            Operation.TAG -> notes.addTag(note.id, TagName.parse("t${note.tags.size}"))
            Operation.PIN -> notes.setPinned(note.id, !note.isPinned)
            Operation.ARCHIVE -> notes.setState(note.id, NoteState.ARCHIVED)
        }

    private fun NoteStore.currentRevision(note: Note) = checkNotNull(notes.get(note.id)).revisionId

    private companion object {
        const val INTERLEAVED_WRITES = 6
        const val CONCURRENT_WRITES = 50
    }
}
