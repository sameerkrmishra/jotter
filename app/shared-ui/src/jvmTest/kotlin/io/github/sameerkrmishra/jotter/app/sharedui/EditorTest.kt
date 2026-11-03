package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertNotNull

/** What the editor's tests share. */
open class EditorTest : ViewModelTest() {
    internal fun Fixture.open(note: Note?): EditorViewModel {
        app.openEditor(note)
        settle()
        return assertNotNull(app.editor)
    }

    internal fun Fixture.editFromOutside(
        note: Note,
        body: String,
    ) = database.fromOutside {
        it.notes.edit(note.id, note.revisionId, note.title, body)
    }

    internal fun Fixture.deleteFromOutside(note: Note) =
        database.fromOutside {
            it.notes.setState(note.id, NoteState.TRASHED)
            it.notes.deleteForever(note.id)
        }
}

/** An IO dispatcher that holds its work until [release], to fix what happens during a write. */
internal class HeldDispatcher : CoroutineDispatcher() {
    private val held = ArrayDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        held.add(block)
    }

    /** Runs what is held now, but not what that work dispatches in turn. */
    fun release() {
        repeat(held.size) { held.removeFirst().run() }
    }
}
