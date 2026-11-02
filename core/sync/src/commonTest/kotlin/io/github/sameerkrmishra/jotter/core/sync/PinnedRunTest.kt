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
                1L to "3e461147bd50a2c5300074ffd9df395bafd71eff7558c80837491c139d1fe2d8",
                2L to "973cf0606f6ed9b88b396c7b5bf80c157b0ff61883ea8c4e02b87284ade617bc",
                3L to "1e578b4945ad39fe7520985e438a02c1079481ea04ca9cd8a7d0a197feed811d",
            )
    }
}
