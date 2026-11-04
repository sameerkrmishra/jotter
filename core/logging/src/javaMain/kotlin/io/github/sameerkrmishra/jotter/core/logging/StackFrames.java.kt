package io.github.sameerkrmishra.jotter.core.logging

internal actual fun stackFrames(failure: Throwable): List<String> =
    failure.stackTrace.map { it.toString() }
