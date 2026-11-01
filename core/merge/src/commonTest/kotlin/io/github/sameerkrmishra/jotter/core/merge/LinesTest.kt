package io.github.sameerkrmishra.jotter.core.merge

import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LinesTest {
    @Test
    fun emptyTextHasNoLines() {
        assertEquals(emptyList(), splitLines(""))
    }

    @Test
    fun everyKindOfBreakEndsALineAndStaysOnIt() {
        assertEquals(listOf("a\n", "b\r\n", "c\r", "d"), splitLines("a\nb\r\nc\rd"))
    }

    @Test
    fun aLineBreakAloneIsALine() {
        assertEquals(listOf("\n", "\r\n", "\r"), splitLines("\n\r\n\r"))
    }

    @Test
    fun crFollowedByCrIsTwoBreaksAndLfThenCrIsTwoBreaks() {
        assertEquals(listOf("\r", "\r\n"), splitLines("\r\r\n"))
        assertEquals(listOf("\n", "\r"), splitLines("\n\r"))
    }

    @Test
    fun aFinalLineWithAndWithoutABreakDiffer() {
        assertEquals(listOf("a"), splitLines("a"))
        assertEquals(listOf("a\n"), splitLines("a\n"))
    }

    @Test
    fun onlyLfAndCrBreakLines() {
        val text = "a b c\u0085d\u000Ce\u000Bf"

        assertEquals(listOf(text), splitLines(text))
    }

    @Test
    fun joiningTheLinesGivesTheTextBack() =
        runTest {
            checkAll(texts) { text -> assertEquals(text, splitLines(text).joinToString("")) }
        }

    @Test
    fun lineBreaksAppearOnlyAtTheEndOfALine() =
        runTest {
            checkAll(texts) { text ->
                splitLines(text).forEach { line ->
                    val body = line.removeSuffix("\n").removeSuffix("\r")
                    assertEquals(false, body.contains('\n') || body.contains('\r'), line)
                }
            }
        }
}
