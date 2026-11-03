package io.github.sameerkrmishra.jotter.app.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * When [SyncScheduler] syncs.
 *
 * @property afterEdit How long after the last edit a sync starts, so that typing does not sync on
 *   every autosave.
 * @property period How often it syncs anyway, for changes on other devices.
 * @property firstBackoff How long a backend that failed is left alone before the next try. Each
 *   further failure doubles it, up to [maxBackoff].
 * @property maxBackoff The longest wait after failures.
 * @property folderRetry How often a local folder that cannot be reached is tried again, however
 *   often it failed: trying is only a look at the disk, and a USB stick should sync soon after it
 *   is plugged in.
 */
public data class SyncTiming(
    val afterEdit: Duration = 5.seconds,
    val period: Duration = 5.minutes,
    val firstBackoff: Duration = 30.seconds,
    val maxBackoff: Duration = 30.minutes,
    val folderRetry: Duration = 30.seconds,
)

/**
 * Decides when the desktop app syncs (PLAN.md §6.3): on start, a little after edits, every
 * [SyncTiming.period], and when the user asks. It runs one round at a time, over every backend in
 * turn, so a database never has two syncs of this process at once.
 *
 * A backend whose sync failed is left out of the following rounds for a while, a longer while
 * after each failure, except when the user asks; a local folder that cannot be reached is tried
 * every [SyncTiming.folderRetry] instead. When that while is over, the backend is tried on its
 * own, without waiting for the next round, so a backend that is back syncs at once.
 *
 * Nothing here blocks: the sync engine runs the database work, and each backend its own I/O, on
 * their dispatchers. Cancelling the job that [start] returns stops a sync wherever it is, which
 * `SyncEngine` allows: the next one repeats what did not finish. Nothing else stops it: a failure
 * shows in [problem], and the scheduler goes on.
 *
 * @param syncAll Syncs the backends that its argument accepts, such as [BackendManager.syncAll].
 * @param timing When.
 * @param timeSource The clock for waiting out a failure, which tests replace.
 */
