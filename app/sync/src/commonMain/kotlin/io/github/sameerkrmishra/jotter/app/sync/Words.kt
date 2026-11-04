package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.core.sync.SegmentProblem
import io.github.sameerkrmishra.jotter.core.sync.SyncIssue
import io.github.sameerkrmishra.jotter.core.sync.SyncReport
import io.github.sameerkrmishra.jotter.core.sync.SyncResult

// What the status screen and `jotter backends` say, in plain words. One place, so that the app and
// the CLI say the same. English only, like the rest of the app for now.

private const val DEVICE_ID_SHOWN = 8

/** Whether a backend can be reached, in plain words. Never says or implies that it is empty. */
public fun describe(reachability: Reachability): String =
    when (reachability) {
        Reachability.Reachable -> "Reachable."
        is Reachability.Unreachable ->
            when (reachability.reason) {
                UnreachableReason.MISSING ->
                    "Not found (${reachability.detail}). If it is on a USB stick or another " +
                        "drive, plug it in. Nothing is synced until it is back."
                UnreachableReason.NOT_INITIALIZED ->
                    "The location is there, but it is not marked as a Jotter store " +
                        "(${reachability.detail}). If a drive is mounted there, it may be " +
                        "unplugged. If the marker was deleted, remove this backend and add it again."
                UnreachableReason.UNAUTHORIZED -> WRONG_PASSWORD
                UnreachableReason.FAILED ->
                    "Cannot be reached: ${reachability.detail.trimEnd('.')}. Check the network, " +
                        "or that the drive or server is there; Jotter tries again by itself."
            }
    }

/**
 * What a hub that refuses this device's token means: the device was unpaired on the PC, or the
 * hub's devices were reset.
 */
internal const val UNPAIRED: String =
    "The PC hub refused this device's pairing token: it was unpaired on the PC. Pair this " +
        "device again on the PC and enter the new token for this backend. Until then Jotter " +
        "tries again only every 30 minutes."

/** [describe], with the words for the kind of backend [config] is. */
internal fun describe(
    reachability: Reachability,
    config: BackendConfig,
): String =
    if (config is PeerConfig) {
        when {
            reachability is Reachability.Unreachable &&
                reachability.reason == UnreachableReason.UNAUTHORIZED -> UNPAIRED
            reachability is Reachability.Unreachable &&
                reachability.reason == UnreachableReason.NOT_INITIALIZED ->
                "The PC hub answers, but its folder is not set up as a Jotter store " +
                    "(${reachability.detail}). Set the hub up again on the PC."
            else -> describe(reachability)
        }
    } else {
        describe(reachability)
    }

/**
 * What a server that refuses the password means, and what to do. Retrying cannot help, so the app
 * tries again only rarely until the user enters a new one.
 */
internal const val WRONG_PASSWORD: String =
    "The server refused the user name or app password. It may have been revoked or mistyped. " +
        "Make a new app password for Jotter in your account's settings and enter it for this " +
        "backend. Until then Jotter tries again only every 30 minutes."

/** How a sync ended, and what it did, in plain words. */
internal fun describe(result: SyncResult): Pair<Outcome, String> =
    when (result) {
        is SyncResult.Completed -> {
            // A file listed and not read means changes may be missing: never call that synced.
            val unread = result.report.issues.count(::isUnreadable)
            if (unread == 0) {
                Outcome.SYNCED to "Synced. ${describe(result.report)}"
            } else {
                Outcome.INCOMPLETE to
                    "Not fully synced: ${files(unread)} could not be read, so what is in " +
                    "${if (unread == 1) "it" else "them"} has not arrived. It is tried again " +
                    "soon. ${describe(result.report)}"
            }
        }
        is SyncResult.Unreachable ->
            if (result.reachability.reason == UnreachableReason.UNAUTHORIZED) {
                Outcome.UNAUTHORIZED to WRONG_PASSWORD
            } else {
                Outcome.UNREACHABLE to describe(result.reachability)
            }
        is SyncResult.Incompatible ->
            Outcome.INCOMPATIBLE to
                "Not synced: ${result.detail}. A newer version of Jotter may be able to use it."
        is SyncResult.Interrupted if isUnauthorized(result.cause) ->
            Outcome.UNAUTHORIZED to "$WRONG_PASSWORD ${describe(result.report)}"
        is SyncResult.Interrupted ->
            Outcome.INTERRUPTED to
                "Stopped part way: ${result.cause.message}. What was done is kept, and the " +
                "next sync goes on. ${describe(result.report)}"
    }

private fun describe(report: SyncReport): String {
    val received = report.newRevisions
    val sent = report.pushedSegments
    return when {
        received == 0 && sent == 0 -> "Nothing new."
        else -> "Received ${changes(received)}, sent ${files(sent)}."
    }
}

private fun isUnauthorized(cause: Throwable) =
    cause is BackendUnavailableException &&
        cause.reachability.reason == UnreachableReason.UNAUTHORIZED

/** A file the backend listed and could not give, which a later try may well read. */
private fun isUnreadable(issue: SyncIssue): Boolean =
    issue is SyncIssue.SegmentNotApplied && issue.problem == SegmentProblem.UNREADABLE

private fun changes(count: Int) = if (count == 1) "1 change" else "$count changes"

private fun files(count: Int) = if (count == 1) "1 file" else "$count files"

/** What a cycle could not do, in plain words, with what it means for the user's notes. */
internal fun describe(issue: SyncIssue): String =
    when (issue) {
        is SyncIssue.SegmentNotApplied -> {
            val file = "File ${issue.number} of device ${shortId(issue.device.toString())}"
            when (issue.problem) {
                SegmentProblem.CORRUPT ->
                    "$file is damaged and was not read. Changes in it arrive only if that " +
                        "device, or another backend, still has them."
                SegmentProblem.FUTURE_FORMAT ->
                    "$file was written by a newer version of Jotter. Update Jotter to read it."
                SegmentProblem.UNREADABLE -> "$file could not be read. It is tried again next time."
                SegmentProblem.MISSING ->
                    "$file is missing from the backend, so earlier changes of some notes may " +
                        "not have arrived. Those notes wait under \"Needs attention\"."
            }
        }
        is SyncIssue.RevisionTooLarge ->
            "A change is too large to sync, so it stays on this device only."
        is SyncIssue.ClockDrift ->
            "A change from another device is dated too far in the future. Check that " +
                "device's clock."
    }

private fun shortId(id: String) = id.take(DEVICE_ID_SHOWN)
