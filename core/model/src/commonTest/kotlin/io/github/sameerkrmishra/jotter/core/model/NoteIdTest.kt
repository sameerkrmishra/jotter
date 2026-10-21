package io.github.sameerkrmishra.jotter.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NoteIdTest {
    private val canonical = "0192e6f0-8b4a-7c3e-9d2f-1a2b3c4d5e6f"

    @Test
    fun parseKeepsCanonicalUuid() {
        assertEquals(canonical, NoteId.parse(canonical).toString())
    }

    @Test
    fun parseNormalisesCaseAndFormat() {
        val upperCase = NoteId.parse(canonical.uppercase())
        val hexOnly = NoteId.parse(canonical.replace("-", ""))

        assertEquals(canonical, upperCase.toString())
        assertEquals(canonical, hexOnly.toString())
        assertEquals(upperCase, hexOnly)
    }

    @Test
    fun parseRejectsInvalidText() {
        val error = assertFailsWith<IllegalArgumentException> { NoteId.parse("not-a-uuid") }

        assertEquals("Not a valid note id: \"not-a-uuid\"", error.message)
    }

    @Test
    fun parseOrNullReturnsNullForInvalidText() {
        listOf("", "not-a-uuid", canonical.drop(1), "$canonical-0", canonical.replace('0', 'g'))
            .forEach { assertNull(NoteId.parseOrNull(it), "Expected null for \"$it\"") }
    }
}
