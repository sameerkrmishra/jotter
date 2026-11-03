package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.core.storage.BackendRecord
import io.github.sameerkrmishra.jotter.core.sync.SyncReport
import kotlin.time.Instant

/**
 * A backend this device syncs with.
 *
 * @property id Its lasting local name. A backend added again gets a new one.
 * @property name A name for people, such as the folder's name.
 * @property config Where it is and how to reach it.
 * @property addedAt When it was added on this device.
 * @property lastAttempt How the last sync with it went, from this app or the CLI, or null.
 * @property lastSuccessAt When a sync with it last ran to its end, or null if none did.
 */
public data class Backend(
    val id: String,
    val name: String,
    val config: BackendConfig,
    val addedAt: Instant,
    val lastAttempt: Attempt?,
    val lastSuccessAt: Instant?,
)

/**
 * One sync with a backend, as stored.
 *
 * @property at When it ended.
 * @property outcome How it ended.
 * @property detail What happened, in plain words.
 * @property issues What it could not do, in plain words, one item each.
 */
public data class Attempt(
    val at: Instant,
    val outcome: Outcome,
    val detail: String,
    val issues: List<String>,
)

/** How a sync with one backend ended. */
public enum class Outcome {
    /** It ran to its end. It may still have [Attempt.issues]. */
    SYNCED,

    /** The backend could not be reached, so nothing was read or written. */
    UNREACHABLE,

    /** The backend failed part way. What was done is kept. */
    INTERRUPTED,

    /** The backend holds a format this version cannot use. */
    INCOMPATIBLE,

    /** This version cannot use this kind of backend. */
    UNSUPPORTED,

    /** Something on this device failed, such as the database or the secret store. */
    FAILED,
    ;

    /** Whether the sync ran to its end. */
    public val succeeded: Boolean get() = this == SYNCED
}

/**
 * What one sync with [backend] did.
 *
 * @property backend The backend, as it was before this sync.
 * @property attempt What is stored about it.
 * @property report What the cycle did, for a sync that got as far as the backend.
 */
public data class BackendSync(
    val backend: Backend,
    val attempt: Attempt,
    val report: SyncReport?,
)

/**
 * The state of [backend] now: whether it can be reached, besides how its last sync went.
 *
 * @property backend The backend, with its last sync.
 * @property reachability What it said when asked just now.
 */
public data class BackendStatus(
    val backend: Backend,
    val reachability: Reachability,
) {
    /** [reachability] in plain words. */
    public val reachabilityText: String get() = describe(reachability)
}

internal fun backendOf(record: BackendRecord): Backend =
    Backend(
        id = record.id,
        name = record.name,
        config = BackendKinds.configOf(record.kind, record.settings),
        addedAt = record.addedAt,
        lastAttempt =
            record.lastAttempt?.let {
                Attempt(it.at, outcomeOf(it.outcome), it.detail, it.issues)
            },
        lastSuccessAt = record.lastSuccessAt,
    )

/** An outcome stored by a newer version, which this one does not know, counts as a failure. */
private fun outcomeOf(name: String): Outcome =
    Outcome.entries.firstOrNull { it.name == name } ?: Outcome.FAILED
