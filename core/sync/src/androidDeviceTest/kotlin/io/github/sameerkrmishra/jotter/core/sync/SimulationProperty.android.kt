package io.github.sameerkrmishra.jotter.core.sync

import androidx.test.platform.app.InstrumentationRegistry

/**
 * An instrumentation argument, since a device test sees no environment of the host:
 * `-Pandroid.testInstrumentationRunnerArguments.jotter_sim_cases=100`.
 */
internal actual fun simulationProperty(name: String): Long? =
    InstrumentationRegistry.getArguments().getString(name.replace('.', '_'))?.toLong()

/** A small range: the device run checks the platform, the JVM job covers the seeds. */
internal actual val simulationDefaultCases: Long = 30

internal actual suspend fun simulateEach(seeds: List<Long>): List<Result<SimOutcome>> =
    seeds.map { runCatching { simulate(it) } }
