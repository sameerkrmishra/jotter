package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.sameerkrmishra.jotter.app.sync.DatabaseExport
import io.github.sameerkrmishra.jotter.app.sync.EXPORT_DATABASE_EXPLAINED
import io.github.sameerkrmishra.jotter.app.sync.ExportOutcome
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext

/**
 * `jotter export-database FILE`: a consistent copy of the whole database in one new file, also
 * while the desktop app runs (ADR-0002). An existing file is replaced only with `--replace`.
 */
internal class ExportDatabaseCommand(
    private val location: DatabaseLocation,
) : CoreCliktCommand(name = "export-database") {
    private val file by argument(name = "FILE", help = "The new file.").path()
    private val replace by option("--replace", help = "Replace FILE if it exists.").flag()

    override fun help(context: Context): String =
        "Copy the notes database to one file, safely also while Jotter runs. " +
            EXPORT_DATABASE_EXPLAINED

    override fun run() {
        if (!Files.exists(location.path)) {
            throw CliktError(
                "jotter: there are no notes to export: ${location.path} does not exist",
            )
        }
        val target = file.toAbsolutePath().toOkioPath()
        val outcome =
            withStore(location.path) { store ->
                runBlocking { DatabaseExport(store, EmptyCoroutineContext).export(target, replace) }
            }
        val failure =
            when (outcome) {
                is ExportOutcome.Exported -> null.also { echo(outcome.message) }
                is ExportOutcome.Exists ->
                    "${outcome.file} exists already, so it was left alone. Choose a new file " +
                        "name, or add --replace to replace it."
                is ExportOutcome.Failed -> outcome.message
            }
        if (failure != null) throw CliktError("jotter: $failure")
    }
}
