package io.github.sameerkrmishra.jotter.backend.webdav

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.io.IOException
import kotlin.test.assertFailsWith

/** A fake server with the collection `/store`, and ways to make it misbehave once. */
internal class FakeFixture {
    val server = FakeDavServer().apply { mkdirs("/store") }

    /** A store in `/store`, set up. */
    suspend fun store(): WebDavBlobStore = fakeStore(server).also { it.initialize() }

    /** Answers the next [method] to a path ending in [suffix] with [status] and [headers]. */
    fun fault(
        method: String,
        suffix: String,
        status: HttpStatusCode,
        vararg headers: Pair<String, String>,
    ) {
        val headerMap = headers.map { (name, value) -> name to listOf(value) }.toTypedArray()
        server.faults +=
            FakeDavServer.Fault(method, suffix) { respond("", status, headersOf(*headerMap)) }
    }

    /** Answers the next [method] to a path ending in [suffix] with a multistatus of [responses]. */
    fun multistatus(
        method: String,
        suffix: String,
        responses: String,
    ) {
        server.faults +=
            FakeDavServer.Fault(method, suffix) {
                respond(
                    """<D:multistatus xmlns:D="DAV:">$responses</D:multistatus>""",
                    HttpStatusCode.MultiStatus,
                )
            }
    }

    /** Answers the next GET of a path ending in [suffix] with [bytes] and no `Content-Length`. */
    fun body(
        suffix: String,
        bytes: ByteArray,
        vararg headers: Pair<String, String>,
    ) {
        val headerMap = headers.map { (name, value) -> name to listOf(value) }.toTypedArray()
        server.faults +=
            FakeDavServer.Fault("GET", suffix) {
                respond(ByteReadChannel(bytes), HttpStatusCode.OK, headersOf(*headerMap))
            }
    }

    /** Breaks the connection of the next [method] to a path ending in [suffix]. */
    fun drop(
        method: String,
        suffix: String = "",
    ) {
        server.faults +=
            FakeDavServer.Fault(method, suffix) { throw IOException("Connection reset") }
    }
}

/** A `response` that describes a file of [size] bytes at [href]. */
internal fun fileResponse(
    href: String,
    size: Int,
) = "<D:response><D:href>$href</D:href><D:propstat><D:prop><D:resourcetype/>" +
    "<D:getcontentlength>$size</D:getcontentlength></D:prop>" +
    "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"

/** Asserts that [block] fails as unavailable, and returns why. */
internal suspend fun unavailableReason(block: suspend () -> Unit): UnreachableReason =
    assertFailsWith<BackendUnavailableException> { block() }.reachability.reason