public class SyncScheduler(
    private val syncAll: suspend (due: (Backend) -> Boolean) -> List<BackendSync>,
    private val timing: SyncTiming = SyncTiming(),
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val running = MutableStateFlow(false)
    private val trouble = MutableStateFlow<String?>(null)
    private val troubles = MutableStateFlow(Troubles(watching = null, syncing = null))
    private val failures = mutableMapOf<String, Failures>()

    /** Whether a round of syncs is running. */
    public val syncing: StateFlow<Boolean> = running.asStateFlow()

    /**
     * What keeps the scheduler from doing its work, in plain words, such as a database that
     * cannot be read, or null. A backend's own failures are in its outcome instead.
     */
    public val problem: StateFlow<String?> = trouble.asStateFlow()

    /**
     * Starts syncing in [scope] until it, or the returned job, is cancelled. Call it once.
     *
     * @param revisionMark A number that rises when a revision is stored, such as
     *   `NoteService.observeRevisionMark()`. A rise starts a sync once it has stopped rising for
     *   [SyncTiming.afterEdit]. Revisions that a sync brought in cause one more round, which
     *   sends on the merges it made and relays them to the other backends. If the flow fails, it
     *   is collected again after [SyncTiming.firstBackoff], and [problem] says so meanwhile.
     */
    public fun start(
        scope: CoroutineScope,
        revisionMark: Flow<Long>,
    ): Job =
        scope.launch {
            launch { watch(revisionMark) }
            launch {
                while (true) {
                    delay(timing.period)
                    requests.send(Request.Scheduled)
                }
            }
            serve(this)
        }

    /** Syncs every backend as soon as the running round is over, failed ones included. */
    public fun syncNow() {
        requests.trySend(Request.Now)
    }

    @Suppress("TooGenericExceptionCaught") // Whatever fails, sync after edits must come back.
    private suspend fun watch(revisionMark: Flow<Long>) {
        var started = false

        suspend fun startOnce() {
            // The first sync starts once the mark is known, so that what it brings in counts as
            // new and causes the round that sends on its merges.
            if (!started) {
                started = true
                requests.send(Request.Scheduled)
            }
        }
        while (true) {
            try {
                revisionMark.collect {
                    requests.send(Request.Mark(it))
                    report { troubles -> troubles.copy(watching = null) }
                    startOnce()
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text =
                    "Jotter cannot see edits to sync them (${e.message}). It syncs every few " +
                        "minutes meanwhile, and tries again."
                report { it.copy(watching = text) }
                startOnce()
                delay(timing.firstBackoff)
            }
        }
    }

    private suspend fun serve(scope: CoroutineScope) {
        val marks = Marks()
        var pendingEdit: Job? = null
        var retry: Job? = null
        for (request in requests) {
            if (request is Request.Mark) {
                if (marks.isNew(request.mark)) {
                    pendingEdit?.cancel()
                    pendingEdit = scope.launch { later(timing.afterEdit, Request.Scheduled) }
                }
                continue
            }
            // Requests that queued up meanwhile are all served by this one round.
            var kind = request
            while (true) {
                val next = requests.tryReceive().getOrNull() ?: break
                if (next is Request.Mark) marks.isNew(next.mark)
                kind = maxOf(kind, next, compareBy { it.reach })
            }
            if (kind != Request.Retry) {
                pendingEdit?.cancel()
                marks.syncing()
            }
            round(kind)
            retry?.cancel()
            retry = nextRetry()?.let { wait -> scope.launch { later(wait, Request.Retry) } }
        }
    }

    private suspend fun later(
        wait: Duration,
        request: Request,
    ) {
        delay(wait)
        requests.send(request)
    }

    /** How long until the first failed backend is due again, or null if none failed. */
    private fun nextRetry(): Duration? =
        failures.values
            .minOfOrNull { failed -> failed.until - timeSource.markNow() }
            ?.coerceAtLeast(Duration.ZERO)

    @Suppress("TooGenericExceptionCaught") // The next round tries again; this one must not end it.
    private suspend fun round(kind: Request) {
        running.value = true
        try {
            val now = timeSource.markNow()
            syncAll { backend ->
                val failed = failures[backend.id]
                when (kind) {
                    Request.Now -> true
                    Request.Retry -> failed != null && failed.until <= now
                    else -> failed == null || failed.until <= now
                }
            }.forEach(::count)
            report { it.copy(syncing = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // BackendManager reports every failure of a backend in its result. What is left, such
            // as a database that cannot be read, is the device's: the next round tries again.
            val text = "Jotter could not sync: ${e.message}. It tries again."
            report { it.copy(syncing = text) }
        } finally {
            running.value = false
        }
    }

    private fun report(change: (Troubles) -> Troubles) {
        val now = troubles.updateAndGet(change)
        trouble.value = now.watching ?: now.syncing
    }

    /** What keeps sync after edits from working, and what made the last round fail. */
    private data class Troubles(
        val watching: String?,
        val syncing: String?,
    )

    private fun count(sync: BackendSync) {
        val id = sync.backend.id
        val outcome = sync.attempt.outcome
        if (outcome.succeeded) {
            failures.remove(id)
        } else {
            val count = (failures[id]?.count ?: 0) + 1
            val wait =
                if (outcome == Outcome.UNREACHABLE && sync.backend.config is LocalFolderConfig) {
                    timing.folderRetry
                } else {
                    backoff(count)
                }
            failures[id] = Failures(count, timeSource.markNow() + wait)
        }
    }

    private fun backoff(failures: Int): Duration {
        var wait = timing.firstBackoff
        repeat(failures - 1) { wait = minOf(wait * 2, timing.maxBackoff) }
        return minOf(wait, timing.maxBackoff)
    }

    /** The revision marks seen, and the one the last round started at. */
    private class Marks {
        private var latest: Long? = null
        private var synced: Long? = null

        /** Takes [mark] in, and returns whether it is above what the last round started at. */
        fun isNew(mark: Long): Boolean {
            latest = mark
            val since = synced ?: mark.also { synced = it }
            return mark > since
        }

        /** A round starts: everything seen so far goes out with it. */
        fun syncing() {
            synced = latest ?: synced
        }
    }

    private class Failures(
        val count: Int,
        val until: ComparableTimeMark,
    )

    /** What the loop is asked to do. Of two rounds asked for, the one with the higher [reach]. */
    private sealed class Request(
        val reach: Int,
    ) {
        class Mark(
            val mark: Long,
        ) : Request(0)

        /** A retry of the failed backends that are due, and no other. */
        data object Retry : Request(1)

        data object Scheduled : Request(2)

        data object Now : Request(3)
    }
}
