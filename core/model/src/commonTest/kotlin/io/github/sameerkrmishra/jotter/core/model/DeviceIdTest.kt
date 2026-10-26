package io.github.sameerkrmishra.jotter.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.sign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DeviceIdTest {
    private val canonical = "5f0c2a9e-1b3d-4e6f-8a7b-9c0d1e2f3a4b"

    @Test
    fun parseNormalisesCaseAndFormat() {
        val upperCase = DeviceId.parse(canonical.uppercase())
        val hexOnly = DeviceId.parse(canonical.replace("-", ""))

        assertEquals(canonical, upperCase.toString())
        assertEquals(upperCase, hexOnly)
    }

    @Test
    fun parseRejectsInvalidText() {
        val error = assertFailsWith<IllegalArgumentException> { DeviceId.parse("pc") }

        assertEquals("Not a valid device id: \"pc\"", error.message)
        assertNull(DeviceId.parseOrNull(canonical.drop(1)))
    }

    @Test
    fun generateCreatesDistinctRandomUuids() {
        val first = DeviceId.generate()
        val second = DeviceId.generate()

        assertNotEquals(first, second)
        assertEquals('4', first.toString()[VERSION_INDEX], "Expected a UUIDv4: $first")
        assertEquals(first, DeviceId.parse(first.toString()))
    }

    @Test
    fun ordersAsUnsignedBytes() {
        // Signed bytes would put 0x80 before 0x7f.
        val low = DeviceId.parse("7f000000-0000-0000-0000-000000000000")
        val high = DeviceId.parse("80000000-0000-0000-0000-000000000000")

        assertTrue(low < high)
    }

    @Test
    fun orderMatchesUnsignedByteOrder() =
        runTest {
            val bytes = Arb.choice(deviceIdBytes, randomBytes(Uuid.SIZE_BYTES))
            checkAll(bytes, bytes) { a, b ->
                assertEquals(
                    compareUnsigned(a, b).sign,
                    deviceIdOf(a).compareTo(deviceIdOf(b)).sign,
                    "${a.toHexString()} vs ${b.toHexString()}",
                )
            }
        }

    @Test
    fun orderIsTotalAndConsistentWithEquals() =
        runTest { checkTotalOrderLaws(deviceIdBytes.map(::deviceIdOf)) }

    private companion object {
        /** Index of the version digit in the canonical form `xxxxxxxx-xxxx-Vxxx-...`. */
        const val VERSION_INDEX = 14
    }
}
