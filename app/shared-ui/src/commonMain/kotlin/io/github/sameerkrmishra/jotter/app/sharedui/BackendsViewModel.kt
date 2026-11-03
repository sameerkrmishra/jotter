package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.app.sync.Backend
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.Outcome
import io.github.sameerkrmishra.jotter.app.sync.SyncScheduler
import io.github.sameerkrmishra.jotter.app.sync.describe
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal const val SET_UP_TITLE = "Set this folder up for Jotter?"
internal const val REMOVE_TITLE = "Stop syncing with this backend?"
internal const val NOT_CHECKED = "Checking whether it can be reached…"
internal const val NEVER_SYNCED = "Never synced."
internal const val READ_FAILED = "The backends could not be read."

/** What setting up [path] does, for the user to agree to first (ADR-0012). */
internal fun setUpExplanation(path: String): String =
    "$path is not a Jotter store yet. Setting it up creates a hidden file, .jotter-store, in " +
        "it, and the next sync copies the history of all your notes into a folder called " +
        "jotter there. If a USB stick or another drive belongs at this place, plug it in " +
        "first: otherwise your notes are copied onto this computer's own disk instead."

/** What removing [backend] does and does not do. */
internal fun removeExplanation(backend: Backend): String =
    "Jotter stops syncing with ${backend.name} and forgets on this device what it synced " +
        "there. Its files stay in ${backend.config.location}: Jotter never deletes them, and " +
        "other devices can go on syncing there. Adding it again later copies all your notes " +
        "there again."

/**
 * The backend screen (PLAN.md §10, M4): the backends with whether each can be reached now, when
 * it last synced and its issues; adding a folder; removing a backend; and syncing at once. A
 * vanished or unplugged backend shows as not found, never as empty, beside the time of its last
 * success.
 *
 * @param manager The backends.
 * @param scheduler The app's sync scheduler, which syncs at once when asked.
 * @param parent Where it runs, on the UI thread. Cancel it, or call [close], to stop it.
 *   Blocking work runs in [manager], on its own dispatchers.
 * @param clock The time that "synced 5 minutes ago" counts from.
 */
