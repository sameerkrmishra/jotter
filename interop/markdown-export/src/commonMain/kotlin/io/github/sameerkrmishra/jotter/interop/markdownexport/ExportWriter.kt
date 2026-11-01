package io.github.sameerkrmishra.jotter.interop.markdownexport

import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * Writes [files] into [directory], creating it if needed. A file from an earlier export with the
 * same name is replaced. Files of notes that no longer match are left alone, and nothing is
 * deleted.
 *
 * [exportMarkdown]'s names differ even ignoring case, but a file system can treat more names as
 * equal than that: Android's storage also ignores Unicode normalisation, so `é` written as one
 * character and as `e` plus an accent are one file there. Common code has no normalisation tables
 * to foresee this, so the writer notices instead of overwriting a note it has just written.
 *
 * @throws IOException if the file system fails, or if it treats two of [files] as the same file.
 *   Files written before the failure stay.
 */
public fun writeExport(
    files: List<ExportedFile>,
    directory: Path,
    fileSystem: FileSystem,
) {
    fileSystem.createDirectories(directory)
    val replaceable = files.filterTo(HashSet()) { fileSystem.exists(directory / it.fileName) }
    for (file in files) {
        val path = directory / file.fileName
        if (file !in replaceable && fileSystem.exists(path)) {
            throw IOException("This file system cannot tell apart another file from $path")
        }
        fileSystem.write(path) { writeUtf8(file.content) }
    }
}
