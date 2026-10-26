package io.github.sameerkrmishra.jotter.core.model

import kotlin.time.Clock
import kotlin.time.Instant

/** A wall clock that the test sets by hand. */
class FakeClock(
    var millis: Long = 0,
) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
}
