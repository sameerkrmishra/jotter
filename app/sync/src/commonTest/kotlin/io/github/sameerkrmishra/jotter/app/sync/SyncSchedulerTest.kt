package io.github.sameerkrmishra.jotter.app.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        val all: List<Backend>,
    ) {
        val rounds = mutableListOf<List<String>>()
        val times = mutableListOf<Pair<Long, List<String>>>()
        val failing = mutableMapOf<String, Outcome>()
        var now: () -> Long = { 0 }
        var blockNext: CompletableDeferred<Unit>? = null
        var throwNext: Exception? = null

        suspend fun syncAll(due: (Backend) -> Boolean): List<BackendSync> {
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
                "Jotter could not sync: database gone. It tries again.",
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
                        throw IllegalStateException("query failed")
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
                    .contains("query failed"),
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

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }

    private fun backend(id: String) =
        Backend(id, id, LocalFolderConfig("/$id"), Instant.fromEpochMilliseconds(0), null, null)
}
