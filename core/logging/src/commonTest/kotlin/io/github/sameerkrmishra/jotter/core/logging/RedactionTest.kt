package io.github.sameerkrmishra.jotter.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    @Test
    fun userInfoLeavesUrls() {
        assertEquals(
            "PUT https://$REDACTED@dav.example.org/jotter/x failed",
            redact("PUT https://me:s3cret@dav.example.org/jotter/x failed"),
        )
        assertEquals("http://$REDACTED@[::1]:8080/", redact("http://token@[::1]:8080/"))
    }

    @Test
    fun anAtInThePathStays() {
        val url = "https://cloud.example.org/remote.php/dav/files/me@example.org/jotter/"
        assertEquals(url, redact(url))
    }

    @Test
    fun authorizationValuesGo() {
        assertEquals("Authorization: $REDACTED", redact("Authorization: Basic bWU6c2VjcmV0"))
        assertEquals(
            "headers [proxy-authorization=$REDACTED",
            redact("headers [proxy-authorization=Bearer abc.def.ghi, Accept=*/*]"),
        )
    }

    @Test
    fun schemeCredentialsGo() {
        assertEquals("sent Bearer $REDACTED", redact("sent Bearer abcdef123456"))
        assertEquals("sent basic $REDACTED then", redact("sent basic bWU6c2VjcmV0 then"))
        // Prose is left alone.
        assertEquals("a basic idea", redact("a basic idea"))
    }

    @Test
    fun namedSecretsGo() {
        assertEquals("password=$REDACTED&user=me", redact("password=hunter2&user=me"))
        assertEquals("{\"token\": \"$REDACTED\"}", redact("{\"token\": \"jt_abcdef\"}"))
        assertEquals("api_key: $REDACTED", redact("api_key: 12345"))
        assertEquals("secret=$REDACTED", redact("secret=x"))
    }

    @Test
    fun plainTextStays() {
        val text = "Synced backend 3f2a (webdav): sent 2 files, received 5 changes in 120 ms"
        assertEquals(text, redact(text))
    }

    @Test
    fun aFailureShowsItsClassAndFrames() {
        val text = describeFailure(TestFailure("hub at 100.64.0.1:47734 refused"))
        val lines = text.lines()
        assertEquals(
            "io.github.sameerkrmishra.jotter.core.logging.TestFailure: " +
                "hub at 100.64.0.1:47734 refused",
            lines[0],
        )
        assertTrue(lines.drop(1).all { it.startsWith("  at ") })
        assertTrue(lines.size > 1)
    }

    @Test
    fun aMessageThatMayQuoteDataIsLeftOut() {
        // Such as a JSON parser quoting a damaged change file, or a check() naming a tag.
        val text =
            describeFailure(IllegalStateException("Unexpected JSON at: {\"body\":\"MARKER\"}"))
        assertFalse("MARKER" in text)
        assertTrue(text.lines()[0].endsWith("IllegalStateException"))
    }

    @Test
    fun causesFollowEachWithItsOwnRule() {
        val failure =
            TestFailure(
                "sync of b1 failed",
                IllegalArgumentException("tag MARKER is invalid", TestFailure("root cause")),
            )
        val text = describeFailure(failure)
        assertFalse("MARKER" in text)
        val causes = text.lines().filter { it.startsWith("caused by: ") }
        assertEquals(2, causes.size)
        assertTrue(causes[0].endsWith("IllegalArgumentException"))
        assertTrue(causes[1].endsWith("TestFailure: root cause"))
    }

    @Test
    fun aCycleOfCausesEnds() {
        val first = CyclicFailure()
        val second = CyclicFailure()
        first.next = second
        second.next = first
        val text = describeFailure(first)
        assertEquals(1, text.lines().count { it.startsWith("caused by: ") })
    }

    @Test
    fun safeMessagesAreRedactedToo() {
        val text = describeFailure(TestFailure("GET https://me:pw@host/ failed"))
        assertFalse("pw@" in text)
    }

    @Test
    fun aFailureWithoutMessageShowsItsClass() {
        assertTrue(describeFailure(TestFailureWithoutMessage()).lines()[0].endsWith("Message"))
    }
}

private class CyclicFailure : Exception() {
    var next: Throwable? = null

    override val cause: Throwable? get() = next
}

private class TestFailureWithoutMessage : Exception()
