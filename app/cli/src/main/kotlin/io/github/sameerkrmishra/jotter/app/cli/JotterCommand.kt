package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import io.github.sameerkrmishra.jotter.app.sync.SecretStores
import io.github.sameerkrmishra.jotter.app.sync.installLinuxLogging
import io.github.sameerkrmishra.jotter.app.sync.linuxSecretStores
import io.github.sameerkrmishra.jotter.core.storage.DATABASE_ENVVAR
import java.io.Console
import java.io.InputStream
import kotlin.coroutines.EmptyCoroutineContext

/** The secret stores of this user, by ADR-0016: the keyring, or the file they chose. */
private fun linuxStores(readEnvvar: (String) -> String?): SecretStores =
    linuxSecretStores(readEnvvar, System.getProperty("user.home"), EmptyCoroutineContext)

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
 * @param secrets Where backend secrets are kept, for the environment given. Tests replace it.
 * @param terminal The terminal a password is read from, or null if there is none.
 */
internal class JotterCommand(
    stdin: InputStream,
    argv: List<String>,
    secrets: ((String) -> String?) -> SecretStores = ::linuxStores,
    terminal: (() -> Console?)? = null,
) : CoreCliktCommand(name = "jotter") {
    private val location = DatabaseLocation()
    private val sync =
        SyncSetup(
            location,
            secrets,
            if (terminal == null) Passwords(stdin) else Passwords(stdin, terminal),
        )

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
            SyncCommand(sync),
            BackendsCommand(sync),
            ImportCommand(location),
            ExportCommand(location),
            ExportDatabaseCommand(location),
            DebugLogCommand(sync),
        )
    }

    override fun help(context: Context): String = "Local-first notes, on the command line."

    override fun run() {
        // Before anything that may log. Without HOME there is no log at all.
        installLinuxLogging("cli", currentContext.readEnvvar)
        sync.readEnvvar = currentContext.readEnvvar
        location.path =
            databasePath(databaseOption, currentContext.readEnvvar, System.getProperty("user.home"))
    }
}
