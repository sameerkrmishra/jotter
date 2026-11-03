package io.github.sameerkrmishra.jotter.core.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun simulationProperty(name: String): Long? =
    getenv(name.replace('.', '_').uppercase())?.toKString()?.toLong()

internal actual val simulationDefaultCases: Long = 25

internal actual suspend fun simulateEach(seeds: List<Long>): List<Result<SimOutcome>> =
    seeds.map { runCatching { simulate(it) } }
