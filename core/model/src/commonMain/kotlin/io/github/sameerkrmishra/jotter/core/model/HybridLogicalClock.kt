package io.github.sameerkrmishra.jotter.core.model

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * A hybrid logical clock (Kulkarni et al., 2014): it issues [HybridTimestamp]s that stay close to
 * wall-clock time but, unlike the wall clock, respect causality.
 *
 * Guarantees:
 *
 * - Every timestamp it returns is greater than every timestamp it returned or accepted before,
 *   even if the wall clock goes backwards. While the wall clock lags behind, the
 *   [HybridTimestamp.counter] grows instead.
 * - After [receive] accepts a timestamp from another device, every later timestamp is greater
 *   than it. A user revision made after applying remote revisions is therefore stamped later
 *   than all of them, as ADR-0003 requires.
 * - [receive] moves the clock at most [maxDrift] ahead of the wall-clock reading at that moment.
 *   Without received timestamps, [HybridTimestamp.physicalMillis] follows the greatest
 *   wall-clock reading seen, or the [last] value the clock started from.
 *
 * The clock has no persistence of its own. Store [last] with the data it stamped and pass it in
 * again on the next start; otherwise a wall clock that went backwards while the app was closed
 * could repeat timestamps. Instances are not thread-safe: each one needs a single owner that
 * serialises calls, such as the database write transaction that stores the stamped revision.
 *
 * @param wallClock The device's wall clock. Readings before the Unix epoch count as zero.
 * @param last The greatest timestamp issued or accepted so far, [HybridTimestamp.ZERO] for a new
 *   device.
 * @property maxDrift How far a received timestamp may be ahead of the wall clock. See [receive].
 */
public class HybridLogicalClock(
    private val wallClock: Clock,
    last: HybridTimestamp = HybridTimestamp.ZERO,
    public val maxDrift: Duration = DEFAULT_MAX_DRIFT,
) {
    init {
        require(maxDrift.isPositive() && maxDrift.isFinite()) {
            "maxDrift must be positive and finite: $maxDrift"
        }
    }

    /** The greatest timestamp issued or accepted so far. Persist it to restart the clock. */
    public var last: HybridTimestamp = last
        private set

    /** Returns a new timestamp for a local event, such as a user revision. */
    public fun now(): HybridTimestamp = advance(from = last)

    /**
     * Accepts [remote], the timestamp of an event from another device, and returns a new
     * timestamp for receiving it. That timestamp and every later one is greater than [remote].
     *
     * A [remote] timestamp more than [maxDrift] ahead of the wall clock is rejected: the clock
     * stays unchanged and [ClockDriftException] is thrown. Accepting it would drag this clock,
     * and through it every device it syncs with, that far into the future for good. Clamping
     * it instead would break the guarantee that later timestamps are greater than [remote], so
     * an edit could sort before the revision it edits. Rejecting the timestamp does not reject
     * the revision: the caller can still store it, and should tell the user which device's clock
     * is wrong.
     *
     * @throws ClockDriftException if [remote] is too far ahead of the wall clock.
     */
    public fun receive(remote: HybridTimestamp): HybridTimestamp {
        val wallMillis = wallMillis()
        // Both sides are non-negative, so the subtraction cannot overflow.
        if (remote.physicalMillis - wallMillis > maxDrift.inWholeMilliseconds) {
            throw ClockDriftException(remote, wallMillis, maxDrift)
        }
        return advance(from = maxOf(last, remote), wallMillis)
    }

    // The standard HLC update, in one form for both kinds of event: take the wall clock if it is
    // ahead of everything known, otherwise move just past the greatest known timestamp.
    private fun advance(
        from: HybridTimestamp,
        wallMillis: Long = wallMillis(),
    ): HybridTimestamp {
        val next =
            if (wallMillis > from.physicalMillis) {
                HybridTimestamp(wallMillis, 0)
            } else {
                from.successor()
            }
        last = next
        return next
    }

    private fun wallMillis(): Long = wallClock.now().toEpochMilliseconds().coerceAtLeast(0)

    /** Defaults for [HybridLogicalClock]. */
    public companion object {
        /**
         * The default [maxDrift]: one day. Large enough that a phone whose clock is off by a
         * few hours (a wrong time zone, a missed daylight-saving change) still syncs, small
         * enough to catch clocks set to the wrong year or corrupted timestamps.
         */
        public val DEFAULT_MAX_DRIFT: Duration = 1.days
    }
}

/**
 * Thrown by [HybridLogicalClock.receive] when a remote timestamp is too far ahead of this
 * device's wall clock. Either the sending device's clock or this one is badly wrong.
 *
 * @property remote The rejected timestamp.
 * @property wallMillis This device's wall-clock reading at the time, in epoch milliseconds.
 * @property maxDrift The limit that [remote] exceeded.
 */
public class ClockDriftException(
    public val remote: HybridTimestamp,
    public val wallMillis: Long,
    public val maxDrift: Duration,
) : IllegalArgumentException(
        "Remote timestamp $remote is more than $maxDrift ahead of the wall clock ($wallMillis ms)",
    )
