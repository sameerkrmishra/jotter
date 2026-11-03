package io.github.sameerkrmishra.jotter.backend.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DavLocationTest {
    @Test
    fun anHttpsUrlIsSplitIntoOriginAndDecodedSegments() {
        val location =
            DavLocation.parse(
                "HTTPS://dav.example:8443/remote.php/dav/files/me@example.org/jotter%20sync/",
            )
        assertEquals("https://dav.example:8443", location.origin)
        assertEquals(
            listOf("remote.php", "dav", "files", "me@example.org", "jotter sync"),
            location.segments,
        )
        assertEquals(
            "https://dav.example:8443/remote.php/dav/files/me%40example.org/jotter%20sync/",
            location.rootUrl,
        )
        assertEquals(location.rootUrl, location.toString())
    }

    @Test
    fun aTrailingSlashMakesNoDifference() {
        assertEquals(
            DavLocation.parse("https://h/a/b").rootUrl,
            DavLocation.parse("https://h/a/b/").rootUrl,
        )
        assertEquals("https://h/", DavLocation.parse("https://h").rootUrl)
    }

    @Test
    fun urlsOfFilesAndCollectionsEncodeEverySegment() {
        val location = DavLocation.parse("https://h/store")
        assertEquals(
            "https://h/store/a/caf%C3%A9%20%E6%97%A5.txt",
            location.fileUrl(listOf("a", "café 日.txt")),
        )
        assertEquals("https://h/store/100%25%3F%23/", location.collectionUrl(listOf("100%?#")))
        assertEquals("https://h/store/-._~%2B%26%F0%9F%93%9D", location.fileUrl(listOf("-._~+&📝")))
    }

    @Test
    fun plainHttpIsOnlyForThisMachine() {
        listOf(
            "http://localhost:8080/dav",
            "http://127.0.0.1/",
            "http://127.1.2.3:1/x",
            "http://[::1]:80/",
        ).forEach {
            DavLocation.parse(it)
        }
        listOf(
            "http://dav.example/",
            "http://192.168.1.2/",
            "http://127.0.0.256/",
            "http://127.0.0/",
            "http://10.0.0.1/",
            "http://127.a.0.1/",
            "http://localhost.evil/",
        ).forEach { url ->
            val failure = assertFailsWith<IllegalArgumentException>(url) { DavLocation.parse(url) }
            assertTrue("https" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun badUrlsAreRefused() {
        listOf(
            "ftp://localhost/",
            "dav.example/x",
            "https:///x",
            "https://h/x?q=1",
            "https://h/x#f",
            "https://h/a/../b",
            "https://h/a/./b",
            "https://h/%zz",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { DavLocation.parse(url) }
        }
    }

    @Test
    fun aUrlWithAPasswordIsRefusedWithoutRepeatingIt() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                DavLocation.parse(
                    "https://me:hunter2@dav.example/",
                )
            }
        assertFalse("hunter2" in failure.message.orEmpty())
    }

    @Test
    fun segmentsDecodeEscapesInEitherCaseAndRawCharacters() {
        assertEquals(listOf("café x"), decodeSegments("/caf%c3%a9%20x/"))
        assertEquals(listOf("café", "日📝"), decodeSegments("/café//日📝"))
        assertEquals(emptyList(), decodeSegments("/"))
    }

    @Test
    fun brokenEscapesAndBytesThatAreNotUtf8DecodeToNull() {
        listOf("/%", "/%4", "/%g0", "/%+1", "/%-1", "/%ff", "/%c3").forEach {
            assertNull(decodeSegments(it), it)
        }
    }

    @Test
    fun hrefsGiveTheirPath() {
        assertEquals("/a/b/", hrefPath(" https://h:1/a/b/ "))
        assertEquals("/", hrefPath("https://h"))
        assertEquals("/a", hrefPath("/a?x=1#y"))
        assertNull(hrefPath("a/b"))
    }
}
