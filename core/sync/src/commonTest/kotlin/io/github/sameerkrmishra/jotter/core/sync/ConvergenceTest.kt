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
            val outcomes = seeded((start until start + cases).toList())
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
            // A few seeds, such as one replayed from a nightly issue, need not do all of it.
            if (cases >= MIN_CASES_FOR_TOTALS) {
                assertTrue(outcomes.sumOf { it.merges } > cases, "The cases must make merges")
                assertTrue(outcomes.sumOf { it.conflictsSeen } > 0, "The cases must make conflicts")
                assertTrue(outcomes.sumOf { it.crashes } > 0, "The cases must crash")
            }
        }

    /** On every platform: these seeds lie outside what most of them run by default. */
    @Test
    fun theSeedsThatFoundBugsStillPass(): TestResult =
        runTest(timeout = MIN_TIMEOUT_MINUTES.minutes) {
            seeded(REGRESSION_SEEDS)
        }

    /**
     * Whatever goes wrong in a case, the failure names the seed that replays it. Seeds may run in
     * parallel, but every case is its own world, so the outcomes do not depend on the order: all
     * of them run, and a failure names the first failing seed of [seeds]. Every failing seed is
     * printed on one `Failing seeds:` line, which the nightly report reads from the test results.
     */
    private suspend fun seeded(seeds: List<Long>): List<SimOutcome> {
        val results = simulateEach(seeds)
        val failed = seeds.zip(results).filter { (_, result) -> result.isFailure }
        failed.firstOrNull()?.let { (seed, result) ->
            println("Failing seeds: ${failed.joinToString(" ") { it.first.toString() }}")
            val failure = checkNotNull(result.exceptionOrNull())
            throw AssertionError("Seed $seed failed: ${failure.message}", failure)
        }
        return results.map { it.getOrThrow() }
    }

    private companion object {
        const val FIRST_SEED = 1L

        /** The fewest cases whose totals must show merges, conflicts and crashes. */
        const val MIN_CASES_FOR_TOTALS = 100L

        /** First catches of real bugs and of mutants, on this generator and on earlier ones. */
        val REGRESSION_SEEDS =
            listOf(
                1L,
                2L,
                4L,
                5L,
                6L,
                9L,
                10L,
                11L,
                18L,
                19L,
                23L,
                37L,
                40L,
                47L,
                62L,
                67L,
                93L,
                141L,
                152L,
                156L,
                199L,
                519L,
                1918L,
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
                // Nightly finds (issue #28 and after): a deletion beside a hidden head, and a
                // head that the fold itself frees.
                171987L,
                218876L,
                262796L,
                330375L,
                374916L,
            )

        /** The timeout is generous: a slow runner should fail on a finding, not on the clock. */
        const val MIN_TIMEOUT_MINUTES = 30L
        const val CASES_PER_MINUTE = 100L
        val CASES: Long get() = simulationProperty("jotter.sim.cases") ?: simulationDefaultCases
    }
}
