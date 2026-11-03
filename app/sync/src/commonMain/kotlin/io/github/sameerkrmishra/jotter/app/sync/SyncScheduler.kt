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
 */
public data class SyncTiming(
    val afterEdit: Duration = 5.seconds,
    val period: Duration = 5.minutes,
    val firstBackoff: Duration = 30.seconds,
    val maxBackoff: Duration = 30.minutes,
)

/**
 * Decides when the desktop app syncs (PLAN.md §6.3): on start, a little after edits, every
 * [SyncTiming.period], and when the user asks. It runs one round at a time, over every backend in
 * turn, so a database never has two syncs of this process at once. A backend whose sync failed is
 * left out of the following rounds for a while, a longer while after each failure, except when
 * the user asks.
 *
 * Nothing here blocks: the sync engine runs the database work, and each backend its own I/O, on
 * their dispatchers. Cancelling the job that [start] returns stops a sync wherever it is, which
 * `SyncEngine` allows: the next one repeats what did not finish.
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
    private val failures = mutableMapOf<String, Failures>()

    /** Whether a round of syncs is running. */
    public val syncing: StateFlow<Boolean> = running.asStateFlow()

    /**
     * Starts syncing in [scope] until it, or the returned job, is cancelled. Call it once.
     *
     * @param revisionMark A number that rises when a revision is stored, such as
     *   `NoteService.observeRevisionMark()`. A rise starts a sync once it has stopped rising for
     *   [SyncTiming.afterEdit]. Revisions that a sync brought in cause one more round, which
     *   sends on the merges it made and relays them to the other backends.
     */
    public fun start(
        scope: CoroutineScope,
        revisionMark: Flow<Long>,
    ): Job =
        scope.launch {
            launch {
                var started = false
                revisionMark.collect {
                    requests.send(Request.Mark(it))
                    // The first sync starts once the mark is known, so that what it brings in
                    // counts as new and causes the round that sends on its merges.
                    if (!started) {
                        started = true
                        requests.send(Request.Scheduled)
                    }
                }
            }
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

    private suspend fun serve(scope: CoroutineScope) {
        val marks = Marks()
        var pendingEdit: Job? = null
        for (request in requests) {
            when (request) {
                is Request.Mark ->
                    if (marks.isNew(request.mark)) {
                        pendingEdit?.cancel()
                        pendingEdit =
                            scope.launch {
                                delay(timing.afterEdit)
                                requests.send(Request.Scheduled)
                            }
                    }
                Request.Scheduled, Request.Now -> {
                    // Requests that queued up meanwhile are all served by this one round.
                    var force = request == Request.Now
                    while (true) {
                        when (val next = requests.tryReceive().getOrNull() ?: break) {
                            is Request.Mark -> marks.isNew(next.mark)
                            Request.Scheduled -> Unit
                            Request.Now -> force = true
                        }
                    }
                    pendingEdit?.cancel()
                    marks.syncing()
                    round(force)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // The next round tries again; this one must not end it.
    private suspend fun round(force: Boolean) {
        running.value = true
        try {
            val now = timeSource.markNow()
            syncAll { force || failures[it.id]?.let { failed -> failed.until <= now } != false }
                .forEach(::count)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // BackendManager reports every failure of a backend in its result. What is left, such
            // as a database that cannot be read, is the device's: the next round tries again.
        } finally {
            running.value = false
        }
    }

    private fun count(sync: BackendSync) {
        val id = sync.backend.id
        if (sync.attempt.outcome.succeeded) {
            failures.remove(id)
        } else {
            val count = (failures[id]?.count ?: 0) + 1
            failures[id] = Failures(count, timeSource.markNow() + backoff(count))
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

    private sealed interface Request {
        data class Mark(
            val mark: Long,
        ) : Request

        data object Scheduled : Request

        data object Now : Request
    }
}
