package io.github.sameerkrmishra.jotter.interop.markdownexport

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private const val NO_SPACE = "No space left on device"
private const val STAT_FAILED = "Input/output error"

// More than the writer's 1000 counted names plus the two before them.
private const val MAX_LOOKUPS = 2000

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
    fun aHigherRankedTwinKeepsTheNameEvenIfItsNameSortsLater() {
        // In file name order the decomposed form comes first, in naming order the active note.
        val active = noteOf(COMPOSED, 2, body = "active")
        val trashed = noteOf(DECOMPOSED, 1, body = "trashed", state = NoteState.TRASHED)

        val renamed = export(trashed, active)

        assertEquals(
            listOf(RenamedFile("$DECOMPOSED.md", "$DECOMPOSED ${noteIdOf(1)}.md")),
            renamed,
        )
        assertEquals(renderMarkdown(active), (out / "$COMPOSED.md").text())
        assertHolds(active, trashed)
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

    @Test
    fun aTargetThatCannotBeReadCountsAsAnotherFile() {
        val note = noteOf("One", 1, body = "new")
        export(noteOf("One", 1, body = "old"), fileSystem = system)
        val old = (out / "One.md").text()
        val failing = FailingFileSystem(system, unreadable = out / "One.md")

        val renamed = export(note, fileSystem = failing)

        assertEquals(listOf(RenamedFile("One.md", "One ${noteIdOf(1)}.md")), renamed)
        assertEquals(old, (out / "One.md").text())
        assertEquals(renderMarkdown(note), (out / "One ${noteIdOf(1)}.md").text())
    }

    @Test
    fun aFailedCleanupDoesNotFailAFinishedExport() {
        val note = noteOf("One", 1)
        val failing = FailingFileSystem(system, deletesFail = true)

        assertEquals(emptyList(), export(note, fileSystem = failing))

        assertEquals(renderMarkdown(note), (out / "One.md").text())
    }

    @Test
    fun aFailedCleanupDoesNotHideTheFailureItFollows() {
        val failing = FailingFileSystem(system, writesLeft = 0, deletesFail = true)

        val thrown = assertFailsWith<IOException> { export(noteOf("One", 1), fileSystem = failing) }

        assertEquals(NO_SPACE, thrown.message)
    }

    @Test
    fun aFailingStatOnTargetPathsFailsTheExportInsteadOfHanging() {
        val failing = BrokenLookupFileSystem(system, out, statFails = true)

        val thrown = assertFailsWith<IOException> { export(noteOf("One", 1), fileSystem = failing) }

        assertEquals(STAT_FAILED, thrown.message)
    }

    @Test
    fun aFileSystemWithNoFreeNameFailsTheExportInsteadOfHanging() {
        val failing = BrokenLookupFileSystem(system, out, statFails = false)

        val thrown = assertFailsWith<IOException> { export(noteOf("One", 1), fileSystem = failing) }

        assertEquals("No free file name for note ${noteIdOf(1)}", thrown.message)
        assertEquals(emptyList(), names())
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

    // In [directory], looking up a note's name fails, or every name seems taken and unreadable.
    // An export that keeps looking would never end, so too many lookups fail the test instead:
    // an AssertionError is not an IOException, so nothing on the way out can swallow it.
    private class BrokenLookupFileSystem(
        delegate: FileSystem,
        private val directory: Path,
        private val statFails: Boolean,
    ) : ForwardingFileSystem(delegate) {
        private var lookups = 0

        // The temporary directory sits in the same parent, but is not named like a note.
        private fun isNote(path: Path): Boolean =
            path.parent == directory && path.name.endsWith(".md")

        override fun metadataOrNull(path: Path): FileMetadata? {
            if (!isNote(path)) return super.metadataOrNull(path)
            if (++lookups > MAX_LOOKUPS) throw AssertionError("Still looking after $lookups names")
            if (statFails) throw IOException(STAT_FAILED)
            return FileMetadata(isRegularFile = true)
        }

        override fun source(file: Path): Source {
            if (isNote(file)) throw IOException("Permission denied")
            return super.source(file)
        }
    }

    // Fails like a full disk once its allowance is used up.
    private class FailingFileSystem(
        delegate: FileSystem,
        private var writesLeft: Int = Int.MAX_VALUE,
        private var movesLeft: Int = Int.MAX_VALUE,
        private val unreadable: Path? = null,
        private val deletesFail: Boolean = false,
    ) : ForwardingFileSystem(delegate) {
        override fun source(file: Path): Source {
            if (file == unreadable) throw IOException("Permission denied")
            return super.source(file)
        }

        override fun delete(
            path: Path,
            mustExist: Boolean,
        ) {
            if (deletesFail) throw IOException("Device or resource busy")
            super.delete(path, mustExist)
        }

        override fun sink(
            file: Path,
            mustCreate: Boolean,
        ): Sink {
            if (writesLeft-- == 0) throw IOException(NO_SPACE)
            return super.sink(file, mustCreate)
        }

        override fun atomicMove(
            source: Path,
            target: Path,
        ) {
            if (movesLeft-- == 0) throw IOException(NO_SPACE)
            super.atomicMove(source, target)
        }
    }
}
