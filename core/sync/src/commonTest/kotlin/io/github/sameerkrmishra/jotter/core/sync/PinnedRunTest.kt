package io.github.sameerkrmishra.jotter.core.sync

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * The final state of fixed simulator runs. The expected digests are the same on the JVM and on
 * linuxX64, so this shows that whole histories, with their merged text and every merge's hash, come
 * out alike on both. A digest that moves means the sync format moved, or the simulator draws its
 * faults differently: seeds 1 and 3 moved when format files got a folder of their own, as the
 * extra listing draws from the same random sequence.
 */
class PinnedRunTest {
    @Test
    fun aSeedGivesTheSameFinalStateOnEveryPlatform(): TestResult =
        runTest(timeout = 5.minutes) {
            assertEquals(PINNED, PINNED.keys.associateWith { simulate(it).digest })
        }

    @Test
    fun aSeedReplaysExactly(): TestResult =
        runTest(timeout = 5.minutes) {
            assertEquals(simulate(REPLAYED), simulate(REPLAYED))
        }

    private companion object {
        const val REPLAYED = 42L
        val PINNED: Map<Long, String> =
            mapOf(
                1L to "461c6728113d4084c5405e7cd1ffc24ee0405f7e0bf1cd77b9de13a05f9b6213",
                2L to "ac67c15a280db3d69ebb20ffbefe1b800a2873bea6a585911b98814cad25bfcc",
                3L to "9b0b5ee5b556f610befe9b52fc818e47539e12a04b901b22a700bee84167a938",
            )
    }
}
