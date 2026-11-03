package io.github.sameerkrmishra.jotter.backend.webdav

import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `initialize`, `ping` and the constructors, against the fake server. */
class WebDavSetupTest {
    private val fixture = FakeFixture()
    private val server = fixture.server

    @Test
    fun initializeWritesTheMarkerAndCanBeRepeated() =
        runTest {
            val store = fixture.store()
            assertNotNull(server.fileAt("/store/$MARKER_NAME"))
            store.initialize()
            assertEquals(Reachability.Reachable, store.ping())
            assertEquals(emptyList(), store.list(""))
            // The test file is gone.
            assertTrue(server.requests.any { it.startsWith("DELETE /store/$PROBE_PREFIX") })
        }

    @Test
    fun initializeNeedsTheCollection() =
        runTest {
            val reason = unavailableReason { fakeStore(server, "/missing/").initialize() }
            assertEquals(UnreachableReason.MISSING, reason)
            assertFalse(server.isDirectory("/missing"))
            fixture.fault("PROPFIND", "/store/", HttpStatusCode.Conflict)
            assertEquals(UnreachableReason.MISSING, unavailableReason { fakeStore(server).initialize() })
        }

    @Test
    fun initializeRefusesAFile() =
        runTest {
            server.putFile("/file", byteArrayOf(1))
            assertFailsWith<BlobStoreFailureException> { fakeStore(server, "/file").initialize() }
        }

    @Test
    fun initializeRefusesAServerThatIgnoresIfNoneMatch() =
        runTest {
            server.ignoreIfNoneMatch = true
            val store = fakeStore(server)
            val failure = assertFailsWith<BlobStoreFailureException> { store.initialize() }
            assertContains(failure.message.orEmpty(), "ignores If-None-Match")
            assertNull(server.fileAt("/store/$MARKER_NAME"))
            assertEquals(UnreachableReason.NOT_INITIALIZED, unreachable(store).reason)
        }

    @Test
    fun initializeFailsWhenATestWriteFails() =
        runTest {
            fixture.fault("PUT", "", HttpStatusCode.Forbidden)
            assertFailsWith<BlobStoreFailureException> { fakeStore(server).initialize() }
            server.faults += FakeDavServer.Fault("PUT", "") { respond("", HttpStatusCode.Created) }
            fixture.fault("PUT", "", HttpStatusCode.InternalServerError)
            assertFailsWith<BlobStoreFailureException> { fakeStore(server).initialize() }
        }

    @Test
    fun initializeFailsWhenTheMarkerCannotBeWritten() =
        runTest {
            fixture.fault("PUT", MARKER_NAME, HttpStatusCode.InsufficientStorage)
            val failure =
                assertFailsWith<BlobStoreFailureException> { fakeStore(server).initialize() }
            assertContains(failure.message.orEmpty(), "out of space")
        }

    @Test
    fun initializeFailsForAnOddAnswerAboutTheCollection() =
        runTest {
            fixture.fault("PROPFIND", "/store/", HttpStatusCode.Forbidden)
            assertFailsWith<BlobStoreFailureException> { fakeStore(server).initialize() }
        }

    @Test
    fun pingTellsEachKindOfUnreachableStore() =
        runTest {
            assertEquals(UnreachableReason.NOT_INITIALIZED, unreachable(fakeStore(server)).reason)
            assertEquals(
                UnreachableReason.MISSING,
                unreachable(fakeStore(server, "/missing/")).reason,
            )
            val store = fixture.store()
            server.credentials = WebDavCredentials("me@example.org", "revoked")
            assertEquals(UnreachableReason.UNAUTHORIZED, unreachable(store).reason)
            server.credentials = FAKE_CREDENTIALS
            fixture.fault("PROPFIND", "", HttpStatusCode.InternalServerError)
            assertEquals(UnreachableReason.FAILED, unreachable(store).reason)
            server.down = true
            assertEquals(UnreachableReason.FAILED, unreachable(store).reason)
        }

    @Test
    fun pingReportsAnOddAnswerAboutTheRoot() =
        runTest {
            fixture.fault("PROPFIND", "/store/", HttpStatusCode.Forbidden)
            assertEquals(UnreachableReason.FAILED, unreachable(fakeStore(server)).reason)
            // Some servers say 409 for a collection whose parent is gone.
            fixture.fault("PROPFIND", "/store/", HttpStatusCode.Conflict)
            assertEquals(UnreachableReason.MISSING, unreachable(fakeStore(server)).reason)
        }

    @Test
    fun pingReportsARedirectToAnotherServer() =
        runTest {
            val elsewhere = "https://other.example/dav/"
            fixture.fault(
                "PROPFIND",
                "",
                HttpStatusCode.MovedPermanently,
                HttpHeaders.Location to elsewhere,
            )
            val reachability = unreachable(fakeStore(server))
            assertEquals(UnreachableReason.FAILED, reachability.reason)
            assertContains(reachability.detail, elsewhere)
        }

    @Test
    fun theCredentialsKeepThePasswordOutOfText() {
        val credentials = WebDavCredentials("me", "secret password")
        assertFalse("secret password" in credentials.toString())
        assertFailsWith<IllegalArgumentException> { WebDavCredentials("me:you", "x") }
    }

    @Test
    fun thePublicConstructorChecksTheUrl() {
        assertFailsWith<IllegalArgumentException> {
            WebDavBlobStore("http://dav.example/", FAKE_CREDENTIALS)
        }
        WebDavBlobStore("https://dav.example/", FAKE_CREDENTIALS).close()
    }

    private suspend fun unreachable(store: WebDavBlobStore) =
        assertIs<Reachability.Unreachable>(store.ping())
}
