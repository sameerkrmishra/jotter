package io.github.sameerkrmishra.jotter.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TextTest {
    @Test
    fun storableTextIsKept() {
        // Accented, Katakana, a surrogate pair (U+1F600), U+FEFF (not leading) and U+FFFD.
        val text = "caf\u00E9 \u30AB \uD83D\uDE00 \uFEFF \uFFFD"

        assertTrue(text.isStorableText())
        assertSame(text, text.toStorableText())
    }

    @Test
    fun unpairedSurrogatesAndNoncharactersBecomeReplacementCharacters() {
        val cases =
            mapOf(
                "\uD83D" to "\uFFFD",
                "\uDE00x" to "\uFFFDx",
                "a\uD83Db" to "a\uFFFDb",
                "\uDE00\uD83D" to "\uFFFD\uFFFD",
                "\uD83D\uD83D\uDE00" to "\uFFFD\uD83D\uDE00",
                "a\uFFFEb\uFFFF" to "a\uFFFDb\uFFFD",
                "a\u0000b" to "a\uFFFDb",
                "\uD83D\uDE00\uFFFF" to "\uD83D\uDE00\uFFFD",
                // A leading byte order mark; elsewhere U+FEFF is kept.
                "\uFEFFa\uFEFF" to "\uFFFDa\uFEFF",
            )

        cases.forEach { (text, expected) ->
            assertFalse(text.isStorableText(), text)
            assertEquals(expected, text.toStorableText())
            assertTrue(expected.isStorableText())
        }
    }
}
