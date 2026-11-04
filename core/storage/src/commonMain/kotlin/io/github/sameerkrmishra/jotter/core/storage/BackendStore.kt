package io.github.sameerkrmishra.jotter.core.storage

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.sameerkrmishra.jotter.core.storage.db.Backend
import io.github.sameerkrmishra.jotter.core.storage.db.JotterDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/**
 * A backend this device syncs with, as stored. [kind] and [settings] are `app:sync`'s, and this
 * module does not read them. They never hold a secret (ADR-0016).
 *
 * @property id Its lasting local name, which the sync tables use as `backend_id`.
 * @property kind What sort of backend it is, such as `local-folder`.
 * @property name A name for people.
 * @property settings Where it is and how to reach it, apart from secrets, in [kind]'s format.
 * @property addedAt When it was added on this device.
 * @property lastAttempt How the last sync with it went, or null if it never ran.
 * @property lastSuccessAt When a sync with it last ran to its end, or null if none did.
 */
public data class BackendRecord(
    val id: String,
    val kind: String,
    val name: String,
    val settings: String,
    val addedAt: Instant,
    val lastAttempt: SyncAttempt? = null,
    val lastSuccessAt: Instant? = null,
)

/**
 * One sync with a backend, as the status screen shows it.
 *
 * @property at When it ended.
 * @property outcome How it ended, in `app:sync`'s terms.
 * @property detail A description for people.
 * @property issues What it could not do, one line for people each.
 */
public data class SyncAttempt(
    val at: Instant,
    val outcome: String,
    val detail: String,
    val issues: List<String>,
)

/**
 * The backends this device syncs with (PLAN.md §4 and §6.5). Get it from [NoteStore.backends].
 *
 * It keeps the list and the outcome of the last sync with each, so that the desktop app and the
 * CLI show the same. What sync knows about a backend's files is in the sync tables, which
 * `core:sync` owns: removing a backend here does not touch them, `SyncEngine.forget` does.
 *
 * All functions block on database I/O: call them off the UI thread.
 */
public class BackendStore internal constructor(
    private val database: JotterDatabase,
    private val queryContext: CoroutineContext,
) {
    private val queries = database.backendQueries

    /** Returns every backend, oldest first. */
    public fun list(): List<BackendRecord> =
        queries.selectBackends().executeAsList().map(::recordOf)

    /** Emits [list] now and whenever it changes through this [NoteStore]. */
    public fun observe(): Flow<List<BackendRecord>> =
        queries
            .selectBackends()
            .asFlow()
            .mapToList(queryContext)
            .distinctUntilChanged()
            .map { rows -> rows.map(::recordOf) }

    /** Returns the backend [id], or null if there is none. */
    public fun get(id: String): BackendRecord? =
        queries.selectBackend(id).executeAsOneOrNull()?.let(::recordOf)

    /**
     * Stores [backend], whose [BackendRecord.lastAttempt] and [BackendRecord.lastSuccessAt] are
     * ignored: a new backend has not synced.
     *
     * @throws IllegalArgumentException if a backend with its id exists.
     * @throws StorageBusyException if another process kept the database locked.
     */
    public fun add(backend: BackendRecord) {
        reportingBusy {
            database.transaction {
                require(get(backend.id) == null) { "A backend ${backend.id} exists" }
                queries.insertBackend(
                    backend.id,
                    backend.kind,
                    backend.name.toStorableText(),
                    backend.settings.toStorableText(),
                    backend.addedAt.toEpochMilliseconds(),
                )
            }
        }
    }

    /**
     * Replaces the settings of the backend [id] with [settings], as when its secret moves to
     * another store. Returns whether it was there.
     *
     * @throws StorageBusyException if another process kept the database locked.
     */
    public fun updateSettings(
        id: String,
        settings: String,
    ): Boolean =
        reportingBusy {
            database.transactionWithResult {
                val found = get(id) != null
                queries.updateSettings(settings.toStorableText(), id)
                found
            }
        }

    /**
     * Removes the backend [id] from the list. Its sync tables stay; see the class. Returns whether
     * it was there.
     *
     * @throws StorageBusyException if another process kept the database locked.
     */
    public fun remove(id: String): Boolean =
        reportingBusy {
            database.transactionWithResult {
                val found = get(id) != null
                queries.deleteBackend(id)
                found
            }
        }

    /**
     * Records how a sync with the backend [id] went. If [succeeded], it also counts as the last
     * success. Does nothing if the backend was removed in the meantime.
     *
     * @throws StorageBusyException if another process kept the database locked.
     */
    public fun recordAttempt(
        id: String,
        attempt: SyncAttempt,
        succeeded: Boolean,
    ) {
        val at = attempt.at.toEpochMilliseconds()
        reportingBusy {
            queries.recordAttempt(
                attemptedAt = at,
                outcome = attempt.outcome,
                detail = attempt.detail.toStorableText(),
                issues = attempt.issues.joinToString(ISSUE_SEPARATOR) { it.oneLine() },
                succeededAt = if (succeeded) at else null,
                id = id,
            )
        }
    }

    private companion object {
        const val ISSUE_SEPARATOR = "\n"

        fun String.oneLine(): String = toStorableText().replace('\n', ' ').replace('\r', ' ')

        fun recordOf(row: Backend): BackendRecord {
            val attempt =
                row.attempted_at?.let { at ->
                    SyncAttempt(
                        at = Instant.fromEpochMilliseconds(at),
                        outcome = row.outcome.orEmpty(),
                        detail = row.outcome_detail.orEmpty(),
                        issues =
                            row.issues
                                ?.takeIf { it.isNotEmpty() }
                                ?.split(ISSUE_SEPARATOR)
                                .orEmpty(),
                    )
                }
            return BackendRecord(
                id = row.id,
                kind = row.kind,
                name = row.name,
                settings = row.settings,
                addedAt = Instant.fromEpochMilliseconds(row.added_at),
                lastAttempt = attempt,
                lastSuccessAt = row.succeeded_at?.let(Instant::fromEpochMilliseconds),
            )
        }
    }
}