@Stable
@Suppress("TooManyFunctions") // One small function per thing the user can do on the screen.
internal class BackendsViewModel(
    private val manager: BackendManager,
    private val scheduler: SyncScheduler,
    parent: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    // A child of the app's scope, so that close() stops this screen and nothing else.
    private val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))

    /** The backends, oldest first, with how their last sync went. */
    var backends by mutableStateOf(emptyList<Backend>())
        private set

    /** What each backend said when last asked whether it can be reached, by id. */
    var reachability by mutableStateOf(emptyMap<String, Reachability>())
        private set

    /** Whether a sync runs now. */
    var syncing by mutableStateOf(false)
        private set

    /** The folder the user is typing. */
    var folder by mutableStateOf("")
        private set

    /** A folder that is not a store yet, while the user decides whether to set it up. */
    var settingUp by mutableStateOf<String?>(null)
        private set

    /** The backend the user asked to remove, while they confirm. */
    var removing by mutableStateOf<Backend?>(null)
        private set

    /** Whether adding or removing runs, so that it is not started twice. */
    var busy by mutableStateOf(false)
        private set

    /** What keeps the scheduler from syncing, while it lasts, such as an unreadable database. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** A notice for the user, until they dismiss it. */
    var message by mutableStateOf<String?>(null)
        private set

    /** The time "ago" counts from: when the backends were last asked. */
    var now by mutableStateOf(clock.now())
        private set

    init {
        scope.launch {
            manager
                .observe()
                .catch { message = READ_FAILED }
                .collect { backends = it }
        }
        scope.launch {
            scheduler.syncing.collect { running ->
                syncing = running
            }
        }
        scope.launch { scheduler.problem.collect { problem = it } }
        // Each round that ends may have found a backend come or go.
        scope.launch {
            scheduler.syncing
                .drop(1)
                .filter { !it }
                .collect { checkReachability() }
        }
        checkReachability()
    }

    /** Asks every backend whether it can be reached now. */
    fun checkReachability() {
        scope.launch {
            reporting {
                val found = manager.status()
                val back =
                    found.any { status ->
                        status.reachability == Reachability.Reachable &&
                            status.backend.lastAttempt?.outcome == Outcome.UNREACHABLE
                    }
                reachability = found.associate { it.backend.id to it.reachability }
                now = clock.now()
                // A backend that is back syncs at once, without waiting for the next retry.
                if (back && !syncing) scheduler.syncNow()
            }
        }
    }

    /** The user typed in the folder field. */
    fun editFolder(text: String) {
        folder = text
    }

    /** Adds the typed folder, after asking first if it is not a store yet. */
    fun addFolder() {
        add(folder.trim(), setUpNewStore = false)
    }

    /** The user agreed to set up [settingUp]. */
    fun confirmSetUp() {
        val path = settingUp ?: return
        settingUp = null
        add(path, setUpNewStore = true)
    }

    /** The user did not want to set up [settingUp]. Nothing was done. */
    fun cancelSetUp() {
        settingUp = null
    }

    private fun add(
        path: String,
        setUpNewStore: Boolean,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                reporting { added(manager.addLocalFolder(path, setUpNewStore = setUpNewStore)) }
            } finally {
                busy = false
            }
        }
    }

    private fun added(result: AddResult) {
        when (result) {
            is AddResult.Added -> {
                folder = ""
                message = "Added ${result.backend.name}. It syncs now."
                scheduler.syncNow()
                checkReachability()
            }
            is AddResult.NotAStoreYet -> settingUp = result.path
            is AddResult.AlreadyAdded ->
                message = "${result.backend.config.location} is already a backend."
            is AddResult.Refused -> message = result.reason
        }
    }

    /** Asks the user to confirm removing [backend]. */
    fun askToRemove(backend: Backend) {
        removing = backend
    }

    /** The user does not want to remove it after all. */
    fun cancelRemove() {
        removing = null
    }

    /** Removes [removing]. Its files stay. */
    fun confirmRemove() {
        val backend = removing ?: return
        removing = null
        busy = true
        scope.launch {
            try {
                reporting {
                    manager.remove(backend.id)
                    message =
                        "Removed ${backend.name}. Its files stay in ${backend.config.location}."
                }
            } finally {
                busy = false
            }
        }
    }

    /** Syncs every backend now, also one that failed a moment ago. */
    fun syncNow() {
        scheduler.syncNow()
    }

    /** Runs [block], and shows a failure, such as a locked database, instead of throwing it. */
    @Suppress("TooGenericExceptionCaught") // Whatever fails, the screen says so and stays.
    private suspend fun reporting(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "That did not work: ${e.message}"
        }
    }

    /** Removes the notice. */
    fun dismissMessage() {
        message = null
    }

    /** Stops what this view model runs. */
    fun close() {
        scope.cancel()
    }
}

/** The state of [backend] in plain words: whether it can be reached, and when it last synced. */
internal fun BackendsViewModel.statusOf(backend: Backend): BackendLines {
    val reach = reachability[backend.id]
    val attempt = backend.lastAttempt
    return BackendLines(
        reachability = reach?.let(::describe) ?: NOT_CHECKED,
        lastSuccess = backend.lastSuccessAt?.let { "Last synced ${ago(it, now)}." } ?: NEVER_SYNCED,
        lastProblem =
            attempt?.takeIf { !it.outcome.succeeded }?.let {
                "The last try, ${ago(it.at, now)}, did not sync: ${it.detail}"
            },
        issues = attempt?.issues.orEmpty(),
        reachable = reach == Reachability.Reachable,
    )
}

/**
 * What the screen says about one backend.
 *
 * @property reachability Whether it can be reached now.
 * @property lastSuccess When it last synced.
 * @property lastProblem Why the last try failed, if it did.
 * @property issues What the last sync could not do.
 * @property reachable Whether it answered just now.
 */
internal data class BackendLines(
    val reachability: String,
    val lastSuccess: String,
    val lastProblem: String?,
    val issues: List<String>,
    val reachable: Boolean,
)

/** How long before [now] [then] was, in words: "just now", "5 minutes ago", "2 days ago". */
internal fun ago(
    then: Instant,
    now: Instant,
): String {
    val elapsed = now - then
    return when {
        elapsed < 1.minutes -> "just now"
        elapsed < 1.hours -> plural(elapsed.inWholeMinutes, "minute") + " ago"
        elapsed < 1.days -> plural(elapsed.inWholeHours, "hour") + " ago"
        else -> plural(elapsed.inWholeDays, "day") + " ago"
    }
}

private fun plural(
    count: Long,
    unit: String,
) = if (count == 1L) "1 $unit" else "$count ${unit}s"
