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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RevisionIdTest {
    private val userHex = "0192e6f08b4a7c3e9d2f1a2b3c4d5e6f"
    private val mergeHex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun generateCreatesDistinctUuidV7Ids() {
        val first = RevisionId.generate()
        val second = RevisionId.generate()

        assertNotEquals(first, second)
        assertEquals(32, first.toString().length)
        assertEquals('7', first.toString()[VERSION_INDEX], "Expected a UUIDv7: $first")
        assertFalse(first.isMergeHash)
    }

    @Test
    fun fromMergeHashUsesLowercaseHex() {
        val id = RevisionId.fromMergeHash(mergeHex.hexToByteArray())

        assertEquals(mergeHex, id.toString())
        assertTrue(id.isMergeHash)
    }

    @Test
    fun fromMergeHashRejectsOtherSizes() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                RevisionId.fromMergeHash(ByteArray(Uuid.SIZE_BYTES))
            }

        assertEquals("A merge hash has 32 bytes, not 16", error.message)
    }

    @Test
    fun parseAcceptsBothKindsIgnoringCase() {
        assertEquals(userHex, RevisionId.parse(userHex.uppercase()).toString())
        assertEquals(
            RevisionId.fromMergeHash(mergeHex.hexToByteArray()),
            RevisionId.parse(mergeHex),
        )
    }

    @Test
    fun parseRejectsOtherText() {
        val error = assertFailsWith<IllegalArgumentException> { RevisionId.parse("abc") }
        assertEquals("Not a valid revision id: \"abc\"", error.message)

        val dashed = "0192e6f0-8b4a-7c3e-9d2f-1a2b3c4d5e6f"
        listOf("", dashed, userHex.drop(1), "${userHex}0", mergeHex.replace('a', 'g'))
            .forEach { assertNull(RevisionId.parseOrNull(it), "Expected null for \"$it\"") }
        // The neighbours of the hex digit ranges, at a valid length.
        listOf('/', ':', '@', 'G', '`', 'g', ' ').forEach {
            val text = userHex.dropLast(1) + it
            assertNull(RevisionId.parseOrNull(text), "Expected null for \"$text\"")
        }
    }

    @Test
    fun userIdsSortBeforeMergeIdsWithTheSamePrefix() {
        val user = RevisionId.parse(userHex)
        val merge = RevisionId.parse(userHex + userHex)

        assertTrue(user < merge)
    }

    @Test
    fun orderMatchesUnsignedByteOrderAcrossBothKinds() =
        runTest {
            val bytes =
                Arb.choice(
                    revisionIdBytes,
                    randomBytes(Uuid.SIZE_BYTES),
                    randomBytes(RevisionId.MERGE_HASH_SIZE),
                )
            checkAll(bytes, bytes) { a, b ->
                assertEquals(
                    compareUnsigned(a, b).sign,
                    revisionIdOf(a).compareTo(revisionIdOf(b)).sign,
                    "${a.toHexString()} vs ${b.toHexString()}",
                )
            }
        }

    @Test
    fun orderIsTotalAndConsistentWithEquals() =
        runTest { checkTotalOrderLaws(revisionIdBytes.map(::revisionIdOf)) }

    private companion object {
        /** Index of the version digit in the 32-character hex form. */
        const val VERSION_INDEX = 12
    }
}
