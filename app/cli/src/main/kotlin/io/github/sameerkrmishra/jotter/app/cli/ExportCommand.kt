package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.storage.NoteFilter
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.interop.markdownexport.ExportFilter
import io.github.sameerkrmishra.jotter.interop.markdownexport.ExportedFile
import io.github.sameerkrmishra.jotter.interop.markdownexport.RenamedFile
import io.github.sameerkrmishra.jotter.interop.markdownexport.exportMarkdown
import io.github.sameerkrmishra.jotter.interop.markdownexport.writeExport
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.IOException

/**
 * `jotter export`: writes notes as Markdown files. `interop:markdown-export` exports whatever it
 * is given, the trash too. Here the trash needs asking for, as everywhere else in the CLI.
 */
internal class ExportCommand(
    location: DatabaseLocation,
) : NoteCommand(location, "export") {
    private val tag by option("--tag", help = "Only notes with this tag.").tag()
    private val includeTrash by option("--trash", help = "Include the trash too.").flag()
    private val directory by argument(name = "DIR", help = "Created if needed.").path()

    override fun help(context: Context): String =
        "Export active and archived notes as one Markdown file each, and print how many. " +
            "Files that had to take another name are listed on stderr."

    override fun execute(notes: NoteService) {
        val states =
            if (includeTrash) {
                NoteState.entries.toSet()
            } else {
                setOf(NoteState.ACTIVE, NoteState.ARCHIVED)
            }
        val selected = states.flatMap { notes.list(NoteFilter(it, tag)) }
        val files = exportMarkdown(selected, ExportFilter(states, tag))
        val renamed = write(files)
        echo(countLine("exported", files.size))
        // The export's file names have no control characters, so these stay one line each.
        renamed.forEach { echo("renamed\t${it.plannedName}\t${it.writtenName}", err = true) }
    }

    private fun write(files: List<ExportedFile>): List<RenamedFile> =
        try {
            writeExport(files, directory.toOkioPath(), FileSystem.SYSTEM)
        } catch (e: IOException) {
            throw CliktError("jotter: cannot export to $directory: ${e.message}", e)
        }
}
