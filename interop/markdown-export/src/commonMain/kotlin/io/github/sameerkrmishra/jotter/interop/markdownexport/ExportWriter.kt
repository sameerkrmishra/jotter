package io.github.sameerkrmishra.jotter.interop.markdownexport

import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random
import kotlin.random.nextULong

private const val STAGING_PREFIX = ".jotter-export-"

/**
 * Writes [files] into [directory], creating it if needed, and returns the files that had to take
 * another name, sorted by planned name. No note is ever overwritten by another one, and nothing is
 * deleted.
 *
 * A file already in [directory] is replaced only if it is this same note's, going by the id in its
 * front-matter. Any other file keeps its content, and the note takes its name with the id instead.
 *
 * That also covers names that only the file system considers equal: Android's storage ignores
 * Unicode normalisation, so `é` written as one character and as `e` plus an accent are one file
 * there, and common code has no tables to foresee it. Every file is first written to an empty
 * temporary directory inside [directory], where such a clash shows, and then moved into place
 * atomically. So a full disk or a crash leaves each file either complete and new, or as it was.
 *
 * A file that cannot be read counts as someone else's. Removing the temporary directory is best
 * effort, so a failure there is neither reported nor hides another failure.
 *
 * @throws IOException if the file system fails. Files not moved yet keep their previous content.
 */
public fun writeExport(
    files: List<ExportedFile>,
    directory: Path,
    fileSystem: FileSystem,
): List<RenamedFile> {
    fileSystem.createDirectories(directory)
    val staging = directory / "$STAGING_PREFIX${Random.nextULong()}"
    fileSystem.createDirectory(staging, mustCreate = true)
    try {
        val placed =
            files.sortedBy { it.rank }.map { file ->
                val name = freeNames(file).first { fileSystem.isFree(it, file, staging, directory) }
                fileSystem.write(staging / name) { writeUtf8(file.content) }
                file.fileName to name
            }
        placed.forEach { (_, name) -> fileSystem.atomicMove(staging / name, directory / name) }
        return placed
            .filter { (planned, written) -> planned != written }
            .map { (planned, written) -> RenamedFile(planned, written) }
            .sortedBy { it.plannedName }
    } finally {
        fileSystem.deleteBestEffort(staging)
    }
}

// A leftover directory is harmless. By now the export has either succeeded, and failing it would
// be wrong, or it is failing, and this must not replace the exception that says why.
private fun FileSystem.deleteBestEffort(directory: Path) {
    try {
        deleteRecursively(directory)
    } catch (_: IOException) {
        // Nothing to do; see above.
    }
}

// The counter only matters if a file with the id in its name belongs to something else already.
private fun freeNames(file: ExportedFile): Sequence<String> =
    sequenceOf(file.fileName, file.fallbackName) +
        generateSequence(1) { it + 1 }.map { "${file.noteId} $it$EXTENSION" }

private fun FileSystem.isFree(
    name: String,
    file: ExportedFile,
    staging: Path,
    directory: Path,
): Boolean = !exists(staging / name) && canReplace(directory / name, file)

private fun FileSystem.canReplace(
    path: Path,
    file: ExportedFile,
): Boolean =
    try {
        val metadata = metadataOrNull(path)
        val mark = ownerMark(file.noteId).encodeUtf8()
        metadata == null || (metadata.isRegularFile && read(path) { rangeEquals(0, mark) })
    } catch (_: IOException) {
        // A file that cannot be read cannot be shown to be this note's.
        false
    }
