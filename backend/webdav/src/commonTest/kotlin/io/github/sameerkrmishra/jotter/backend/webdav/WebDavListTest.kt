package io.github.sameerkrmishra.jotter.backend.webdav

import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `list`, against the fake server: every answer read in full, or the call fails. */
class WebDavListTest {
    private val fixture = FakeFixture()
    private val server = fixture.server

    @Test
    fun listReadsEveryWayOfWritingHrefs() =
        runTest {
            val store = fixture.store()
            store.writeNew("d/café 日", byteArrayOf(1, 2))
            store.writeNew("d/sub/x y", byteArrayOf())
            FakeDavServer.HrefStyle.entries.forEach { style ->
                server.hrefStyle = style
                server.davPrefix = if (style.ordinal % 2 == 0) "D" else "lp1"
                assertEquals(
                    listOf(BlobInfo("d/café 日", 2), BlobInfo("d/sub/x y", 0)),
                    store.list("d"),
                    style.name,
                )
            }
        }

    @Test
    fun listSkipsNamesThatCannotBePaths() =
        runTest {
            val store = fixture.store()
            server.putFile("/store/a:b", byteArrayOf(1))
            server.mkdirs("/store/.jotter-tmp/x")
            server.putFile("/store/.jotter-tmp/x/y", byteArrayOf(1))
            server.mkdirs("/store/back\\slash")
            server.putFile("/store/back\\slash/z", byteArrayOf(1))
            store.writeNew("ok", byteArrayOf(1))
            assertEquals(listOf(BlobInfo("ok", 1)), store.list(""))
        }

    @Test
    fun listOfTheRootNeedsTheMarker() =
        runTest {
            val store = fixture.store()
            server.delete("/store/$MARKER_NAME")
            assertEquals(UnreachableReason.NOT_INITIALIZED, unavailableReason { store.list("") })
        }

    @Test
    fun aMemberThatCannotBeDescribedFailsTheListing() =
        runTest {
            val store = fixture.store()
            store.writeNew("d/a", byteArrayOf(1))
            store.writeNew("d/b", byteArrayOf(1))
            val ok = "<D:status>HTTP/1.1 200 OK</D:status>"
            listOf(
                "<D:status>HTTP/1.1 403 Forbidden</D:status>",
                "<D:propstat><D:prop><D:getcontentlength>1</D:getcontentlength></D:prop>" +
                    "$ok</D:propstat>",
                "<D:propstat><D:prop><D:resourcetype/></D:prop>$ok</D:propstat>",
                ok,
            ).forEach { broken ->
                server.brokenMembers["b"] = broken
                assertFailsWith<BlobStoreFailureException>(broken) { store.list("d") }
            }
        }

    @Test
    fun aListingWithAStrangerOrADuplicateFails() =
        runTest {
            val store = fixture.store()
            store.writeNew("d/a", byteArrayOf(1))
            val ok = "<D:status>HTTP/1.1 200 OK</D:status>"
            listOf(
                "<D:response><D:href>/elsewhere/x</D:href>$ok</D:response>",
                "<D:response><D:href>/other/d/x</D:href>$ok</D:response>",
                fileResponse("/store/d/a", 1),
            ).forEach { extra ->
                fixture.multistatus("PROPFIND", "/store/d", extra + fileResponse("/store/d/a", 1))
                assertFailsWith<BlobStoreFailureException>(extra) { store.list("d") }
            }
            // An href that is no path at all is skipped like a name that is not UTF-8.
            val relative = "<D:response><D:href>relative</D:href>$ok</D:response>"
            fixture.multistatus("PROPFIND", "/store/d", relative + fileResponse("/store/d/a", 1))
            assertEquals(listOf("d/a"), store.list("d").map { it.path })
        }

    @Test
    fun aCollectionThatDoesNotDescribeItselfIsFineWithMembersButNotEmpty() =
        runTest {
            val store = fixture.store()
            fixture.multistatus("PROPFIND", "/store/d", fileResponse("/store/d/a", 1))
            assertEquals(listOf(BlobInfo("d/a", 1)), store.list("d"))
            fixture.multistatus("PROPFIND", "/store/d", "")
            assertFailsWith<BlobStoreFailureException> { store.list("d") }
        }

    @Test
    fun aCollectionThatGoesOrChangesDuringTheListingFailsIt() =
        runTest {
            val store = fixture.store()
            store.writeNew("d/sub/a", byteArrayOf(1))
            fixture.fault("PROPFIND", "/store/d/sub/", HttpStatusCode.NotFound)
            assertFailsWith<BlobStoreFailureException> { store.list("d") }
            fixture.multistatus("PROPFIND", "/store/d/sub/", fileResponse("/store/d/sub", 1))
            assertFailsWith<BlobStoreFailureException> { store.list("d") }
        }

    @Test
    fun aListingRedirectedToANameThatIsNotUtf8Fails() =
        runTest {
            val store = fixture.store()
            fixture.fault(
                "PROPFIND",
                "/store/d",
                HttpStatusCode.MovedPermanently,
                HttpHeaders.Location to "/store/%ff/",
            )
            fixture.multistatus("PROPFIND", "/store/%ff/", fileResponse("/store/x", 1))
            assertFailsWith<BlobStoreFailureException> { store.list("d") }
        }

    @Test
    fun aListingAnswerThatIsNotXmlFails() =
        runTest {
            val store = fixture.store()
            server.faults +=
                FakeDavServer.Fault(
                    "PROPFIND",
                    "/store/",
                ) { respond("<html>", HttpStatusCode.MultiStatus) }
            assertFailsWith<BlobStoreFailureException> { store.list("") }
        }

    @Test
    fun aListingAnswerOverTheLimitFails() =
        runTest {
            val store = fakeStore(server, settings = DavSettings(maxListingBytes = 600))
            store.initialize()
            repeat(5) { store.writeNew("d/file-$it", byteArrayOf(1)) }
            val failure = assertFailsWith<BlobStoreFailureException> { store.list("d") }
            assertContains(failure.message.orEmpty(), "limit")
        }

    @Test
    fun listOfAPathBelowAFileIsEmptyWhateverTheServerAnswers() =
        runTest {
            val store = fixture.store()
            store.writeNew("a/file", byteArrayOf(1))
            fixture.fault("PROPFIND", "/store/a/file/below", HttpStatusCode.BadRequest)
            assertEquals(emptyList(), store.list("a/file/below"))
            fixture.fault("PROPFIND", "/store/a/missing", HttpStatusCode.Forbidden)
            assertFailsWith<BlobStoreFailureException> { store.list("a/missing") }
        }

    @Test
    fun listFailsAsUnavailableForCredentialsAndOutages() =
        runTest {
            val store = fixture.store()
            fixture.fault("PROPFIND", "", HttpStatusCode.Unauthorized)
            assertEquals(UnreachableReason.UNAUTHORIZED, unavailableReason { store.list("x") })
            repeat(3) { fixture.fault("PROPFIND", "", HttpStatusCode.ServiceUnavailable) }
            assertEquals(UnreachableReason.FAILED, unavailableReason { store.list("x") })
            repeat(3) { fixture.drop("PROPFIND") }
            assertEquals(UnreachableReason.FAILED, unavailableReason { store.list("x") })
        }
}
