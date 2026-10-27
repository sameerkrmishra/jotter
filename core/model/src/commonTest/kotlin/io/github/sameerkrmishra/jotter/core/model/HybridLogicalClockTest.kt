package io.github.sameerkrmishra.jotter.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HybridLogicalClockTest {
    private val wall = FakeClock(millis = 1_000)
    private val clock = HybridLogicalClock(wall, maxDrift = 10.seconds)

    @Test
    fun followsTheWallClockWhileItAdvances() {
        assertEquals(HybridTimestamp(1_000, 0), clock.now())
        wall.millis = 1_005
        assertEquals(HybridTimestamp(1_005, 0), clock.now())
        assertEquals(HybridTimestamp(1_005, 0), clock.last)
    }

    @Test
    fun countsEventsWithinOneMillisecond() {
        clock.now()

        assertEquals(HybridTimestamp(1_000, 1), clock.now())
        assertEquals(HybridTimestamp(1_000, 2), clock.now())
    }

    @Test
    fun staysMonotonicWhenTheWallClockGoesBackwards() {
        clock.now()
        wall.millis = 400

        assertEquals(HybridTimestamp(1_000, 1), clock.now())
        wall.millis = 1_001
        assertEquals(HybridTimestamp(1_001, 0), clock.now())
    }

    @Test
    fun treatsWallClockReadingsBeforeTheEpochAsZero() {
        wall.millis = -5_000

        assertEquals(HybridTimestamp(0, 1), clock.now())
        // The drift is measured from zero, so 10 s is still within it.
        clock.observe(HybridTimestamp(10_000, 0))
        assertEquals(HybridTimestamp(10_000, 1), clock.now())
    }

    @Test
    fun restartsFromThePersistedTimestamp() {
        val restarted = HybridLogicalClock(wall, last = HybridTimestamp(5_000, 3))

        assertEquals(HybridTimestamp(5_000, 4), restarted.now())
    }

    @Test
    fun observingAnEarlierTimestampChangesNothing() {
        val before = clock.now()

        clock.observe(HybridTimestamp(200, 7))

        assertSame(before, clock.last)
        assertEquals(HybridTimestamp(1_000, 1), clock.now())
    }

    @Test
    fun observingALaterTimestampMovesTheClockPastIt() {
        clock.observe(HybridTimestamp(3_000, 7))

        assertEquals(HybridTimestamp(3_000, 7), clock.last)
        assertEquals(HybridTimestamp(3_000, 8), clock.now())
    }

    @Test
    fun observingInTheSameMillisecondKeepsTheGreaterCounter() {
        clock.observe(HybridTimestamp(3_000, 7))
        clock.observe(HybridTimestamp(3_000, 2))
        assertEquals(HybridTimestamp(3_000, 7), clock.last)

        clock.observe(HybridTimestamp(3_000, 20))
        assertEquals(HybridTimestamp(3_000, 21), clock.now())
    }

    @Test
    fun acceptsATimestampExactlyMaxDriftAhead() {
        clock.observe(HybridTimestamp(11_000, 0))

        assertEquals(HybridTimestamp(11_000, 1), clock.now())
    }

    @Test
    fun rejectsATimestampFurtherAheadAndStaysUnchanged() {
        val before = clock.now()
        val remote = HybridTimestamp(11_001, 0)

        val error = assertFailsWith<ClockDriftException> { clock.observe(remote) }

        assertEquals(remote, error.remote)
        assertEquals(1_000, error.wallMillis)
        assertEquals(10.seconds, error.maxDrift)
        assertEquals(
            "Remote timestamp $remote is more than 10s ahead of the wall clock (1000 ms)",
            error.message,
        )
        assertSame(before, clock.last)
        assertEquals(HybridTimestamp(1_000, 1), clock.now())
    }

    @Test
    fun nowAfterAnEarlierTimestampIsAnOrdinaryReading() {
        assertEquals(HybridTimestamp(1_000, 0), clock.now(after = HybridTimestamp(500, 3)))
        assertEquals(HybridTimestamp(1_000, 0), clock.last)
    }

    @Test
    fun nowAfterATimestampWithinTheDriftMovesTheClockPastIt() {
        val parent = HybridTimestamp(5_000, 2)

        assertEquals(HybridTimestamp(5_000, 3), clock.now(after = parent))
        assertEquals(HybridTimestamp(5_000, 3), clock.last)
        assertEquals(HybridTimestamp(5_000, 4), clock.now())
    }

    @Test
    fun nowAfterAFarFutureTimestampFollowsItWithoutMovingTheClock() {
        // A parent written by a device whose clock was a year ahead.
        val parent = HybridTimestamp(31_536_000_000, 0)

        assertEquals(HybridTimestamp(31_536_000_000, 1), clock.now(after = parent))
        assertEquals(HybridTimestamp(1_000, 0), clock.last)
        assertEquals(HybridTimestamp(1_000, 1), clock.now())
    }

    @Test
    fun nowAfterACorruptGreatestTimestampFailsClearlyAndStaysUnchanged() {
        val before = clock.now()

        val error =
            assertFailsWith<IllegalArgumentException> { clock.now(after = HybridTimestamp.MAX) }

        assertEquals(
            "No timestamp follows ${HybridTimestamp.MAX}; it is likely corrupt",
            error.message,
        )
        assertSame(before, clock.last)
    }

    @Test
    fun defaultsToOneDayOfDrift() {
        assertEquals(HybridLogicalClock.DEFAULT_MAX_DRIFT, HybridLogicalClock(wall).maxDrift)
    }

    @Test
    fun rejectsAMaxDriftThatIsNotPositiveAndFinite() {
        listOf(Duration.ZERO, (-1).milliseconds, Duration.INFINITE).forEach {
            assertFailsWith<IllegalArgumentException>("maxDrift $it") {
                HybridLogicalClock(wall, maxDrift = it)
            }
        }
    }

    @Test
    fun timestampsFollowEverythingIssuedOrObservedAndStayWithinTheDrift() =
        runTest {
            checkAll(clockOperations) { operations ->
                val wall = FakeClock(millis = START)
                val clock = HybridLogicalClock(wall, maxDrift = MAX_DRIFT.milliseconds)
                operations.forEach { operation ->
                    val before = clock.last
                    when (operation) {
                        is Operation.MoveWallClock -> wall.millis += operation.byMillis
                        Operation.LocalEvent -> checkLocalEvent(clock, wall.millis, before)
                        is Operation.Observe ->
                            checkObserve(clock, wall.millis, operation.at(wall.millis))
                        is Operation.LocalEventAfter ->
                            checkLocalEventAfter(clock, wall.millis, operation.at(wall.millis))
                    }
                    val physical = clock.last.physicalMillis
                    assertTrue(
                        physical == before.physicalMillis || physical - wall.millis <= MAX_DRIFT,
                        "Clock jumped to ${clock.last}, too far ahead of ${wall.millis}",
                    )
                }
            }
        }

    @Test
    fun timestampsRespectCausalityAcrossDevices() =
        runTest {
            // Each device's clock is skewed, but by less than the allowed drift. A device that
            // observes an event has that event, and everything before it, in its causal past.
            checkAll(Arb.list(Arb.long(-SKEW..SKEW), 3..3), deviceSteps) { skews, steps ->
                val walls = skews.map { FakeClock(millis = START + it) }
                val clocks = walls.map { HybridLogicalClock(it) }
                // The greatest timestamp in each device's causal past.
                val causalPast = MutableList(clocks.size) { HybridTimestamp.ZERO }
                steps.forEach { (sender, receiver, advanceMillis) ->
                    walls.forEach { it.millis += advanceMillis }
                    val sent = clocks[sender].now()
                    assertTrue(sent > causalPast[sender], "Device $sender: $sent")
                    causalPast[sender] = sent
                    clocks[receiver].observe(sent)
                    causalPast[receiver] = maxOf(causalPast[receiver], sent)
                }
                clocks.forEachIndexed { device, clock ->
                    assertTrue(clock.now() > causalPast[device], "Device $device")
                }
            }
        }

    private fun checkLocalEvent(
        clock: HybridLogicalClock,
        wallMillis: Long,
        before: HybridTimestamp,
    ) {
        val next = clock.now()
        assertTrue(next > before, "$next after $before")
        assertEquals(hybridOf(wallMillis, before), next)
        assertEquals(next, clock.last)
    }

    private fun checkObserve(
        clock: HybridLogicalClock,
        wallMillis: Long,
        remote: HybridTimestamp,
    ) {
        val before = clock.last
        if (remote.physicalMillis - wallMillis > MAX_DRIFT) {
            assertFailsWith<ClockDriftException> { clock.observe(remote) }
            assertSame(before, clock.last)
        } else {
            clock.observe(remote)
            assertEquals(maxOf(before, remote), clock.last)
        }
    }

    private fun checkLocalEventAfter(
        clock: HybridLogicalClock,
        wallMillis: Long,
        parent: HybridTimestamp,
    ) {
        val before = clock.last
        val next = clock.now(after = parent)
        assertTrue(next > parent && next > before, "$next after $parent and $before")
        if (parent.physicalMillis - wallMillis <= MAX_DRIFT) {
            assertEquals(hybridOf(wallMillis, maxOf(before, parent)), next)
            assertEquals(next, clock.last)
        } else {
            // The clock ticks as if there were no parent; the result jumps past the parent only
            // if the clock is not already ahead of it.
            val reading = hybridOf(wallMillis, before)
            assertEquals(reading, clock.last)
            assertEquals(if (parent >= reading) parent.successor() else reading, next)
        }
    }

    // The wall clock if that is ahead of everything known, and otherwise the smallest step past
    // the greatest known timestamp: an HLC never runs ahead needlessly.
    private fun hybridOf(
        wallMillis: Long,
        greatestKnown: HybridTimestamp,
    ): HybridTimestamp =
        if (wallMillis > greatestKnown.physicalMillis) {
            HybridTimestamp(wallMillis, 0)
        } else {
            greatestKnown.successor()
        }

    private sealed interface Operation {
        data class MoveWallClock(
            val byMillis: Long,
        ) : Operation

        data object LocalEvent : Operation

        /** Observing a remote timestamp [offsetMillis] from the wall clock. */
        data class Observe(
            val offsetMillis: Long,
            val counter: Int,
        ) : Operation {
            fun at(wallMillis: Long) = remoteAt(wallMillis, offsetMillis, counter)
        }

        /** Editing a revision whose timestamp is [offsetMillis] from the wall clock. */
        data class LocalEventAfter(
            val offsetMillis: Long,
            val counter: Int,
        ) : Operation {
            fun at(wallMillis: Long) = remoteAt(wallMillis, offsetMillis, counter)
        }
    }

    private companion object {
        const val START = 1_000_000L
        const val MAX_DRIFT = 1_000L
        const val SKEW = 3_600_000L

        fun remoteAt(
            wallMillis: Long,
            offsetMillis: Long,
            counter: Int,
        ) = HybridTimestamp((wallMillis + offsetMillis).coerceAtLeast(0), counter)

        // Offsets behind the wall clock, ahead of it and beyond the drift.
        val offsets = Arb.long(-2 * MAX_DRIFT..2 * MAX_DRIFT)

        // Small moves of the wall clock, often backwards, between events.
        val clockOperations: Arb<List<Operation>> =
            Arb.list(
                Arb.choice(
                    Arb.long(-50L..50L).map { Operation.MoveWallClock(it) },
                    Arb.constant(Operation.LocalEvent),
                    Arb.bind(offsets, Arb.int(0..3)) { o, c -> Operation.Observe(o, c) },
                    Arb.bind(offsets, Arb.int(0..3)) { o, c -> Operation.LocalEventAfter(o, c) },
                ),
                0..50,
            )

        // (sender, receiver, how far all wall clocks move first, possibly backwards).
        val deviceSteps: Arb<List<Triple<Int, Int, Long>>> =
            Arb.list(
                Arb.bind(Arb.int(0..2), Arb.int(0..2), Arb.long(-20L..20L)) { s, r, advance ->
                    Triple(s, r, advance)
                },
                0..40,
            )
    }
}
