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
        assertEquals(HybridTimestamp(9_999, 1), clock.receive(HybridTimestamp(9_999, 0)))
    }

    @Test
    fun restartsFromThePersistedTimestamp() {
        val restarted = HybridLogicalClock(wall, last = HybridTimestamp(5_000, 3))

        assertEquals(HybridTimestamp(5_000, 4), restarted.now())
    }

    @Test
    fun receivingAnEarlierTimestampFollowsTheWallClock() {
        assertEquals(HybridTimestamp(1_000, 0), clock.receive(HybridTimestamp(200, 7)))
    }

    @Test
    fun receivingALaterTimestampMovesPastIt() {
        val remote = HybridTimestamp(3_000, 7)

        assertEquals(HybridTimestamp(3_000, 8), clock.receive(remote))
        assertEquals(HybridTimestamp(3_000, 9), clock.now())
    }

    @Test
    fun receivingInTheSameMillisecondTakesTheGreaterCounter() {
        clock.receive(HybridTimestamp(3_000, 7))

        assertEquals(HybridTimestamp(3_000, 9), clock.receive(HybridTimestamp(3_000, 2)))
        assertEquals(HybridTimestamp(3_000, 21), clock.receive(HybridTimestamp(3_000, 20)))
    }

    @Test
    fun acceptsATimestampExactlyMaxDriftAhead() {
        val remote = HybridTimestamp(11_000, 0)

        assertEquals(HybridTimestamp(11_000, 1), clock.receive(remote))
    }

    @Test
    fun rejectsATimestampFurtherAheadAndStaysUnchanged() {
        val before = clock.now()
        val remote = HybridTimestamp(11_001, 0)

        val error = assertFailsWith<ClockDriftException> { clock.receive(remote) }

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
    fun everyTimestampIsGreaterThanEverythingIssuedOrAcceptedBefore() =
        runTest {
            checkAll(clockOperations) { operations ->
                val wall = FakeClock(millis = START)
                val clock = HybridLogicalClock(wall, maxDrift = MAX_DRIFT.milliseconds)
                operations.forEach { operation ->
                    val before = clock.last
                    when (operation) {
                        is Operation.MoveWallClock -> wall.millis += operation.byMillis

                        Operation.LocalEvent -> {
                            val next = clock.now()
                            assertTrue(next > before, "$next after $before")
                            assertIsHybridOf(next, wall.millis, before)
                        }

                        is Operation.Receive -> {
                            val remote = operation.remoteAt(wall.millis)
                            if (remote.physicalMillis - wall.millis > MAX_DRIFT) {
                                assertFailsWith<ClockDriftException> { clock.receive(remote) }
                                assertSame(before, clock.last)
                            } else {
                                val next = clock.receive(remote)
                                assertTrue(next > before && next > remote, "$next: $before $remote")
                                assertIsHybridOf(next, wall.millis, maxOf(before, remote))
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun timestampsRespectCausalityAcrossDevices() =
        runTest {
            // Each device's clock is skewed, but by less than the allowed drift. A device that
            // receives an event has that event, and everything before it, in its causal past.
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
                    val received = clocks[receiver].receive(sent)
                    assertTrue(received > maxOf(causalPast[receiver], sent), "Device $receiver")
                    causalPast[receiver] = received
                }
            }
        }

    // The timestamp is the wall clock if that is ahead of everything known, and otherwise the
    // smallest step past the greatest known timestamp: an HLC never runs ahead needlessly.
    private fun assertIsHybridOf(
        next: HybridTimestamp,
        wallMillis: Long,
        greatestKnown: HybridTimestamp,
    ) {
        val expected =
            if (wallMillis > greatestKnown.physicalMillis) {
                HybridTimestamp(wallMillis, 0)
            } else {
                greatestKnown.successor()
            }
        assertEquals(expected, next)
    }

    private sealed interface Operation {
        data class MoveWallClock(
            val byMillis: Long,
        ) : Operation

        data object LocalEvent : Operation

        data class Receive(
            val offsetMillis: Long,
            val counter: Int,
        ) : Operation {
            fun remoteAt(wallMillis: Long) =
                HybridTimestamp((wallMillis + offsetMillis).coerceAtLeast(0), counter)
        }
    }

    private companion object {
        const val START = 1_000_000L
        const val MAX_DRIFT = 1_000L
        const val SKEW = 3_600_000L

        // Small moves, often backwards; remote timestamps behind, ahead and beyond the drift.
        val clockOperations: Arb<List<Operation>> =
            Arb.list(
                Arb.choice(
                    Arb.long(-50L..50L).map { Operation.MoveWallClock(it) },
                    Arb.constant(Operation.LocalEvent),
                    Arb.bind(Arb.long(-2 * MAX_DRIFT..2 * MAX_DRIFT), Arb.int(0..3)) { o, c ->
                        Operation.Receive(o, c)
                    },
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
