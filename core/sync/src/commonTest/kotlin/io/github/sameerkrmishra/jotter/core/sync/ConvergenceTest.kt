package io.github.sameerkrmishra.jotter.core.sync

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The exit criterion of M3 (PLAN.md §10). `Simulation` says what a case does. */
class ConvergenceTest {
    @Test
    fun devicesConvergeUnderEveryFault(): TestResult =
        runTest(timeout = maxOf(MIN_TIMEOUT_MINUTES, CASES / CASES_PER_MINUTE).minutes) {
            val start = simulationProperty("jotter.sim.start") ?: FIRST_SEED
            val cases = CASES
            val outcomes = (start until start + cases).map { seeded(it) }
            println(
                "Simulated seeds $start until ${start + cases}: " +
                    "${outcomes.sumOf { it.revisions }} revisions, " +
                    "${outcomes.sumOf { it.merges }} merges, " +
                    "${outcomes.sumOf { it.conflictsSeen }} conflicts, " +
                    "${outcomes.sumOf { it.crashes }} crashes, " +
                    "${outcomes.sumOf { it.badStamps }} bad revisions, " +
                    "${outcomes.sumOf { it.restores }} restored backups, " +
                    "${outcomes.sumOf { it.diskRestores }} of them whole disks",
            )
            assertTrue(outcomes.sumOf { it.merges } > cases, "The cases must make merges")
            assertTrue(outcomes.sumOf { it.conflictsSeen } > 0, "The cases must make conflicts")
            assertTrue(outcomes.sumOf { it.crashes } > 0, "The cases must crash")
        }

    /** On every platform: these seeds lie outside what most of them run by default. */
    @Test
    fun theSeedsThatFoundBugsStillPass(): TestResult =
        runTest(timeout = MIN_TIMEOUT_MINUTES.minutes) {
            REGRESSION_SEEDS.forEach { seeded(it) }
        }

    /** Whatever goes wrong in a case, the failure names the seed that replays it. */
    private suspend fun seeded(seed: Long): SimOutcome =
        runCatching { simulate(seed) }.getOrElse { failure ->
            throw AssertionError("Seed $seed failed: ${failure.message}", failure)
        }

    private companion object {
        const val FIRST_SEED = 1L

        /** First catches of real bugs and of mutants, on this generator and on earlier ones. */
        val REGRESSION_SEEDS =
            listOf(
                1L,
                10L,
                11L,
                19L,
                23L,
                37L,
                40L,
                62L,
                93L,
                156L,
                263L,
                296L,
                376L,
                384L,
                715L,
                1050L,
                1207L,
                1236L,
                2091L,
                3621L,
                55279L,
            )

        /** The timeout is generous: a slow runner should fail on a finding, not on the clock. */
        const val MIN_TIMEOUT_MINUTES = 30L
        const val CASES_PER_MINUTE = 100L
        val CASES: Long get() = simulationProperty("jotter.sim.cases") ?: simulationDefaultCases
    }
}
