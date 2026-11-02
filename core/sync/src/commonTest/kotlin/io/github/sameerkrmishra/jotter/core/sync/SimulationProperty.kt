package io.github.sameerkrmishra.jotter.core.sync

/** The number in the system property or environment variable [name], for longer runs by hand. */
internal expect fun simulationProperty(name: String): Long?

/** How many seeds `check` runs here. Native tests run a debug binary, which is far slower. */
internal expect val simulationDefaultCases: Long
