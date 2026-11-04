package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The other process of [DatabaseExportJvmTest], as the CLI would be beside the app. Arguments: the
 * database path and the number of notes to write. It says `ready` once the database is open,
 * writes the notes, one transaction each, and says `done`.
 */
internal object NoteWriterProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val (path, count) = args
        NoteStore.open(path, "writer", EmptyCoroutineContext).use { store ->
            println(READY)
            repeat(count.toInt()) { store.notes.create("Note $it", "written by the other process") }
        }
        println(DONE)
    }

    const val READY = "ready"
    const val DONE = "done"
}
