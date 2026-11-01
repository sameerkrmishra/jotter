package io.github.sameerkrmishra.jotter.interop.markdownexport

import io.github.sameerkrmishra.jotter.core.model.Note
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private const val COMPOSED = "é"
private const val DECOMPOSED = "e\u0301"

class ExportWriterTest {
    private val system = FileSystem.SYSTEM
    private val normalising = NormalisingFileSystem(system)
    private val root =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-export-test-${Random.nextLong().toULong()}"
    private val out = root / "nested" / "out"

    @AfterTest
    fun deleteFiles() {
        system.deleteRecursively(root)
    }

    private fun Path.text(): String = system.read(this) { readUtf8() }

    private fun names(): List<String> = system.list(out).map { it.name }.sorted()

    /** Whether the directory holds exactly the [notes]' current content, under any names. */
    private fun assertHolds(vararg notes: Note) {
        val expected = notes.map { renderMarkdown(it) }.sorted()
        assertEquals(expected, system.list(out).map { it.text() }.sorted())
    }

    private fun export(
        vararg notes: Note,
        fileSystem: FileSystem = normalising,
    ): List<RenamedFile> = writeExport(exportMarkdown(notes.toList()), out, fileSystem)

    @Test
    fun writesEveryFileIntoANewDirectory() {
        val files = exportMarkdown(listOf(noteOf("One", 1, body = "é"), noteOf("Two", 2)))
        assertEquals(emptyList(), writeExport(files, out, system))
        assertEquals(listOf("One.md", "Two.md"), names())
        assertEquals(files[0].content, (out / "One.md").text())
    }

    @Test
    fun anEmptyExportStillCreatesTheDirectory() {
        assertEquals(emptyList(), writeExport(emptyList(), out, system))
        assertEquals(emptyList(), names())
    }

