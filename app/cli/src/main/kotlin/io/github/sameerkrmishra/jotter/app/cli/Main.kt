package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import kotlin.system.exitProcess as exitJvm

/**
 * The `jotter` command. Everything else is in [JotterCommand], where tests reach it.
 *
 * `clikt-core` leaves the process to the application: by default it sees no environment variables,
 * prints errors on stdout and exits with status 0 after a failure. Clikt's test support hides all
 * three, so `MainTest` covers them. `System.out` keeps a failed write to itself, so that is
 * checked at the end: a script must not take lost output for a success.
 */
public fun main(args: Array<String>) {
    JotterCommand(System.`in`)
        .context {
            readEnvvar = System::getenv
            exitProcess = { exitJvm(it) }
            echoMessage = { _, message, newline, err ->
                val stream = if (err) System.err else System.out
                if (newline) stream.println(message) else stream.print(message)
            }
        }.main(args)
    if (System.out.checkError()) {
        System.err.println("jotter: cannot write to stdout")
        exitJvm(1)
    }
}
