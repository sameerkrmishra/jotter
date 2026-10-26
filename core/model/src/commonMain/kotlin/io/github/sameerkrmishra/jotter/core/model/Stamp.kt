package io.github.sameerkrmishra.jotter.core.model

/**
 * The position of a revision, or of a last-writer-wins value, in the one total order that
 * ADR-0003 uses wherever it says "higher stamp" or "last writer": by [timestamp], then by
 * [deviceId], then by [revisionId], the ids compared as unsigned byte strings. Compare with
 * this type only, never with its parts, so that every device orders the same way.
 *
 * The order is total and consistent with [equals]: two stamps compare as equal only if all
 * three parts are equal.
 *
 * @property timestamp The hybrid logical clock timestamp.
 * @property deviceId The device whose clock issued [timestamp].
 * @property revisionId The revision being ordered, or the revision that wrote a
 *   last-writer-wins value. It is the final tiebreaker, because two automatic merges can
 *   inherit the same [timestamp] and [deviceId] from a common parent.
 */
public data class Stamp(
    val timestamp: HybridTimestamp,
    val deviceId: DeviceId,
    val revisionId: RevisionId,
) : Comparable<Stamp> {
    /** Orders by [timestamp], then [deviceId], then [revisionId]. */
    override fun compareTo(other: Stamp): Int {
        val byTimestamp = timestamp.compareTo(other.timestamp)
        if (byTimestamp != 0) return byTimestamp
        val byDevice = deviceId.compareTo(other.deviceId)
        return if (byDevice != 0) byDevice else revisionId.compareTo(other.revisionId)
    }
}

/**
 * A last-writer-wins field value with the [stamp] of the revision that wrote it (ADR-0003).
 *
 * A user revision that changes the field stamps it with its own [Revision.stamp]. Any other
 * revision, including an automatic merge, keeps the stamp of the value it carries over, so
 * a later comparison sees when the value was really written.
 *
 * @property value The field value, e.g. whether the note is pinned.
 * @property stamp When and by which revision [value] was written.
 */
public data class Stamped<out T>(
    val value: T,
    val stamp: Stamp,
)
