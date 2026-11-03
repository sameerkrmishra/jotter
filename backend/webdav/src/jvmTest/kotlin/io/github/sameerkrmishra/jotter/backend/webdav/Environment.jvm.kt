package io.github.sameerkrmishra.jotter.backend.webdav

internal actual fun environmentVariable(name: String): String? = System.getenv(name)
