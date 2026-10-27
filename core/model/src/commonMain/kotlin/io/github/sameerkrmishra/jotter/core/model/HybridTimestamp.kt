package io.github.sameerkrmishra.jotter.core.model

/**
 * A hybrid logical clock timestamp: wall-clock milliseconds plus a logical counter that orders
 * events within the same millisecond, or while the wall clock lags behind (see
 * [HybridLogicalClock]).
 *
 * Timestamps are ordered by [physicalMillis], then by [counter].
 *
 * @property physicalMillis Milliseconds since the Unix epoch, never negative. It is the greatest
 *   wall-clock reading known to the clock that issued the timestamp, which may be ahead of that
 *   device's own wall clock.
 * @property counter Distinguishes timestamps with the same [physicalMillis], never negative.
 */
public data class HybridTimestamp(
    val physicalMillis: Long,
    val counter: Int,
) : Comparable<HybridTimestamp> {
    init {
        require(physicalMillis >= 0) { "physicalMillis must not be negative: $physicalMillis" }
        require(counter >= 0) { "counter must not be negative: $counter" }
    }

    /** Orders by [physicalMillis], then by [counter]. Consistent with [equals]. */
    override fun compareTo(other: HybridTimestamp): Int =
        if (physicalMillis != other.physicalMillis) {
            physicalMillis.compareTo(other.physicalMillis)
        } else {
            counter.compareTo(other.counter)
        }

    /**
     * Returns the smallest timestamp greater than this one. The counter grows by one; at
     * [Int.MAX_VALUE] it moves to the next millisecond instead, which keeps the order intact.
     *
     * @throws IllegalArgumentException if this is [MAX], which only a corrupt timestamp can be.
     */
    internal fun successor(): HybridTimestamp {
        require(this != MAX) { "No timestamp follows $this; it is likely corrupt" }
        return if (counter < Int.MAX_VALUE) {
            HybridTimestamp(physicalMillis, counter + 1)
        } else {
            HybridTimestamp(physicalMillis + 1, 0)
        }
    }

    /** Constants for [HybridTimestamp]. */
    public companion object {
        /** The smallest timestamp: where the clock of a device that has issued none starts. */
        public val ZERO: HybridTimestamp = HybridTimestamp(0, 0)

        /**
         * The greatest timestamp. No timestamp follows it, so a clock cannot issue one after it;
         * it only appears in corrupt data.
         */
        public val MAX: HybridTimestamp = HybridTimestamp(Long.MAX_VALUE, Int.MAX_VALUE)
    }
}
