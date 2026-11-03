package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.RevisionId

/** How one [SyncEngine.sync] ended. Nothing is lost in any of these: the next cycle goes on. */
public sealed interface SyncResult {
    /** The backend cannot be reached, so nothing was read or written. Retry later. */
    public data class Unreachable(
        /** What the backend's `ping` said. */
        val reachability: Reachability.Unreachable,
    ) : SyncResult

    /**
     * The backend holds a format this version cannot use, or a damaged `format.json`, so nothing
     * was read from it or written to it.
     */
    public data class Incompatible(
        /** Why, for logs and the status screen. */
        val detail: String,
    ) : SyncResult

    /**
     * The backend failed in the middle of the cycle. What was done before is kept, and what was not
     * applied stays unread.
     */
    public data class Interrupted(
        /** The failure. */
        val cause: BlobStoreException,
        /** What was done until then. */
        val report: SyncReport,
    ) : SyncResult

    /** The cycle ran to its end, which still can leave [SyncReport.issues]. */
    public data class Completed(
        /** What was done. */
        val report: SyncReport,
    ) : SyncResult
}

/**
 * What a sync cycle did.
 *
 * @property pushedSegments Segments published on the backend, repairs included.
 * @property appliedSegments Segments applied from the backend.
 * @property newRevisions Revisions this device did not have before.
 * @property merges Automatic merges made. They reach the backend in the next cycle.
 * @property issues What went wrong without stopping the cycle.
 */
public data class SyncReport(
    val pushedSegments: Int,
    val appliedSegments: Int,
    val newRevisions: Int,
    val merges: Int,
    val issues: List<SyncIssue>,
)

/** Why a segment was not applied. It stays unread and is tried again on every sync. */
public enum class SegmentProblem {
    /** Damaged or not a segment: bad gzip, bad JSON, a bad value, or a merge's hash is wrong. */
    CORRUPT,

    /** Written by a newer version of Jotter. An update will apply it. */
    FUTURE_FORMAT,

    /** The backend listed it but could not give it. */
    UNREADABLE,

    /** A number below other segments of the device has no file (yet). */
    MISSING,
}

/** Something a cycle could not do, while it went on with the rest. */
public sealed interface SyncIssue {
    /** Segment [number] of [device] was not applied, for the reason in [problem]. */
    public data class SegmentNotApplied(
        /** The device whose folder holds it. */
        val device: DeviceId,
        /** Its number. */
        val number: Long,
        /** Why it was not applied. */
        val problem: SegmentProblem,
        /** A description for logs. */
        val detail: String,
    ) : SyncIssue

    /** The revision [id] is too large for any segment, so it stays on this device. */
    public data class RevisionTooLarge(
        /** The revision. */
        val id: RevisionId,
    ) : SyncIssue

    /** The revision was kept, but [timestamp] is too far ahead for this device's clock. */
    public data class ClockDrift(
        /** The timestamp. */
        val timestamp: HybridTimestamp,
    ) : SyncIssue
}
