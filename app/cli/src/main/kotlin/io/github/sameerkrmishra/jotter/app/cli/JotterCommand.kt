package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import io.github.sameerkrmishra.jotter.core.storage.DATABASE_ENVVAR
import java.io.InputStream

/**
 * The root command and the CLI's composition root (ADR-0005): it builds the subcommands and finds
 * the database file, which they open through [withNotes]. A test runs exactly what `main()` runs.
 *
 * It is a [CoreCliktCommand], which prints plain text. Clikt's usual `CliktCommand` prints through
 * the Mordant terminal library, which turns tabs into spaces and adds to startup time.
 *
 * @param stdin Where `jotter add -` reads the note from.
 * @param argv The command line. Clikt no longer tells a command what it was, and `jotter add` must
 *   know whether it has a `--`.
 */
internal class JotterCommand(
    stdin: InputStream,
    argv: List<String>,
) : CoreCliktCommand(name = "jotter") {
    private val location = DatabaseLocation()

    private val databaseOption by option(
        "--db",
        metavar = "FILE",
        envvar = DATABASE_ENVVAR,
        help = "The database file. Default: \$XDG_DATA_HOME/jotter/jotter.db",
    )

    init {
        subcommands(
            AddCommand(location, stdin, argv),
            ListCommand(location),
            ShowCommand(location),
            SearchCommand(location),
            TagsCommand(location),
            ConflictsCommand(location),
            SyncCommand(location),
            BackendsCommand(location),
            ImportCommand(location),
            ExportCommand(location),
        )
    }

    override fun help(context: Context): String = "Local-first notes, on the command line."

    override fun run() {
        location.path =
            databasePath(databaseOption, currentContext.readEnvvar, System.getProperty("user.home"))
    }
}
