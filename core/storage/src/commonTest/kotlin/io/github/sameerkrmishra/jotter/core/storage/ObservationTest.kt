package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun queriesRunOnTheQueryContext() =
        runTest {
            val queryContext = RecordingDispatcher(StandardTestDispatcher(testScheduler))
            withStore(queryContext = queryContext) { store ->
                val emissions = collect(store.notes.observeList())
                assertEquals(emptyList(), emissions.receive())
                store.notes.create("", "milk")
                emissions.receive()

                assertTrue(queryContext.dispatches >= 2, "${queryContext.dispatches} dispatches")
            }
        }

    @Test
    fun anUnrelatedWriteEmitsNothingNew() =
        runTest {
            withStore(queryContext = StandardTestDispatcher(testScheduler)) { store ->
                val notes = store.notes
                val watched = notes.create("", "milk")
                val other = notes.create("", "bread")
                val emissions = collect(notes.observe(watched.id))
                assertEquals(watched, emissions.receive())

                // Re-runs the query (let it run before the next write), but its result is the same.
                notes.setPinned(other.id, true)
                testScheduler.advanceUntilIdle()
                val tagged = notes.addTag(watched.id, shopping)

                assertEquals(tagged, emissions.receive())
            }
        }

    /** Counts the coroutines dispatched to it, and runs them on [delegate]. */
    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        var dispatches = 0

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    /** Collects [flow] into a channel until the test ends. */
    private fun <T> TestScope.collect(flow: Flow<T>): ReceiveChannel<T> {
        val channel = Channel<T>(Channel.UNLIMITED)
        backgroundScope.launch { flow.collect(channel::send) }
        return channel
    }
}
