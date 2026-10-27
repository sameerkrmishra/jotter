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
 * - Every timestamp [now] returns is greater than [last], that is, than every timestamp the
 *   clock issued or observed before, even if the wall clock goes backwards. While the wall clock
 *   lags behind, the [HybridTimestamp.counter] grows instead.
 * - After [observe] accepts a timestamp from another device, every later timestamp is greater
 *   than it. A user revision made after applying remote revisions is therefore stamped later
 *   than all of them.
 * - `now(after = parent)` is always greater than `parent`, as ADR-0003 requires of a user
 *   revision and its parent, even when the parent's timestamp is too far ahead to observe.
 * - When the clock's [HybridTimestamp.physicalMillis] jumps ahead, it lands at most [maxDrift]
 *   ahead of the wall-clock reading at that moment, plus 1 ms if the counter overflowed at the
 *   same time (see [HybridTimestamp]: a full counter moves to the next millisecond).
 *
 * **Persistence and sharing.** The clock has no persistence of its own, and the app and the
 * CLI share one database and one device id. So [last] must be read from the database and
 * written back in the same write transaction that stores the revisions it stamped. The simplest
 * way is to build a clock per write transaction from the persisted value. Otherwise two
 * processes could issue the same timestamp, or a wall clock that went backwards while the app
 * was closed could repeat one. For the same reason instances are not thread-safe.
 *
 * @param wallClock The device's wall clock. Readings before the Unix epoch count as zero.
 * @param last The greatest timestamp issued or observed so far, as persisted, or
 *   [HybridTimestamp.ZERO] for a new device.
 * @property maxDrift How far ahead of the wall clock an observed timestamp may be. See [observe].
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

    /** The greatest timestamp issued or observed so far. Persist it after every use. */
    public var last: HybridTimestamp = last
        private set

    /**
     * Returns a new timestamp for a local event, such as a user revision, greater than [last].
     *
     * If [after] is given, typically the timestamp of the revision being edited, the result is
     * also greater than [after]. When [after] is within [maxDrift] of the wall clock, the clock
     * first observes it, so later timestamps are greater too. When it is further ahead, the
     * result is [after]'s immediate successor but the clock itself stays put: the far-future
     * time spreads only through that note's revisions, not to the whole clock. Later timestamps
     * for other events are then not guaranteed to be greater than this result.
     *
     * @throws IllegalArgumentException if [after] is [HybridTimestamp.MAX], which only corrupt
     *   data can contain, as no timestamp follows it. The clock stays unchanged.
     */
    public fun now(after: HybridTimestamp? = null): HybridTimestamp {
        val wallMillis = wallMillis()
        val known =
            if (after != null &&
                isWithinDrift(after, wallMillis)
            ) {
                maxOf(last, after)
            } else {
                last
            }
        val next =
            if (wallMillis > known.physicalMillis) {
                HybridTimestamp(wallMillis, 0)
            } else {
                known.successor()
            }
        val result = if (after != null && after >= next) after.successor() else next
        last = next
        return result
    }

    /**
     * Records [remote], the timestamp of an event from another device, so that every later
     * timestamp from [now] is greater than it.
     *
     * A [remote] timestamp more than [maxDrift] ahead of the wall clock is rejected: the clock
     * stays unchanged and [ClockDriftException] is thrown. Accepting it would drag this clock,
     * and through it every device it syncs with, that far into the future for good. Rejecting
     * the timestamp does not reject the revision: the caller still stores it, should tell the
     * user which device's clock is wrong, and edits on top of it use `now(after = ...)`.
     *
     * @throws ClockDriftException if [remote] is too far ahead of the wall clock.
     */
    public fun observe(remote: HybridTimestamp) {
        val wallMillis = wallMillis()
        if (!isWithinDrift(remote, wallMillis)) {
            throw ClockDriftException(remote, wallMillis, maxDrift)
        }
        last = maxOf(last, remote)
    }

    // Both sides are non-negative, so the subtraction cannot overflow.
    private fun isWithinDrift(
        timestamp: HybridTimestamp,
        wallMillis: Long,
    ): Boolean = timestamp.physicalMillis - wallMillis <= maxDrift.inWholeMilliseconds

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
 * Thrown by [HybridLogicalClock.observe] when a remote timestamp is too far ahead of this
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
