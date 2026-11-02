package io.github.sameerkrmishra.jotter.core.sync

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * The final state of fixed simulator runs. The expected digests are the same on the JVM and on
 * linuxX64, so this shows that whole histories, with their merged text and every merge's hash, come
 * out alike on both. A digest that moves means the sync format moved.
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
                1L to "1ef4ef66e78a07bd6f189d2f461d67d22902454608a0c82044916c96071c2093",
                2L to "09ac9077ca683b0ab933e881e73e6ea8286e6de2eeb95639d4c1c02eb607d3d4",
                3L to "9ec285bd2646a1afd07ac283639e8682e20b8bf952de929450b393d4ccd324be",
            )
    }
}
