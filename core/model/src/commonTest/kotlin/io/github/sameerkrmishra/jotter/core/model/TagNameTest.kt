package io.github.sameerkrmishra.jotter.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TagNameTest {
    @Test
    fun normalisesCaseAndWhitespace() {
        assertEquals("shopping list", TagName.parse("  Shopping \t\n LIST ").toString())
        assertEquals(TagName.parse("work"), TagName.parse("Work"))
    }

    @Test
    fun collapsesUnicodeWhitespace() {
        // No-break space and ideographic space, which a `\s` regex misses on the JVM.
        assertEquals("a b", TagName.parse(" a　 b ").toString())
    }

    @Test
    fun lowercasesBeyondAscii() {
        assertEquals("ärger", TagName.parse("ÄRGER").toString())
    }

    @Test
    fun rejectsEmptyAndBlankText() {
        val error = assertFailsWith<IllegalArgumentException> { TagName.parse(" \t ") }

        assertEquals("Not a valid tag: \" \t \"", error.message)
        assertNull(TagName.parseOrNull(""))
    }

    @Test
    fun limitsTheNormalisedLength() {
        val longest = "a".repeat(TagName.MAX_LENGTH)

        assertEquals(longest, TagName.parse("  $longest  ").toString())
        assertNull(TagName.parseOrNull(longest + "a"))
    }

    @Test
    fun rejectsControlCharacters() {
        assertNull(TagName.parseOrNull("a\u0000b"))
        assertNull(TagName.parseOrNull("bell\u0007"))
    }

    @Test
    fun parsingIsIdempotent() =
        runTest {
            checkAll(Arb.string(0..TagName.MAX_LENGTH)) { text ->
                val tag = TagName.parseOrNull(text) ?: return@checkAll
                assertEquals(tag, TagName.parse(tag.toString()))
            }
        }
}
