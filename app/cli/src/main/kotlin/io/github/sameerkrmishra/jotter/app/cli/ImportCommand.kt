package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.sameerkrmishra.jotter.interop.keepimport.KeepImportReport
import io.github.sameerkrmishra.jotter.interop.keepimport.KeepImporter

private const val READ_ERROR = "READ_ERROR"
private const val RUN_AGAIN = "Running the import again is safe: it skips the notes it has"

/**
 * `jotter import`: stores the notes of a Google Takeout. This is not a [NoteCommand], because the
 * path is checked before the database is opened, so that a typing error does not create one.
 */
internal class ImportCommand(
    private val location: DatabaseLocation,
) : CoreCliktCommand(name = "import") {
    private val path by argument(
        name = "PATH",
        help = "A Takeout Keep directory, or one .json file from it.",
    ).path()

    override fun help(context: Context): String =
        "Import Google Keep notes. Prints tab-separated counts, and on stderr a line per warning " +
            "and per failure. Safe to repeat: notes it already has are skipped."

    override fun run() {
        val files = findKeepFiles(path)
        val reader = KeepFileReader()
        val report =
            withNotes(location.path) { notes ->
                KeepImporter(notes).import(reader.documents(files.notes))
            }
        report(report, reader.failures, files.ignored)
    }

    private fun report(
        report: KeepImportReport,
        unreadable: List<UnreadableFile>,
        ignored: Int,
    ) {
        val failures = report.failures.size + unreadable.size
        echo(countLine("imported", report.imported.size))
        echo(countLine("skipped", report.skipped.size))
        echo(countLine("ignored", ignored))
        echo(countLine("warnings", report.warnings.size))
        echo(countLine("failures", failures))
        report.warnings.forEach {
            echo(problemLine("warning", it.source, it.kind.name, it.detail), err = true)
        }
        report.failures.forEach {
            echo(problemLine("failure", it.source, it.kind.name, it.detail), err = true)
        }
        unreadable.forEach {
            echo(problemLine("failure", it.source, READ_ERROR, it.detail), err = true)
        }
        if (report.stopped) {
            throw CliktError("jotter: the database stayed locked, the import stopped. $RUN_AGAIN")
        }
        if (failures > 0) throw CliktError("jotter: not everything was imported. $RUN_AGAIN")
    }
}
