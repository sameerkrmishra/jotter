package io.github.sameerkrmishra.jotter.core.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class StampTest {
    private val early = HybridTimestamp(1, 0)
    private val late = HybridTimestamp(2, 0)
    private val lowDevice = DeviceId.parse("00000000-0000-4000-8000-000000000000")
    private val highDevice = DeviceId.parse("ff000000-0000-4000-8000-000000000000")
    private val lowRevision = RevisionId.parse("00".repeat(16))
    private val highRevision = RevisionId.parse("ff".repeat(16))

    @Test
    fun timestampDecidesFirst() {
        assertTrue(
            Stamp(early, highDevice, highRevision) < Stamp(late, lowDevice, lowRevision),
        )
    }

    @Test
    fun deviceIdBreaksTimestampTies() {
        assertTrue(
            Stamp(late, lowDevice, highRevision) < Stamp(late, highDevice, lowRevision),
        )
    }

    @Test
    fun revisionIdBreaksTheRemainingTies() {
        // Two automatic merges that inherited the same stamp from a common parent.
        assertTrue(
            Stamp(late, lowDevice, lowRevision) < Stamp(late, lowDevice, highRevision),
        )
    }

    @Test
    fun orderIsTotalAndConsistentWithEquals() = runTest { checkTotalOrderLaws(stamps) }
}
