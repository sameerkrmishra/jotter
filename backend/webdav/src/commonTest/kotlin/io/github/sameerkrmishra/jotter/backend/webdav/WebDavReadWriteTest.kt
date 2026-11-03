package io.github.sameerkrmishra.jotter.backend.webdav

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `read`, `writeNew`, retries and redirects, against the fake server. */
class WebDavReadWriteTest {
    private val fixture = FakeFixture()
    private val server = fixture.server

    @Test
    fun transientFailuresAreRetried() =
        runTest {
            val store = fixture.store()
            store.writeNew("a", byteArrayOf(1))
            fixture.drop("PROPFIND")
            fixture.fault("PROPFIND", "", HttpStatusCode.BadGateway, HttpHeaders.RetryAfter to "1")
            assertEquals(listOf("a"), store.list("").map { it.path })
            fixture.drop("GET")
            fixture.fault("GET", "", HttpStatusCode.TooManyRequests)
            assertContentEquals(byteArrayOf(1), store.read("a"))
        }

    @Test
    fun aRetryAfterBeyondTheLimitIsNotWaitedFor() =
        runTest {
            val store = fixture.store()
            fixture.fault(
                "GET",
                "",
                HttpStatusCode.ServiceUnavailable,
                HttpHeaders.RetryAfter to "3600",
            )
            assertFailsWith<BackendUnavailableException> { store.read("a") }
            assertEquals(1, server.requests.count { it.startsWith("GET") })
        }

    @Test
    fun aFailureWithoutMessageIsStillDescribed() =
        runTest {
            val store = fixture.store()
            repeat(3) { server.faults += FakeDavServer.Fault("GET", "") { throw IOException() } }
            val failure = assertFailsWith<BackendUnavailableException> { store.read("a") }
            assertContains(failure.message.orEmpty(), "IOException")
        }

    @Test
    fun readingACollectionIsNotFoundOnEveryServer() =
        runTest {
            val store = fixture.store()
            store.writeNew("dir/file", byteArrayOf(1))
            listOf(
                HttpStatusCode.NotFound,
                HttpStatusCode.MethodNotAllowed,
                HttpStatusCode.MovedPermanently,
            ).forEach {
                server.getOfCollection = it
                assertFailsWith<BlobNotFoundException>(it.toString()) { store.read("dir") }
            }
            fixture.fault("GET", "/store/dir/file/below", HttpStatusCode.Forbidden)
            assertFailsWith<BlobNotFoundException> { store.read("dir/file/below") }
        }

    @Test
    fun aFileThatCannotBeReadFails() =
        runTest {
            val store = fixture.store()
            store.writeNew("file", byteArrayOf(1))
            fixture.fault("GET", "/file", HttpStatusCode.Forbidden)
            assertFailsWith<BlobStoreFailureException> { store.read("file") }
            fixture.fault("GET", "/file", HttpStatusCode.Unauthorized)
            assertFailsWith<BackendUnavailableException> { store.read("file") }
        }

    @Test
    fun aFileOverTheLimitIsNotRead() =
        runTest {
            val store = fakeStore(server, settings = DavSettings(maxFileBytes = 10))
            store.initialize()
            server.putFile("/store/big", ByteArray(11))
            assertFailsWith<BlobStoreFailureException> { store.read("big") }
            // Without a Content-Length the limit is found while reading.
            fixture.body("/big", ByteArray(11))
            assertFailsWith<BlobStoreFailureException> { store.read("big") }
            server.putFile("/store/fits", ByteArray(10) { 3 })
            assertContentEquals(ByteArray(10) { 3 }, store.read("fits"))
        }

    @Test
    fun aLargeBodyWithoutLengthIsReadInFull() =
        runTest {
            val store = fixture.store()
            val bytes = ByteArray(LARGE) { (it % PRIME).toByte() }
            fixture.body("/big", bytes)
            assertContentEquals(bytes, store.read("big"))
        }

    @Test
    fun aBodyShorterThanItsLengthIsRetried() =
        runTest {
            val store = fixture.store()
            store.writeNew("file", byteArrayOf(1, 2, 3))
            fixture.body("/file", byteArrayOf(1), HttpHeaders.ContentLength to "3")
            assertContentEquals(byteArrayOf(1, 2, 3), store.read("file"))
        }

    @Test
    fun aReadFromAStoreThatWentAwayIsUnavailableNotMissing() =
        runTest {
            val store = fixture.store()
            server.delete("/store")
            assertEquals(UnreachableReason.MISSING, unavailableReason { store.read("a") })
        }

    @Test
    fun writeNewCreatesMissingCollectionsBelowTheRootOnly() =
        runTest {
            val store = fixture.store()
            store.writeNew("a/b/c/d", byteArrayOf(1))
            assertTrue(server.isDirectory("/store/a/b/c"))
            server.delete("/store")
            assertFailsWith<BackendUnavailableException> { store.writeNew("x/y", byteArrayOf(1)) }
            assertFalse(server.isDirectory("/store"))
        }

