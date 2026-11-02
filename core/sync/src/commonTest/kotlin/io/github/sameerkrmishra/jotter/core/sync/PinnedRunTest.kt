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
                1L to "208be0cc6e5383ba195c175073d26dca74e651377af0730587601b34053147ae",
                2L to "5d9efb27393d4b39f3705b5e241a58376f3792dc84aa504149a58f9f94254cc7",
                3L to "f128007725ccd710db90d0895baf79f0078525b5044b6ff4fdfb301a33f12712",
            )
    }
}
