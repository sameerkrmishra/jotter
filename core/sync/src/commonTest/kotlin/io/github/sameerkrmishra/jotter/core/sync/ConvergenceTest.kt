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
        runTest(timeout = 30.minutes) {
            val start = simulationProperty("jotter.sim.start") ?: FIRST_SEED
            val cases = simulationProperty("jotter.sim.cases") ?: simulationDefaultCases
            val outcomes = (start until start + cases).map { simulate(it) }
            println(
                "Simulated seeds $start until ${start + cases}: " +
                    "${outcomes.sumOf { it.revisions }} revisions, " +
                    "${outcomes.sumOf { it.merges }} merges, " +
                    "${outcomes.sumOf { it.conflictsSeen }} conflicts, " +
                    "${outcomes.sumOf { it.crashes }} crashes, " +
                    "${outcomes.sumOf { it.badStamps }} bad revisions, " +
                    "${outcomes.sumOf { it.restores }} restored backups",
            )
            assertTrue(outcomes.sumOf { it.merges } > cases, "The cases must make merges")
            assertTrue(outcomes.sumOf { it.conflictsSeen } > 0, "The cases must make conflicts")
            assertTrue(outcomes.sumOf { it.crashes } > 0, "The cases must crash")
        }

    private companion object {
        const val FIRST_SEED = 1L
    }
}
