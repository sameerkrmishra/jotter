package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import io.github.sameerkrmishra.jotter.app.sync.AppInfo
import io.github.sameerkrmishra.jotter.app.sync.DebugReport
import io.github.sameerkrmishra.jotter.app.sync.linuxLogDirectory
import io.github.sameerkrmishra.jotter.core.logging.LogFiles
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** The version in the jar's manifest, or `unknown` when run from classes, as in tests. */
internal fun cliVersion(): String =
    JotterCommand::class.java.`package`?.implementationVersion ?: "unknown"

/** The system, for a debug log: `Linux 6.18.5 (amd64), Java 21.0.9`. */
internal fun jvmPlatform(property: (String) -> String? = System::getProperty): String =
    "${property("os.name")} ${property("os.version")} (${property("os.arch")}), " +
        "Java ${property("java.version")}"

/**
 * `jotter debug-log`: prints the debug log (ADR-0020), so the user sees all of it, and with
 * `--output` writes the same text to a new file to attach to an issue. Nothing is sent anywhere.
 * It works also when the notes cannot be opened, as that is when it is most wanted.
 */
internal class DebugLogCommand(
    private val setup: SyncSetup,
) : CoreCliktCommand(name = "debug-log") {
    private val output by option(
        "--output",
        "-o",
        metavar = "FILE",
        help = "Write it to this new file.",
    ).convert {
        try {
            Path.of(it)
        } catch (_: InvalidPathException) {
            fail("not a valid path: $it")
        }
    }

    override fun help(context: Context): String =
        "Print the debug log to attach to an issue: versions, backend kinds and states, sync " +
            "issues and recent log lines. It holds no note, tag, password or token."

    override fun run() {
        val report =
            DebugReport(
                AppInfo("cli", cliVersion(), jvmPlatform()),
                linuxLogDirectory(setup.readEnvvar)?.let(::LogFiles),
            )
        val text =
            try {
                setup.withBackends { report.build(it) }
            } catch (e: CliktError) {
                runBlocking { report.build(null, e.message.orEmpty().removePrefix("jotter: ")) }
            }
        val file = output
        if (file == null) {
            echo(text, trailingNewline = false)
        } else {
            write(file, text)
            echo(
                "Wrote the debug log to $file: ${text.lines().size} lines, exactly what " +
                    "jotter debug-log prints. Read it before you attach it to an issue.",
            )
        }
    }

    private fun write(
        file: Path,
        text: String,
    ) {
        try {
            Files.writeString(file, text, StandardOpenOption.CREATE_NEW)
        } catch (_: FileAlreadyExistsException) {
            throw CliktError("jotter: $file exists already; choose a new file name")
        } catch (e: IOException) {
            throw CliktError(
                "jotter: cannot write $file. Check that its folder exists and that you may " +
                    "write there.",
                e,
            )
        }
    }
}
