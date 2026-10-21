package io.github.sameerkrmishra.jotter.spike.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class AddNoteTest {
    @Test
    fun blankDraftIsIgnored() {
        assertEquals(listOf("a"), addNote(listOf("a"), "  "))
    }

    @Test
    fun draftIsTrimmedAndPrepended() {
        assertEquals(listOf("b", "a"), addNote(listOf("a"), " b "))
    }
}
