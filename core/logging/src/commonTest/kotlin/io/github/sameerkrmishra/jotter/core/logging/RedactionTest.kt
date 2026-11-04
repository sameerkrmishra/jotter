package io.github.sameerkrmishra.jotter.core.logging

import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    @Test
    fun urlsKeepOnlySchemeHostAndPort() {
        assertEquals(
            "PUT https://dav.example.org failed",
            redact("PUT https://me:s3cret@dav.example.org/jotter/x failed"),
        )
        assertEquals("http://[::1]:8080", redact("http://token@[::1]:8080/"))
        assertEquals(
            "GET https://cloud.example.org:443 refused",
            redact("GET https://cloud.example.org:443/remote.php/dav/files/me@x.org/?a=b refused"),
        )
        assertEquals("(at http://127.0.0.1:9)", redact("(at http://127.0.0.1:9/dav/)"))
    }

    @Test
    fun theHomeDirectoryBecomesATilde() {
        assertEquals("~/notes.db and ~", redact("/home/me/notes.db and /home/me", "/home/me/"))
        assertEquals("/home/meg/notes.db", redact("/home/meg/notes.db", "/home/me"))
        // A root or relative home would match everything, so it is not used.
        assertEquals("/x", redact("/x", "/"))
        assertEquals("rel/x", redact("rel/x", "rel"))
        assertEquals("/home/me/x", redact("/home/me/x", null))
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
    fun namedSecretsGoWhole() {
        // Unquoted: nothing says where it ends, so the rest of the line goes.
        assertEquals("password=$REDACTED", redact("password=hunter2&user=me"))
        assertEquals("api_key: $REDACTED\nnext", redact("api_key: 12 34\nnext"))
        assertEquals("secret=$REDACTED", redact("secret=x"))
        // Quoted: the whole value, spaces and escaped quotes too, and what follows stays.
        assertEquals("{\"token\": $REDACTED}", redact("{\"token\": \"jt_abcdef\"}"))
        assertEquals(
            "{\"password\": $REDACTED, \"user\": \"me\"}",
            redact("{\"password\": \"a b \\\" c\", \"user\": \"me\"}"),
        )
        assertEquals("passphrase=$REDACTED and on", redact("passphrase='two words' and on"))
    }

    @Test
    fun plainTextStays() {
        val text = "Synced backend 3f2a (webdav): sent 2 files, received 5 changes in 120 ms"
        assertEquals(text, redact(text))
    }

    @Test
    fun aFailureShowsItsClassAndFrames() {
        val text = describeFailure(IOException("hub at 100.64.0.1:47734 refused"))
        val lines = text.lines()
        assertTrue(lines[0].endsWith("IOException: hub at 100.64.0.1:47734 refused"), lines[0])
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
            IOException(
                "sync of b1 failed",
                IllegalArgumentException("tag MARKER is invalid", IOException("root cause")),
            )
        val text = describeFailure(failure)
        assertFalse("MARKER" in text)
        val causes = text.lines().filter { it.startsWith("caused by: ") }
        assertEquals(2, causes.size)
        assertTrue(causes[0].endsWith("IllegalArgumentException"))
        assertTrue(causes[1].endsWith("IOException: root cause"))
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
        val text = describeFailure(IOException("GET https://me:pw@host/ failed"))
        assertFalse("pw@" in text)
    }

    @Test
    fun anExceptionThatCopiesItsCausesMessageIsNotTrusted() {
        // Jotter's own wrappers, such as BlobStoreFailureException, put a cause's message in their
        // own. Only classes that write their messages whole are trusted, not a whole package.
        val wrapper = TestFailure("Cannot read: MARKER", IllegalStateException("MARKER"))
        val text = describeFailure(wrapper)
        assertFalse("MARKER" in text, text)
        assertTrue(text.lines()[0].endsWith("TestFailure"))
        assertTrue(
            hasSafeMessage("io.github.sameerkrmishra.jotter.core.sqlite.DatabaseUpgradeException"),
        )
        assertFalse(
            hasSafeMessage("io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException"),
        )
        assertFalse(
            hasSafeMessage("io.github.sameerkrmishra.jotter.backend.webdav.DavTransportException"),
        )
        assertTrue(hasSafeMessage("java.net.ConnectException"))
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
