package io.github.sameerkrmishra.jotter.app.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** When the scheduler syncs, on virtual time, with a stand-in for the backends. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {
    private val timing =
        SyncTiming(
            afterEdit = 5.seconds,
            period = 5.minutes,
            firstBackoff = 30.seconds,
            maxBackoff = 2.minutes,
        )
    private val usb = backend("usb")
    private val dav = backend("dav")

    /** The backends, what each sync ends with, and every round with the backends it synced. */
    private class Backends(
        var all: List<Backend>,
    ) {
        val rounds = mutableListOf<List<String>>()
        val times = mutableListOf<Pair<Long, List<String>>>()
        val failing = mutableMapOf<String, Outcome>()
        var now: () -> Long = { 0 }
        var blockNext: CompletableDeferred<Unit>? = null
        var throwNext: Exception? = null
        var throwAlways = false
        var calls = 0

        suspend fun syncAll(due: (Backend) -> Boolean): List<BackendSync> {
            calls++
            // A scheduler that spins on one virtual instant never lets runTest's timeout fire. An
            // Error gets past its catch of Exception and ends it, so such a test fails at once.
            if (calls > MAX_CALLS) throw AssertionError("scheduler spins")
            check(!throwAlways) { "database is locked" }
            blockNext?.let {
                blockNext = null
                it.await()
            }
            throwNext?.let {
                throwNext = null
                throw it
            }
            val synced = all.filter(due)
            rounds += synced.map { it.id }
            times += now() to synced.map { it.id }
            return synced.map {
                val outcome = failing[it.id] ?: Outcome.SYNCED
                BackendSync(
                    it,
                    Attempt(Instant.fromEpochMilliseconds(0), outcome, "", listOf()),
                    null,
                )
            }
        }
    }

    private fun TestScope.start(backends: Backends): Pair<SyncScheduler, MutableStateFlow<Long>> {
        val scheduler = SyncScheduler(backends::syncAll, timing, testScheduler.timeSource)
        backends.now = { testScheduler.currentTime / MILLIS_PER_SECOND }
        val marks = MutableStateFlow(10L)
        scheduler.start(backgroundScope, marks)
        testScheduler.runCurrent()
        return scheduler to marks
    }

    @Test
    fun itSyncsOnStartAndThenPeriodically() =
        runTest {
            val backends = Backends(listOf(usb, dav))
            start(backends)
            assertEquals(listOf(listOf("usb", "dav")), backends.rounds)
            testScheduler.advanceTimeBy(5.minutes - 1.seconds)
            testScheduler.runCurrent()
            assertEquals(1, backends.rounds.size)
            testScheduler.advanceTimeBy(2.seconds)
            assertEquals(2, backends.rounds.size)
        }

    @Test
    fun anEditSyncsOnceTypingHasStopped() =
        runTest {
            val backends = Backends(listOf(usb))
            val (_, marks) = start(backends)
            marks.value = 11
            testScheduler.advanceTimeBy(3.seconds)
            marks.value = 12
            testScheduler.advanceTimeBy(4.seconds)
            testScheduler.runCurrent()
            assertEquals(1, backends.rounds.size)
            testScheduler.advanceTimeBy(2.seconds)
            assertEquals(2, backends.rounds.size)
            // Nothing new since: no more rounds until the period.
            testScheduler.advanceTimeBy(1.minutes)
            assertEquals(2, backends.rounds.size)
        }

    @Test
    fun whatARoundBringsInCausesOneMoreRound() =
        runTest {
            val backends = Backends(listOf(usb))
            val gate = CompletableDeferred<Unit>()
            backends.blockNext = gate
            val (_, marks) = start(backends)
            // The first round pulls revisions while it runs.
            marks.value = 20
            testScheduler.runCurrent()
            gate.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(1, backends.rounds.size)
            testScheduler.advanceTimeBy(6.seconds)
            assertEquals(2, backends.rounds.size)
            testScheduler.advanceTimeBy(1.minutes)
            assertEquals(2, backends.rounds.size)
        }

    @Test
    fun editsBeforeARoundStartsGoWithIt() =
        runTest {
            val backends = Backends(listOf(usb))
            val (scheduler, marks) = start(backends)
            marks.value = 11
            testScheduler.runCurrent()
            scheduler.syncNow()
            testScheduler.runCurrent()
            assertEquals(2, backends.rounds.size)
            testScheduler.advanceTimeBy(1.minutes)
            assertEquals(2, backends.rounds.size)
        }

    @Test
    fun aFailedBackendIsTriedAgainOnItsOwnLaterAfterEachFailure() =
        runTest {
            val backends = Backends(listOf(usb, dav))
            backends.failing["usb"] = Outcome.INTERRUPTED
            start(backends)
            testScheduler.advanceTimeBy(460.seconds)
            // 30 s, 60 s, then the most, 2 minutes. The period's round at 300 s leaves it out.
            assertEquals(
                listOf(
                    0L to listOf("usb", "dav"),
                    30L to listOf("usb"),
                    90L to listOf("usb"),
                    210L to listOf("usb"),
                    300L to listOf("dav"),
                    330L to listOf("usb"),
                    450L to listOf("usb"),
                ),
                backends.times,
            )
        }

    @Test
    fun onlyAFolderIsTriedEvery30sAndEachBackendWhenItIsDue() =
        runTest {
            val hub =
                Backend(
                    "hub",
                    "hub",
                    UnknownConfig("peer"),
                    Instant.fromEpochMilliseconds(0),
                    null,
                    null,
                )
            val backends = Backends(listOf(usb, hub, dav))
            backends.failing["usb"] = Outcome.UNREACHABLE
            backends.failing["hub"] = Outcome.UNREACHABLE
            start(backends)
            testScheduler.advanceTimeBy(100.seconds)
            // A server that cannot be reached waits longer each time: 30 s, then 60 s.
            assertEquals(
                listOf(
                    0L to listOf("usb", "hub", "dav"),
                    30L to listOf("usb", "hub"),
                    60L to listOf("usb"),
                    90L to listOf("usb", "hub"),
                ),
                backends.times,
            )
        }

    @Test
    fun aRefusedPasswordStopsQuickRetriesButNotSyncNow() =
        runTest {
            val backends = Backends(listOf(usb, dav))
            backends.failing["dav"] = Outcome.UNAUTHORIZED
            val (scheduler, _) = start(backends)
            testScheduler.advanceTimeBy(100.seconds)
            // No 30 s retry: only the longest wait, as only a new password helps.
            assertEquals(listOf(0L to listOf("usb", "dav")), backends.times)
            testScheduler.advanceTimeBy(30.seconds)
            assertEquals(120L to listOf("dav"), backends.times.last())
            // The user entered a new password and asks: it syncs at once.
            backends.failing.remove("dav")
            scheduler.syncNow()
            testScheduler.runCurrent()
            assertEquals(130L to listOf("usb", "dav"), backends.times.last())
        }

    // Before the fix these two spun on virtual time and hung the test run.
    @Test
    fun aFailedBackendThatIsRemovedIsNotRetried() =
        runTest(timeout = 10.seconds) {
            val backends = Backends(listOf(usb, dav))
            backends.failing["usb"] = Outcome.UNREACHABLE
            start(backends)
            backends.all = listOf(dav)
            testScheduler.advanceTimeBy(40.seconds)
            // One retry finds it gone, and then nothing until the period.
            assertEquals(2, backends.calls)
            testScheduler.advanceTimeBy(4.minutes)
            assertEquals(2, backends.calls)
        }

    @Test
    fun roundsThatKeepThrowingWaitBetweenTries() =
        runTest(timeout = 10.seconds) {
            val backends = Backends(listOf(usb))
            backends.failing["usb"] = Outcome.UNREACHABLE
            val (scheduler, _) = start(backends)
            backends.throwAlways = true
            testScheduler.advanceTimeBy(100.seconds)
            // The retry at 30 s throws, and each next one waits the first backoff again.
            assertEquals(listOf(0L), backends.times.map { it.first })
            assertEquals(1 + 3, backends.calls)
            assertTrue(
                scheduler.problem.value
                    .orEmpty()
                    .contains("Jotter could not sync: Something unexpected went wrong."),
            )
        }

    @Test
    fun anUnpluggedFolderIsTriedEvery30sAndSyncsSoonAfterItIsBack() =
        runTest {
            val backends = Backends(listOf(usb, dav))
            backends.failing["usb"] = Outcome.UNREACHABLE
            val (_, marks) = start(backends)
            testScheduler.advanceTimeBy(100.seconds)
            assertEquals(listOf(0L, 30L, 60L, 90L), backends.times.map { it.first })
            // An edit meanwhile syncs the others, not the folder that is not due.
            marks.value += 1
            testScheduler.advanceTimeBy(6.seconds)
            assertEquals(105L to listOf("dav"), backends.times.last())

            backends.failing.clear()
            testScheduler.advanceTimeBy(60.seconds)
            assertEquals(120L to listOf("usb"), backends.times.last())
            // Back to normal: nothing more until the period.
            testScheduler.advanceTimeBy(100.seconds)
            assertEquals(120L, backends.times.last().first)
        }

    @Test
    fun theUserCanSyncAFailedBackendAtOnce() =
        runTest {
            val backends = Backends(listOf(usb))
            backends.failing["usb"] = Outcome.UNREACHABLE
            val (scheduler, _) = start(backends)
            scheduler.syncNow()
            testScheduler.runCurrent()
            assertEquals(listOf(listOf("usb"), listOf("usb")), backends.rounds)
        }

    @Test
    fun requestsDuringARoundAreServedByOneMoreRound() =
        runTest {
            val backends = Backends(listOf(usb))
            val gate = CompletableDeferred<Unit>()
            backends.blockNext = gate
            val (scheduler, _) = start(backends)
            assertTrue(scheduler.syncing.value)
            scheduler.syncNow()
            scheduler.syncNow()
            testScheduler.advanceTimeBy(5.minutes)
            gate.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(2, backends.rounds.size)
            assertFalse(scheduler.syncing.value)
        }

    @Test
    fun aFailingRoundDoesNotStopTheScheduler() =
        runTest {
            val backends = Backends(listOf(usb))
            backends.throwNext = IllegalStateException("database gone")
            val (scheduler, _) = start(backends)
            assertEquals(0, backends.rounds.size)
            assertFalse(scheduler.syncing.value)
            assertEquals(
                "Jotter could not sync: Something unexpected went wrong. If it keeps happening, " +
                    "report it and attach the debug log. It tries again.",
                scheduler.problem.value,
            )
            scheduler.syncNow()
            testScheduler.runCurrent()
            assertEquals(1, backends.rounds.size)
            assertNull(scheduler.problem.value)
        }

    @Test
    fun aFailingMarkFlowIsWatchedAgainAndSyncGoesOn() =
        runTest {
            val backends = Backends(listOf(usb))
            val marks = MutableStateFlow(10L)
            var broken = true
            val flaky =
                flow {
                    if (broken) {
                        broken = false
                        error("query failed")
                    }
                    emitAll(marks)
                }
            val scheduler = SyncScheduler(backends::syncAll, timing, testScheduler.timeSource)
            scheduler.start(backgroundScope, flaky)
            testScheduler.runCurrent()
            // It syncs on start all the same, and says why edits are not seen.
            assertEquals(1, backends.rounds.size)
            assertTrue(
                scheduler.problem.value
                    .orEmpty()
                    .contains("Jotter cannot see edits to sync them: Something unexpected"),
            )
            testScheduler.advanceTimeBy(31.seconds)
            marks.value = 11
            testScheduler.advanceTimeBy(6.seconds)
            assertEquals(2, backends.rounds.size)
            assertNull(scheduler.problem.value)
        }

    @Test
    fun cancellingStopsARunningRound() =
        runTest {
            val backends = Backends(listOf(usb))
            backends.blockNext = CompletableDeferred()
            val scheduler = SyncScheduler(backends::syncAll, timing, testScheduler.timeSource)
            val job = scheduler.start(backgroundScope, MutableStateFlow(1L))
            testScheduler.runCurrent()
            assertTrue(scheduler.syncing.value)
            job.cancel()
            testScheduler.runCurrent()
            assertFalse(scheduler.syncing.value)
            assertEquals(0, backends.rounds.size)
        }

    @Test
    fun syncAndWaitReturnsAfterARoundThatStartedLater() =
        runTest {
            val backends = Backends(listOf(usb, dav))
            val gate = CompletableDeferred<Unit>()
            backends.blockNext = gate
            val (scheduler, marks) = start(backends)
            // The round on start is running: it does not count for a call made now.
            val waited = backgroundScope.async { scheduler.syncAndWait() }
            val second = backgroundScope.async { scheduler.syncAndWait() }
            testScheduler.runCurrent()
            // An edit meanwhile goes out with that same round.
            marks.value = 11
            testScheduler.runCurrent()
            gate.complete(Unit)
            testScheduler.runCurrent()
            assertTrue(waited.isCompleted)
            assertTrue(second.isCompleted)
            // Both were served by one more round.
            assertEquals(2, backends.rounds.size)
        }

    @Test
    fun syncAndWaitLeavesOutBackendsThatWait() =
        runTest {
            val backends = Backends(listOf(usb, dav))
            backends.failing["dav"] = Outcome.UNREACHABLE
            val (scheduler, _) = start(backends)
            backends.failing.clear()
            val waited = backgroundScope.async { scheduler.syncAndWait() }
            testScheduler.runCurrent()
            assertTrue(waited.isCompleted)
            assertEquals(listOf("usb"), backends.rounds.last())
        }

    @Test
    fun syncAndWaitIsCancelledWhenTheSchedulerStops() =
        runTest {
            val backends = Backends(listOf(usb))
            backends.blockNext = CompletableDeferred()
            val scheduler = SyncScheduler(backends::syncAll, timing, testScheduler.timeSource)
            val job = scheduler.start(backgroundScope, MutableStateFlow(1L))
            testScheduler.runCurrent()
            // One waits for the next round, behind it a request of the user's.
            val queued = backgroundScope.async { scheduler.syncAndWait() }
            testScheduler.runCurrent()
            scheduler.syncNow()
            job.cancel()
            testScheduler.runCurrent()
            assertTrue(queued.isCancelled)
            // And a call after the stop does not wait for ever.
            val late = backgroundScope.async { scheduler.syncAndWait() }
            testScheduler.runCurrent()
            assertTrue(late.isCancelled)
        }

    @Test
    fun syncAndWaitIsCancelledWhenItsRoundIsStopped() =
        runTest {
            val backends = Backends(listOf(usb))
            val scheduler = SyncScheduler(backends::syncAll, timing, testScheduler.timeSource)
            val job = scheduler.start(backgroundScope, MutableStateFlow(1L))
            testScheduler.runCurrent()
            backends.blockNext = CompletableDeferred()
            val waited = backgroundScope.async { scheduler.syncAndWait() }
            testScheduler.runCurrent()
            assertTrue(scheduler.syncing.value)
            job.cancel()
            testScheduler.runCurrent()
            assertTrue(waited.isCancelled)
        }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /** Far more rounds than any test makes, unless the scheduler spins. */
        const val MAX_CALLS = 1_000
    }

    private fun backend(id: String) =
        Backend(id, id, LocalFolderConfig("/$id"), Instant.fromEpochMilliseconds(0), null, null)
}
