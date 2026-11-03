package io.github.sameerkrmishra.jotter.app.android

import io.github.sameerkrmishra.jotter.app.sync.Backend
import io.github.sameerkrmishra.jotter.app.sync.BackendSync
import io.github.sameerkrmishra.jotter.app.sync.SyncScheduler
import io.github.sameerkrmishra.jotter.app.sync.SyncTiming
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the Android app asks for syncs and jobs, on virtual time, with stand-in jobs. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSyncTest {
    private class Jobs : SyncJobs {
        val periodic = mutableListOf<Boolean>()
        var soon = 0

        override fun periodic(any: Boolean) {
            periodic += any
        }

        override fun soon() {
            soon++
        }
    }

    private class World(
        scope: TestScope,
        backends: kotlinx.coroutines.flow.Flow<Boolean> = MutableStateFlow(true),
    ) {
        var rounds = 0
        val jobs = Jobs()
        val scheduler =
            SyncScheduler(
                { _: (Backend) -> Boolean ->
                    rounds++
                    emptyList<BackendSync>()
                },
                SyncTiming(),
                scope.testScheduler.timeSource,
            )
        val sync =
            AndroidSync(scheduler, MutableStateFlow(1L), backends, jobs, scope.backgroundScope)
    }

    @Test
    fun theSchedulerStartsOnceAndComingBackSyncsAgain() =
        runTest {
            val world = World(this)
            assertTrue(world.sync.start())
            assertFalse(world.sync.start())
            testScheduler.runCurrent()
            assertEquals(1, world.rounds, "the sync on start")
            world.sync.foreground()
            testScheduler.runCurrent()
            assertEquals(2, world.rounds)
            val waited = backgroundScope.async { world.sync.syncAndWait() }
            testScheduler.runCurrent()
            assertTrue(waited.isCompleted)
            assertEquals(3, world.rounds)
        }

    @Test
    fun comingToTheScreenFirstStartsIt() =
        runTest {
            val world = World(this)
            world.sync.foreground()
            testScheduler.runCurrent()
            assertEquals(1, world.rounds)
            assertFalse(world.sync.start())
        }

    @Test
    fun jobsFollowWhetherAnyBackendExists() =
        runTest {
            val any = MutableStateFlow(false)
            val world = World(this, any)
            // Before the backends are read, leaving asks for a sync, to be safe.
            world.sync.soon()
            assertEquals(1, world.jobs.soon)
            world.sync.start()
            testScheduler.runCurrent()
            assertEquals(listOf(false), world.jobs.periodic)
            world.sync.soon()
            assertEquals(1, world.jobs.soon, "nothing to sync")
            any.value = true
            testScheduler.runCurrent()
            assertEquals(listOf(false, true), world.jobs.periodic)
            world.sync.soon()
            assertEquals(2, world.jobs.soon)
        }

    @Test
    fun aFailedReadOfTheBackendsChangesNoJob() =
        runTest {
            val world = World(this, flow { error("database gone") })
            world.sync.start()
            testScheduler.runCurrent()
            assertEquals(emptyList(), world.jobs.periodic)
            world.sync.soon()
            assertEquals(1, world.jobs.soon)
        }
}
