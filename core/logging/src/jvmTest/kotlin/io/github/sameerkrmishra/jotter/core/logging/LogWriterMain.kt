package io.github.sameerkrmishra.jotter.core.logging

import okio.Path.Companion.toPath

/**
 * A process of [SharedLogTest]: writes the given number of lines to the log `shared` in the given
 * directory, through a sink of its own, as a CLI command does. Files are small and many are kept,
 * so it rotates often and nothing is deleted.
 */
fun main(args: Array<String>) {
    val sink = RotatingFileSink(args[0].toPath(), "shared", maxFileBytes = 4_096, files = 1_000)
    Logging.install(sink, LogLevel.INFO)
    val log = Logger("writer")
    repeat(args[1].toInt()) { log.info { "line $it " + "x".repeat(100) } }
}
