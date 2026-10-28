package io.github.sameerkrmishra.jotter.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextTest {
    @Test
    fun validTextIsKept() {
        // Accented, Katakana and a surrogate pair (U+1F600).
        val text = "café カ 😀"

        assertTrue(text.isValidUtf16())
        assertEquals(text, text.withoutLoneSurrogates())
    }

    @Test
    fun unpairedSurrogatesBecomeReplacementCharacters() {
        val cases =
            mapOf(
                "\uD83D" to "�",
                "\uDE00x" to "�x",
                "a\uD83Db" to "a�b",
                "\uDE00\uD83D" to "��",
                "\uD83D😀" to "�😀",
            )

        cases.forEach { (text, expected) ->
            assertFalse(text.isValidUtf16(), text)
            assertEquals(expected, text.withoutLoneSurrogates())
        }
    }
}
