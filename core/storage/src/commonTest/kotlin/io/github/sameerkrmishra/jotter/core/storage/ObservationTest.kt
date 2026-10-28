package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The flows emit the current result at once, and again after every write through the store. */
class ObservationTest {
    private val shopping = TagName.parse("Shopping")

    @Test
    fun listFlowEmitsAfterWrites() =
        runTest {
            withStore(queryContext = StandardTestDispatcher(testScheduler)) { store ->
                val notes = store.notes
                val emissions = collect(notes.observeList())
                assertEquals(emptyList(), emissions.receive())

                val note = notes.create("", "milk")
                assertEquals(listOf(note), emissions.receive())

                val archived = notes.setState(note.id, NoteState.ARCHIVED)
                assertEquals(emptyList(), emissions.receive())
                assertEquals(listOf(archived), notes.list(NoteFilter(NoteState.ARCHIVED)))
            }
        }

    @Test
    fun noteFlowFollowsOneNoteUntilItIsDeleted() =
        runTest {
            withStore(queryContext = StandardTestDispatcher(testScheduler)) { store ->
                val notes = store.notes
                val note = notes.create("", "milk")
                val emissions = collect(notes.observe(note.id))
                assertEquals(note, emissions.receive())

                val tagged = notes.addTag(note.id, shopping)
                assertEquals(tagged, emissions.receive())

                notes.setState(note.id, NoteState.TRASHED)
                assertEquals(NoteState.TRASHED, emissions.receive()?.state)
                notes.deleteForever(note.id)
                assertNull(emissions.receive())
            }
        }

    @Test
    fun tagFlowEmitsAfterWrites() =
        runTest {
            withStore(queryContext = StandardTestDispatcher(testScheduler)) { store ->
                val notes = store.notes
                val emissions = collect(notes.observeTags())
                assertEquals(emptyList(), emissions.receive())

                val note = notes.create("", "milk", setOf(shopping))
                assertEquals(listOf(TagCount(shopping, 1)), emissions.receive())

                notes.setState(note.id, NoteState.TRASHED)
                assertEquals(emptyList(), emissions.receive())
            }
        }

    /** Collects [flow] into a channel until the test ends. */
    private fun <T> TestScope.collect(flow: Flow<T>): ReceiveChannel<T> {
        val channel = Channel<T>(Channel.UNLIMITED)
        backgroundScope.launch { flow.collect(channel::send) }
        return channel
    }
}
