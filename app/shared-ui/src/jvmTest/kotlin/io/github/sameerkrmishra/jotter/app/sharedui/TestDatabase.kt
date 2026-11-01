package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively

/** A database in a temporary directory, which [delete] removes. */
@OptIn(ExperimentalPathApi::class)
class TestDatabase {
    private val directory: Path = createTempDirectory("jotter-ui-test")
    val file: String = directory.resolve("notes.db").toString()

    /** The store of the app under test. Flows run their queries on [context]. */
    fun open(context: CoroutineContext): NoteStore = NoteStore.open(file, "Test", context)

    /** Runs [block] on a store of its own, as the CLI would in another process. */
    fun <T> fromOutside(block: (NoteStore) -> T): T =
        NoteStore.open(file, "Test", EmptyCoroutineContext).use(block)

    /** Runs [block] while another connection holds the write lock, so a write fails after 5 s. */
    inline fun <T> whileLocked(block: () -> T): T =
        BundledSQLiteDriver().open(file).use { lock ->
            lock.execSQL("BEGIN IMMEDIATE")
            try {
                block()
            } finally {
                lock.execSQL("ROLLBACK")
            }
        }

    fun delete() {
        directory.deleteRecursively()
    }
}
