package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import io.github.sameerkrmishra.jotter.interop.keepimport.KeepDocument
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readAttributes

private const val JSON_EXTENSION = ".json"
private const val KEEP_DIRECTORY = "Keep"

/** Far above any Keep note, whose text is limited to some 20,000 characters. */
private const val MAX_FILE_MIB = 64
private const val MAX_FILE_BYTES = MAX_FILE_MIB * 1024L * 1024L

/**
 * The files of a Takeout that `jotter import` will read.
 *
 * @property notes The candidates in a stable order, because which of two documents with one id
 *   fails depends on it.
 * @property ignored The other entries of the directory: Keep's `.html`, attachments and so on.
 */
internal data class KeepFiles(
    val notes: List<Path>,
    val ignored: Int,
)

/** A file that was not read. */
internal data class UnreadableFile(
    val source: String,
    val detail: String,
)

/**
 * Finds the note files at [path], a `Keep` directory or one `.json` file. This is separate from
 * reading them, and comes before opening the database, so that a mistyped path creates nothing.
 *
 * @throws CliktError if [path] is missing, cannot be read, is neither of the two, or is a
 *   directory without any `.json` file.
 */
internal fun findKeepFiles(path: Path): KeepFiles =
    try {
        val attributes = path.readAttributes<BasicFileAttributes>()
        when {
            attributes.isDirectory -> filesInDirectory(path)
            path.isJson() -> KeepFiles(listOf(path), ignored = 0)
            else -> throw CliktError("jotter: not a directory or a .json file: $path")
        }
    } catch (e: NoSuchFileException) {
        throw CliktError("jotter: no such file or directory: $path", e)
    } catch (e: IOException) {
        throw cannotRead(path, e)
    } catch (e: DirectoryIteratorException) {
        // What listing a directory throws for an error after it opened.
        throw cannotRead(path, e)
    }

private fun cannotRead(
    path: Path,
    cause: Exception,
) = CliktError("jotter: cannot read $path", cause)

private fun filesInDirectory(directory: Path): KeepFiles {
    // Paths compare by their bytes, unlike their text, which depends on the locale. The entries
    // keep those bytes too, so a name that the locale cannot show still opens.
    val (notes, others) =
        directory.listDirectoryEntries().sorted().partition { it.isJson() && !it.isDirectory() }
    if (notes.isEmpty()) throw noNotes(directory)
    return KeepFiles(notes, others.size)
}

/** The likely mistake is the Takeout's top directory: `imported 0` would pass for a success. */
private fun noNotes(directory: Path): CliktError {
    val keep = directory.resolve(KEEP_DIRECTORY)
    val hint = if (keep.isDirectory()) ", try $keep" else ""
    return CliktError("jotter: no .json files in $directory$hint")
}

private fun Path.isJson(): Boolean = name.endsWith(JSON_EXTENSION, ignoreCase = true)

/** Reads files one at a time, and remembers the ones that failed so that the run can go on. */
internal class KeepFileReader {
    private val unreadable = mutableListOf<UnreadableFile>()

    /** The files that have failed so far. */
    val failures: List<UnreadableFile>
        get() = unreadable

    /**
     * Returns the documents of [files]. Each is read when its turn comes, so only one is in memory
     * at a time, and no failure to read one reaches the importer, which would lose its report.
     */
    fun documents(files: List<Path>): Sequence<KeepDocument> =
        files.asSequence().mapNotNull(::read)

    private fun read(file: Path): KeepDocument? =
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                // readString would end the run with an OutOfMemoryError, on every repeat too.
                fail(file, "Larger than $MAX_FILE_MIB MiB")
            } else {
                // Strict about UTF-8, unlike readText, which would change a note without a word.
                KeepDocument(file.name, Files.readString(file))
            }
        } catch (_: CharacterCodingException) {
            fail(file, "Not valid UTF-8")
        } catch (_: IOException) {
            fail(file, "The file could not be read")
        }

    private fun fail(
        file: Path,
        detail: String,
    ): KeepDocument? {
        unreadable += UnreadableFile(file.name, detail)
        return null
    }
}
