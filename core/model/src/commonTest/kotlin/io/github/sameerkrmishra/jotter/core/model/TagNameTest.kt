package io.github.sameerkrmishra.jotter.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TagNameTest {
    @Test
    fun trimsAndCollapsesWhitespace() {
        assertEquals("Shopping list", TagName.parse("  Shopping \t\r\n list ").toString())
        assertEquals(TagName.parse("Work"), TagName.parse(" Work\n"))
    }

    @Test
    fun keepsLetterCaseAndComparesExactly() {
        assertEquals("\u00C4RGER", TagName.parse("\u00C4RGER").toString())
        assertNotEquals(TagName.parse("Work"), TagName.parse("work"))
    }

    @Test
    fun collapsesEveryListedWhitespaceCharacter() {
        val text = TagName.WHITESPACE + "a" + TagName.WHITESPACE + "b" + TagName.WHITESPACE

        assertEquals("a b", TagName.parse(text).toString())
    }

    @Test
    fun keepsCharactersOutsideTheWhitespaceList() {
        // Zero-width space and the Mongolian vowel separator are not White_Space (any more),
        // whatever a platform's Char.isWhitespace says.
        listOf("a\u200Bb", "a\u180Eb").forEach {
            assertEquals(it, TagName.parse(it).toString())
        }
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
        // The edges of both control ranges, and characters just outside them.
        listOf('\u0000', '\u001F', '\u007F', '\u009F').forEach {
            assertNull(TagName.parseOrNull("a${it}b"), "Expected null for U+${it.code}")
        }
        listOf('\u007E', '\u00A1').forEach {
            assertEquals("a${it}b", TagName.parse("a${it}b").toString())
        }
    }

    @Test
    fun acceptsSurrogatePairs() {
        // U+1F6D2 (shopping trolley), outside the Basic Multilingual Plane.
        val emoji = "\uD83D\uDED2"

        assertEquals("$emoji list", TagName.parse("$emoji  list").toString())
    }

    @Test
    fun rejectsUnpairedSurrogates() {
        // A high surrogate before a non-surrogate or at the end, and lone low surrogates.
        listOf("a\uD800b", "a\uD800", "a\uDC00b", "\uDC00\uD800").forEach {
            assertNull(TagName.parseOrNull(it), "Expected null for \"$it\"")
        }
    }

    @Test
    fun rejectsWhatSqliteCannotStoreUnchanged() {
        listOf("a\uFFFEb", "a\uFFFF", "\uFEFFa").forEach {
            assertNull(TagName.parseOrNull(it), "Expected null for \"$it\"")
        }
        // The characters next to them are fine.
        assertEquals("a\uFFFDb", TagName.parse("a\uFFFDb").toString())
        assertEquals("a\uFEFFb", TagName.parse("a\uFEFFb").toString())
    }

    @Test
    fun fromNormalisedAcceptsOnlyNormalisedText() {
        assertEquals(TagName.parse("Shopping list"), TagName.fromNormalised("Shopping list"))

        listOf(" Shopping", "Shopping  list", "Shopping\tlist", "", "a\u0000").forEach {
            val error = assertFailsWith<IllegalArgumentException> { TagName.fromNormalised(it) }
            assertEquals("Not a normalised tag: \"$it\"", error.message)
        }
    }

    @Test
    fun parsingIsIdempotent() =
        runTest {
            checkAll(Arb.string(0..TagName.MAX_LENGTH)) { text ->
                val tag = TagName.parseOrNull(text) ?: return@checkAll
                assertEquals(tag, TagName.fromNormalised(tag.toString()))
            }
        }
}
