package io.github.sameerkrmishra.jotter.app.desktop

import io.github.sameerkrmishra.jotter.core.storage.DATABASE_ENVVAR
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class DatabaseTest {
    private val directory: Path = createTempDirectory("jotter-desktop-test")

    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    private fun open(vararg environment: Pair<String, String>) =
        openNoteStore(mapOf(*environment)::get, directory.toString(), EmptyCoroutineContext)

    @Test
    fun theEnvironmentVariableNamesTheFileAndItsDirectoryIsCreated() {
        val file = directory.resolve("a/b/notes.db")

        open(DATABASE_ENVVAR to file.toString()).use { store ->
            assertEquals(emptyList(), store.notes.list())
        }

        assertTrue(file.exists())
    }

    @Test
    fun withoutItTheFileIsTheOneTheCliUses() {
        open("HOME" to directory.toString()).close()

        assertTrue(directory.resolve(".local/share/jotter/jotter.db").exists())
    }

    @Test
    fun aDirectoryThatCannotBeCreatedIsReported() {
        val blocker = directory.resolve("file")
        blocker.writeText("in the way")

        val failure =
            assertFailsWith<DatabaseUnavailableException> {
                open(DATABASE_ENVVAR to blocker.resolve("notes.db").toString())
            }

        assertContains(failure.message.orEmpty(), "cannot create the directory")
    }

    @Test
    fun aFileThatIsNotADatabaseIsReportedAndLeftAlone() {
        val file = directory.resolve("notes.txt")
        file.writeText("not a database, but long enough to look like a file header to SQLite")

        val failure =
            assertFailsWith<DatabaseUnavailableException> {
                open(DATABASE_ENVVAR to file.toString())
            }

        assertContains(failure.message.orEmpty(), "cannot open")
        assertContains(file.readText(), "not a database")
    }
}
