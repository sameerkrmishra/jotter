package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import io.github.sameerkrmishra.jotter.core.storage.DATABASE_ENVVAR
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.assertTrue

/** What a run of [runJotter] printed, and its exit status. */
internal data class CliResult(
    val stdout: String,
    val stderr: String,
    val statusCode: Int,
)

/** How a real process of `jotter` ended. */
internal class Exit(
    val status: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * A real process with its output in temporary files of [directory], so that [finish]'s timeout
 * works: reading a pipe would block until end of file, and a full pipe would block the process.
 *
 * @param stdout Where stdout goes instead, for a test about what happens to stdout itself. It then
 *   counts as empty in the [Exit].
 */
internal class JotterProcess(
    builder: ProcessBuilder,
    directory: Path,
    stdout: ProcessBuilder.Redirect? = null,
) {
    private val stdoutFile = Files.createTempFile(directory, "stdout", null)
    private val stderrFile = Files.createTempFile(directory, "stderr", null)
    private val capturesStdout = stdout == null

    /** The running process. */
    val process: Process =
        builder
            .redirectOutput(stdout ?: ProcessBuilder.Redirect.to(stdoutFile.toFile()))
            .redirectError(stderrFile.toFile())
            .start()

    /** Waits for the end, and kills a process that takes more than [timeoutSeconds]. */
    fun finish(timeoutSeconds: Long): Exit {
        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly()
        assertTrue(exited, "jotter did not exit")
        val output = if (capturesStdout) stdoutFile.readText() else ""
        return Exit(process.exitValue(), output, stderrFile.readText())
    }
}

/** Runs the commands as `main()` does, against the database [file]. */
internal fun runJotter(
    args: List<String>,
    file: Path,
    input: InputStream = InputStream.nullInputStream(),
): CliResult {
    val command = JotterCommand(input, args)
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

/** The text of a Takeout document with synthetic content. [more] holds further JSON members. */
internal fun keepNote(
    createdUsec: Long,
    title: String = "Note $createdUsec",
    text: String = "Text of $createdUsec",
    more: String = "",
): String =
    """
    {"title":"$title","textContent":"$text","createdTimestampUsec":$createdUsec,
    "userEditedTimestampUsec":${createdUsec + EDIT_DELAY_USEC}$more}
    """.trimIndent()

private const val EDIT_DELAY_USEC = 5_000_000L