    @Test
    fun exportingAgainReplacesTheNotesOwnFileAndLeavesOthers() {
        export(noteOf("One", 1, body = "old"), fileSystem = system)
        system.write(out / "mine.txt") { writeUtf8("keep") }
        val updated = noteOf("One", 1, body = "new")

        assertEquals(emptyList(), export(updated, fileSystem = system))

        assertEquals(renderMarkdown(updated), (out / "One.md").text())
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
    fun twinsThatTheFileSystemConsidersTheSameFileAreBothWritten() {
        val first = noteOf(DECOMPOSED, 1, body = "first")
        val second = noteOf(COMPOSED, 2, body = "second")

        val renamed = export(first, second)

        assertEquals(
            listOf(RenamedFile("$COMPOSED.md", "$COMPOSED ${noteIdOf(2)}.md")),
            renamed,
        )
        assertEquals(listOf("$COMPOSED ${noteIdOf(2)}.md", "$COMPOSED.md"), names())
        assertHolds(first, second)
    }

    @Test
    fun retryingTheSameExportKeepsBothTwins() {
        val first = noteOf(DECOMPOSED, 1, body = "first")
        val second = noteOf(COMPOSED, 2, body = "second")
        val renamed = export(first, second)

        assertEquals(renamed, export(first, second))

        assertHolds(first, second)
    }

    @Test
    fun reExportingUpdatesBothTwinsInPlace() {
        export(noteOf(DECOMPOSED, 1, body = "old"), noteOf(COMPOSED, 2, body = "old"))
        val before = names()
        val first = noteOf(DECOMPOSED, 1, body = "new one")
        val second = noteOf(COMPOSED, 2, body = "new two")

        export(first, second)

        assertEquals(before, names())
        assertHolds(first, second)
    }

    @Test
    fun aLaterTwinWithAHigherIdDoesNotReplaceTheEarlierNote() {
        val earlier = noteOf(COMPOSED, 1, body = "earlier")
        export(earlier)
        val later = noteOf(DECOMPOSED, 2, body = "later")

        export(earlier, later)

        assertHolds(earlier, later)
    }

    @Test
    fun aLaterTwinWithALowerIdDoesNotReplaceTheEarlierNote() {
        // The later note is staged first and only the target's owner shows the clash.
        val earlier = noteOf(COMPOSED, 2, body = "earlier")
        export(earlier)
        val later = noteOf(DECOMPOSED, 1, body = "later")

        val renamed = export(earlier, later)

        assertEquals(
            listOf(RenamedFile("$DECOMPOSED.md", "$DECOMPOSED ${noteIdOf(1)}.md")),
            renamed,
        )
        assertHolds(earlier, later)
    }

    @Test
    fun aTwinExportedAloneDoesNotReplaceTheEarlierNote() {
        val earlier = noteOf(COMPOSED, 1, body = "earlier")
        export(earlier)
        val later = noteOf(DECOMPOSED, 2, body = "later")

        export(later)

        assertHolds(earlier, later)
    }

    @Test
    fun aRenamedNoteReplacesItsOwnFileOnAFileSystemThatNormalises() {
        export(noteOf(DECOMPOSED, 1, body = "old"))
        val renamed = noteOf(COMPOSED, 1, body = "new")

        assertEquals(emptyList(), export(renamed))

        assertEquals(renderMarkdown(renamed), (out / "$COMPOSED.md").text())
        assertFalse(system.exists(out / "$DECOMPOSED.md"))
    }

    @Test
    fun aFileThatIsNotANoteKeepsItsContent() {
        system.createDirectories(out)
        system.write(out / "One.md") { writeUtf8("mine") }
        val note = noteOf("One", 1)

        val renamed = export(note, fileSystem = system)

        assertEquals(listOf(RenamedFile("One.md", "One ${noteIdOf(1)}.md")), renamed)
        assertEquals("mine", (out / "One.md").text())
        assertEquals(renderMarkdown(note), (out / "One ${noteIdOf(1)}.md").text())
    }

    @Test
    fun anotherNotesFileKeepsItsContentOnAnyFileSystem() {
        val gone = noteOf("Soup", 1, body = "kept")
        export(gone, fileSystem = system)
        val other = noteOf("Soup", 2, body = "new")

        export(other, fileSystem = system)

        assertHolds(gone, other)
    }

    @Test
    fun aDirectoryWithTheNameIsLeftAlone() {
        system.createDirectories(out / "One.md")
        val renamed = export(noteOf("One", 1), fileSystem = system)
        assertEquals("One ${noteIdOf(1)}.md", renamed.single().writtenName)
        assertEquals(emptyList(), system.list(out / "One.md"))
    }

    @Test
    fun whenTheFallbackIsTakenTooACounterFollowsTheId() {
        system.createDirectories(out)
        system.write(out / "One.md") { writeUtf8("mine") }
        system.write(out / "One ${noteIdOf(1)}.md") { writeUtf8("mine too") }
        val note = noteOf("One", 1)

        val renamed = export(note, fileSystem = system)

        assertEquals("${noteIdOf(1)} 1.md", renamed.single().writtenName)
        assertEquals(renderMarkdown(note), (out / "${noteIdOf(1)} 1.md").text())
    }

    @Test
    fun aFailedWriteLeavesThePreviousExportIntact() {
        val old = arrayOf(noteOf("One", 1, body = "old"), noteOf("Two", 2, body = "old"))
        export(*old, fileSystem = system)
        val failing = FailingFileSystem(system, writesLeft = 1)

        assertFailsWith<IOException> {
            export(
                noteOf("One", 1, body = "new"),
                noteOf("Two", 2, body = "new"),
                fileSystem = failing,
            )
        }

        assertEquals(listOf("One.md", "Two.md"), names())
        assertHolds(*old)
    }

    @Test
    fun aFailedMoveLeavesEveryFileWholeAndRemovesTheStagingDirectory() {
        val old = arrayOf(noteOf("One", 1, body = "old"), noteOf("Two", 2, body = "old"))
        export(*old, fileSystem = system)
        val failing = FailingFileSystem(system, movesLeft = 1)
        val new = noteOf("One", 1, body = "new")

        assertFailsWith<IOException> {
            export(new, noteOf("Two", 2, body = "new"), fileSystem = failing)
        }

        assertEquals(listOf("One.md", "Two.md"), names())
        assertHolds(new, old[1])
    }

    // Stores names in composed form, as Android's case-folding storage treats them.
    private class NormalisingFileSystem(
        delegate: FileSystem,
    ) : ForwardingFileSystem(delegate) {
        override fun onPathParameter(
            path: Path,
            functionName: String,
            parameterName: String,
        ): Path = path.toString().replace(DECOMPOSED, COMPOSED).toPath()
    }

    // Fails like a full disk once its allowance is used up.
    private class FailingFileSystem(
        delegate: FileSystem,
        private var writesLeft: Int = Int.MAX_VALUE,
        private var movesLeft: Int = Int.MAX_VALUE,
    ) : ForwardingFileSystem(delegate) {
        override fun sink(
            file: Path,
            mustCreate: Boolean,
        ): Sink {
            if (writesLeft-- == 0) throw IOException("No space left on device")
            return super.sink(file, mustCreate)
        }

        override fun atomicMove(
            source: Path,
            target: Path,
        ) {
            if (movesLeft-- == 0) throw IOException("No space left on device")
            super.atomicMove(source, target)
        }
    }
}
