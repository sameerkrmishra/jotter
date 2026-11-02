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
                1L to "7b0b58678010f509071bfa92f52f1ca8be666423aae5ca2ccc03a69eef627115",
                2L to "963790c8e4ad31c8259fd8b3c4e7cc545eaa792cc5a9888df04f289326cbd554",
                3L to "620a3c38387748f2a62f2e7aaadb4176b91d15820f748ad6e3c39bea1937b689",
            )
    }
}
