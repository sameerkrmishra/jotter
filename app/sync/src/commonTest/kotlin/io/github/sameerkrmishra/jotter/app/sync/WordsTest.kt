package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.sync.SegmentProblem
import io.github.sameerkrmishra.jotter.core.sync.SyncIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the user reads about a backend. */
class WordsTest {
    private val device = DeviceId.parse("0b5ab3c8-6c43-4bd4-9c1b-3f0d2a7c9e11")

    @Test
    fun reachabilityNeverSoundsEmpty() {
        assertEquals("Reachable.", describe(Reachability.Reachable))
        val texts =
            UnreachableReason.entries.map { describe(Reachability.Unreachable(it, "/media/stick")) }
        texts.forEach { text ->
            assertTrue("empty" !in text.lowercase(), text)
            assertTrue(
                "synced" !in text.lowercase() || "nothing is synced" in text.lowercase(),
                text,
            )
        }
        assertTrue(texts[0].startsWith("Not found (/media/stick)"))
        assertTrue(texts[1].contains("not marked as a Jotter store"))
        assertTrue(texts[2].contains("refused the user name or app password"), texts[2])
        assertTrue(texts[2].contains("only every 30 minutes"), texts[2])
        assertEquals("Cannot be reached: /media/stick.", texts[3])
    }

    @Test
    fun everyIssueSaysWhatItMeans() {
        fun segment(problem: SegmentProblem) =
            describe(SyncIssue.SegmentNotApplied(device, 7, problem, "x"))
        assertEquals(
            "File 7 of device 0b5ab3c8 is damaged and was not read. Changes in it arrive only " +
                "if that device, or another backend, still has them.",
            segment(SegmentProblem.CORRUPT),
        )
        assertTrue(segment(SegmentProblem.FUTURE_FORMAT).contains("Update Jotter"))
        assertTrue(segment(SegmentProblem.UNREADABLE).contains("tried again"))
        assertTrue(segment(SegmentProblem.MISSING).contains("Needs attention"))
        assertTrue(
            describe(SyncIssue.RevisionTooLarge(RevisionId.generate())).contains("too large"),
        )
        val drift = SyncIssue.ClockDrift(HybridTimestamp(Long.MAX_VALUE / 2, 0))
        assertTrue(describe(drift).contains("clock"))
    }
}
