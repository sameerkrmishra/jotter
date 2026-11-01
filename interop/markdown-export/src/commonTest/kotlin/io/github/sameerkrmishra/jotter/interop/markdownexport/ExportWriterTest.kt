package io.github.sameerkrmishra.jotter.interop.markdownexport

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ExportWriterTest {
    private val system = FileSystem.SYSTEM
    private val root =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-export-test-${Random.nextLong().toULong()}"
    private val out = root / "nested" / "out"

    @AfterTest
    fun deleteFiles() {
        system.deleteRecursively(root)
    }

    private fun Path.text(): String = system.read(this) { readUtf8() }

    private fun names(): List<String> = system.list(out).map { it.name }.sorted()

    @Test
    fun writesEveryFileIntoANewDirectory() {
        val files = exportMarkdown(listOf(noteOf("One", 1, body = "é"), noteOf("Two", 2)))
        writeExport(files, out, system)
        assertEquals(listOf("One.md", "Two.md"), names())
        assertEquals(files[0].content, (out / "One.md").text())
    }

    @Test
    fun anEmptyExportStillCreatesTheDirectory() {
        writeExport(emptyList(), out, system)
        assertEquals(emptyList(), names())
    }

    @Test
    fun exportingAgainReplacesFilesAndLeavesOthers() {
        writeExport(exportMarkdown(listOf(noteOf("One", 1, body = "old"))), out, system)
        system.write(out / "mine.txt") { writeUtf8("keep") }
        val files = exportMarkdown(listOf(noteOf("One", 1, body = "new")))

        writeExport(files, out, system)

        assertEquals(files.single().content, (out / "One.md").text())
        assertEquals("keep", (out / "mine.txt").text())
    }

    @Test
    fun titlesThatTryToClimbOutStayInTheDirectory() {
        val titles = listOf("..", "../escaped", "/tmp/escaped", "a/../../escaped")
        val notes = titles.mapIndexed { index, title -> noteOf(title, index) }
        writeExport(exportMarkdown(notes), out, system)
        assertEquals(titles.size, names().size)
        assertEquals(listOf("nested"), system.list(root).map { it.name })
        assertEquals(listOf("out"), system.list(root / "nested").map { it.name })
    }

    @Test
    fun refusesToOverwriteANoteThatTheFileSystemConsidersTheSameFile() {
        val notes = listOf(noteOf("e\u0301", 1, body = "first"), noteOf("é", 2, body = "second"))
        val files = exportMarkdown(notes)
        assertEquals(2, files.size)

        assertFailsWith<IOException> { writeExport(files, out, NormalisingFileSystem(system)) }

        assertEquals(1, system.list(out).size)
    }

    @Test
    fun aRenamedNoteReplacesItsFileOnAFileSystemThatNormalises() {
        val fileSystem = NormalisingFileSystem(system)
        writeExport(exportMarkdown(listOf(noteOf("e\u0301", 1, body = "old"))), out, fileSystem)
        val renamed = exportMarkdown(listOf(noteOf("é", 1, body = "new")))

        writeExport(renamed, out, fileSystem)

        assertEquals(renamed.single().content, (out / "é.md").text())
        assertFalse(system.exists(out / "e\u0301.md"))
    }

    // Stores names in composed form, as Android's case-folding storage treats them.
    private class NormalisingFileSystem(
        delegate: FileSystem,
    ) : ForwardingFileSystem(delegate) {
        override fun onPathParameter(
            path: Path,
            functionName: String,
            parameterName: String,
        ): Path = path.toString().replace("e\u0301", "é").toPath()
    }
}
