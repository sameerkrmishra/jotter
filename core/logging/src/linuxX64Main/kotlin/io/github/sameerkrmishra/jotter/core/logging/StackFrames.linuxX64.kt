package io.github.sameerkrmishra.jotter.core.logging

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
internal actual fun stackFrames(failure: Throwable): List<String> =
    failure.getStackTrace().map { it.trim() }
