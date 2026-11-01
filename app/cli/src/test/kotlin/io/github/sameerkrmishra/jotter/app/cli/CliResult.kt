package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import java.io.InputStream
import java.nio.file.Path

/** What a run of [runJotter] printed, and its exit status. */
internal data class CliResult(
    val stdout: String,
    val stderr: String,
    val statusCode: Int,
)

/** Runs the commands as `main()` does, against the database [file]. */
internal fun runJotter(
    args: List<String>,
    file: Path,
    input: InputStream = InputStream.nullInputStream(),
): CliResult {
    val command = JotterCommand(input)
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val result =
        command.test(args, envvars = mapOf(DATABASE_ENVVAR to file.toString())) {
            // Clikt's test support records through Mordant, which main() does not use and which
            // turns tabs into spaces. Keep the text exactly as main() would print it.
            command.configureContext {
                echoMessage = { _, message, newline, err ->
                    val output = if (err) stderr else stdout
                    output.append(message).append(if (newline) "\n" else "")
                }
            }
            command.parse(it)
        }
    return CliResult(stdout.toString(), stderr.toString(), result.statusCode)
}
