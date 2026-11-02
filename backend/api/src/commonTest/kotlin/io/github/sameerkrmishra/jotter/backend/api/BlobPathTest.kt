package io.github.sameerkrmishra.jotter.backend.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlobPathTest {
    @Test
    fun acceptsTheBackendLayout() {
        requireValidBlobPath("jotter/format.json")
        requireValidBlobPath("jotter/devices/0190a2b4/000000000001.json.gz")
    }

    @Test
    fun acceptsDotsThatAreNotWholeSegments() {
        requireValidBlobPath("...")
        requireValidBlobPath("a/..b/c..")
        requireValidBlobPath(".hidden")
        requireValidBlobPath(".jotter")
    }

    @Test
    fun refusesEachKindOfBadPathForAReasonOfItsOwn() {
        val reasons =
            mapOf(
                "" to "empty",
                "/a" to "empty segment",
                "a/" to "empty segment",
                "a//b" to "empty segment",
                ".." to "\"..\"",
                "a/../b" to "\"..\"",
                "a/./b" to "\".\"",
                "a\\b" to "backslash",
                "a\u0000" to "NUL",
                "C:/a" to "colon",
                "a/C:b" to "colon",
                ".jotter-tmp-1" to "reserved",
                "a/.JOTTER-store" to "reserved",
            )
        reasons.forEach { (path, reason) ->
            val failure =
                assertFailsWith<InvalidBlobPathException>(path) {
                    requireValidBlobPath(path)
                }
            assertEquals(path, failure.path)
            assertContains(failure.message.orEmpty(), reason)
        }
    }

    @Test
    fun theEmptyPrefixIsValidUnlikeTheEmptyPath() {
        requireValidBlobPrefix("")
        requireValidBlobPrefix("a/b")
        assertFailsWith<InvalidBlobPathException> { requireValidBlobPrefix("a/") }
        assertFailsWith<InvalidBlobPathException> { requireValidBlobPrefix("..") }
    }

    @Test
    fun aSegmentIsCheckedOnItsOwn() {
        assertTrue(isValidBlobSegment("000000000001.json.gz"))
        assertFalse(isValidBlobSegment(""))
        assertFalse(isValidBlobSegment(".."))
        assertFalse(isValidBlobSegment("a\\b"))
        assertFalse(isValidBlobSegment(".jotter-tmp-1"))
    }
}
