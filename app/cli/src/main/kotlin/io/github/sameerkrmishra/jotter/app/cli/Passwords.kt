package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import io.github.sameerkrmishra.jotter.app.sync.Secret
import java.io.ByteArrayOutputStream
import java.io.Console
import java.io.IOException
import java.io.InputStream

/**
 * Reads a password from the terminal without echoing it, or, if there is none, from the first
 * line of stdin, for scripts: `secret-tool lookup ... | jotter backends add webdav ...`. Never
 * from an argument or an environment variable, which other processes and shell histories see
 * (ADR-0016).
 *
 * @param stdin Where it reads when there is no terminal.
 * @param terminal The terminal, or null if stdin or stdout is not one.
 */
internal class Passwords(
    private val stdin: InputStream,
    private val terminal: () -> Console? = ::interactiveConsole,
) {
    /** Reads one password, asking with [prompt] on a terminal. */
    fun read(prompt: String): Secret {
        val console = terminal()
        val text =
            if (console != null) {
                console.readPassword("%s", prompt)?.let(::String)
            } else {
                firstLine()
            }
        if (text.isNullOrEmpty()) throw CliktError("jotter: no password was given")
        return Secret(text)
    }

    /** The first line of stdin, without its line ending, or null at the end of input. */
    private fun firstLine(): String? {
        val bytes = ByteArrayOutputStream()
        try {
            while (true) {
                val next = stdin.read()
                if (next == -1 && bytes.size() == 0) return null
                if (next == -1 || next == '\n'.code) break
                bytes.write(next)
            }
        } catch (e: IOException) {
            throw CliktError("jotter: cannot read stdin: ${e.message}", e)
        }
        return bytes.toString(Charsets.UTF_8).removeSuffix("\r")
    }
}

/**
 * The console if this process talks to a terminal. Before JDK 22 [System.console] is null
 * otherwise; from JDK 22 it may be there anyway, and `isTerminal` says.
 */
private fun interactiveConsole(): Console? {
    val console = System.console() ?: return null
    return try {
        val isTerminal = Console::class.java.getMethod("isTerminal").invoke(console) as Boolean
        console.takeIf { isTerminal }
    } catch (_: NoSuchMethodException) {
        console
    }
}
