package io.github.sameerkrmishra.jotter.core.sync

/** The number in the system property or environment variable [name], for longer runs by hand. */
internal expect fun simulationProperty(name: String): Long?

/** How many seeds `check` runs here. Native tests run a debug binary, which is far slower. */
internal expect val simulationDefaultCases: Long

/**
 * Runs [simulate] for each of [seeds] and returns the results in the same order. A platform may
 * run seeds on several threads; each runs whole on one thread, as it would alone.
 */
internal expect suspend fun simulateEach(seeds: List<Long>): List<Result<SimOutcome>>
