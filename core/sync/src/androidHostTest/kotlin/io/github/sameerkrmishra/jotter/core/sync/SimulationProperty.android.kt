package io.github.sameerkrmishra.jotter.core.sync

internal actual fun simulationProperty(name: String): Long? =
    System.getenv(name.replace('.', '_').uppercase())?.toLong()

internal actual val simulationDefaultCases: Long = 200

internal actual suspend fun simulateEach(seeds: List<Long>): List<Result<SimOutcome>> =
    seeds.map { runCatching { simulate(it) } }