    @Test
    fun aCollectionCreatedByAnotherWriterMeanwhileIsFine() =
        runTest {
            val store = fixture.store()
            fixture.fault("PUT", "/a/b", HttpStatusCode.Conflict)
            server.faults +=
                FakeDavServer.Fault("MKCOL", "/a/") {
                    server.mkdirs("/store/a")
                    respond("", HttpStatusCode.MethodNotAllowed)
                }
            store.writeNew("a/b", byteArrayOf(1))
            assertContentEquals(byteArrayOf(1), server.fileAt("/store/a/b"))
        }

    @Test
    fun writeNewMapsTheServersRefusals() =
        runTest {
            val store = fixture.store()
            val one = byteArrayOf(1)
            fixture.fault("PUT", "/a", HttpStatusCode.InsufficientStorage)
            val full = assertFailsWith<BlobStoreFailureException> { store.writeNew("a", one) }
            assertContains(full.message.orEmpty(), "507")
            fixture.fault("PUT", "/a", HttpStatusCode.Unauthorized)
            assertEquals(
                UnreachableReason.UNAUTHORIZED,
                unavailableReason { store.writeNew("a", one) },
            )
            fixture.fault("PUT", "/a", HttpStatusCode.Forbidden)
            assertFailsWith<BlobStoreFailureException> { store.writeNew("a", one) }
            repeat(3) { fixture.fault("PUT", "/a", HttpStatusCode.GatewayTimeout) }
            assertFailsWith<BackendUnavailableException> { store.writeNew("a", one) }
            fixture.fault("MKCOL", "/b/", HttpStatusCode.Unauthorized)
            assertFailsWith<BackendUnavailableException> { store.writeNew("b/c", one) }
            fixture.fault("PUT", "/c", HttpStatusCode.Forbidden)
            fixture.fault("PROPFIND", "/c", HttpStatusCode.InternalServerError)
            assertFailsWith<BlobStoreFailureException> { store.writeNew("c", one) }
            assertNull(server.fileAt("/store/a"))
        }

    @Test
    fun aRetriedWriteThatFindsTheFileReportsItAsThere() =
        runTest {
            val store = fixture.store()
            // The first PUT takes effect and its answer is lost.
            server.faults +=
                FakeDavServer.Fault("PUT", "/a") {
                    server.putFile("/store/a", byteArrayOf(1))
                    throw IOException("Connection reset")
                }
            assertFailsWith<BlobAlreadyExistsException> { store.writeNew("a", byteArrayOf(1)) }
        }

    @Test
    fun aWriteThatNeverGetsThroughIsUnavailable() =
        runTest {
            val store = fixture.store()
            repeat(3) { fixture.drop("PUT") }
            assertEquals(
                UnreachableReason.FAILED,
                unavailableReason { store.writeNew("a", byteArrayOf(1)) },
            )
        }

    @Test
    fun aFileOverTheLimitIsNotWritten() =
        runTest {
            val store = fakeStore(server, settings = DavSettings(maxFileBytes = 10))
            store.initialize()
            assertFailsWith<BlobStoreFailureException> { store.writeNew("big", ByteArray(11)) }
            store.writeNew("fits", ByteArray(10))
        }

    @Test
    fun redirectsWithinTheOriginAreFollowedWithMethodAndBody() =
        runTest {
            val store = fixture.store()
            fixture.fault(
                "PUT",
                "/a",
                HttpStatusCode.TemporaryRedirect,
                HttpHeaders.Location to "/store/b",
            )
            store.writeNew("a", byteArrayOf(7))
            assertContentEquals(byteArrayOf(7), server.fileAt("/store/b"))
            listOf("b", "//dav.example/store/b", "$FAKE_ORIGIN/store/b").forEach { location ->
                fixture.fault(
                    "GET",
                    "/x",
                    HttpStatusCode.PermanentRedirect,
                    HttpHeaders.Location to location,
                )
                assertContentEquals(byteArrayOf(7), store.read("x"), location)
            }
        }

    @Test
    fun redirectsElsewhereOrWithoutEndAreRefused() =
        runTest {
            val store = fixture.store()
            val elsewhere = listOf("https://evil.example/store/a", "http://dav.example/store/a")
            elsewhere.forEach { location ->
                fixture.fault("GET", "/a", HttpStatusCode.Found, HttpHeaders.Location to location)
                assertFailsWith<BlobStoreFailureException>(location) { store.read("a") }
            }
            repeat(REDIRECTS_TOO_MANY) {
                fixture.fault("GET", "/a", HttpStatusCode.Found, HttpHeaders.Location to "/store/a")
            }
            val loop = assertFailsWith<BlobStoreFailureException> { store.read("a") }
            assertContains(loop.message.orEmpty(), "redirects")
            // A redirect without a Location is just an answer.
            server.faults.clear()
            fixture.fault("GET", "/a", HttpStatusCode.Found)
            assertFailsWith<BlobNotFoundException> { store.read("a") }
        }

    private companion object {
        const val LARGE = 300_000
        const val PRIME = 251
        const val REDIRECTS_TOO_MANY = 7
    }
}
