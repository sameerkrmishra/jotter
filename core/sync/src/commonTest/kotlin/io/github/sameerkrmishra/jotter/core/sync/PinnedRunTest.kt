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
                1L to "9c9e854ed58acd6f077a1ef12e1dc57617db5920a5b28b89d2b3f45447f117a2",
                2L to "ac67c15a280db3d69ebb20ffbefe1b800a2873bea6a585911b98814cad25bfcc",
                3L to "5c171da9e7bcfa11c9b1c0e27189928191f0e7f3b5c91c9f12835a74dd00f415",
            )
    }
}
