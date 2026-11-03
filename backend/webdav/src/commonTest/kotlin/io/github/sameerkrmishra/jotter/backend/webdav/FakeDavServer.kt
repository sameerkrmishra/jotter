package io.github.sameerkrmishra.jotter.backend.webdav

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException

/** The URL of the fake server, which only the mock engine knows. */
internal const val FAKE_ORIGIN = "https://dav.example"

/** What the fake server's [FakeDavServer.users] accept. */
internal val FAKE_CREDENTIALS = WebDavCredentials("me@example.org", "app pass: wörd")

/**
 * An in-memory WebDAV server inside a Ktor [MockEngine], for tests that need no network. It does
 * what the servers in docs/adr/0015 do, as far as the store relies on it, and can be switched to
 * each of their differences. Requests are handled one at a time, so `If-None-Match: *` is atomic
 * unless [ignoreIfNoneMatch] is set.
 */
internal class FakeDavServer {
    private sealed interface Node

    private class Dir : Node

    private class File(
        val bytes: ByteArray,
    ) : Node

    private val nodes = mutableMapOf<List<String>, Node>(emptyList<String>() to Dir())
    private val lock = Mutex()

    /** Every request, as "METHOD /path", in order. */
    val requests = mutableListOf<String>()

    /** Whether the server is switched off: every request then fails as a refused connection. */
    var down = false

    /** Whether `PUT` replaces a file even with `If-None-Match: *`, as rclone does. */
    var ignoreIfNoneMatch = false

    /** How `href`s are written. */
    var hrefStyle = HrefStyle.UPPER_ESCAPES

    /** The prefix bound to `DAV:` in answers, as Apache uses `lp1` for some properties. */
    var davPrefix = "D"

    /** What a `GET` of a collection answers: 404 (Apache), 405 (rclone) or 301 (lighttpd). */
    var getOfCollection = HttpStatusCode.NotFound

    /** Answers to give instead of handling a request, taken in order by requests they match. */
    val faults = mutableListOf<Fault>()

    /** What `PROPFIND` answers report for a member, by name, instead of its properties. */
    val brokenMembers = mutableMapOf<String, String>()

    /** Credentials the server accepts. */
    var credentials = FAKE_CREDENTIALS

    /** An engine that sends every request here. */
    fun engine(): HttpClientEngine = MockEngine { request -> handle(request) }

    /** Creates the collection at [path], below the server's root, and its parents. */
    fun mkdirs(path: String) {
        val segments = segmentsOf(path)
        segments.indices.forEach { nodes.getOrPut(segments.subList(0, it + 1)) { Dir() } }
    }

    /** Puts a file at [path] directly, as another program could. */
    fun putFile(
        path: String,
        bytes: ByteArray,
    ) {
        nodes[segmentsOf(path)] = File(bytes)
    }

    /** Deletes [path] and everything below it. */
    fun delete(path: String) {
        val segments = segmentsOf(path)
        nodes.keys.removeAll {
            it.size >= segments.size &&
                it.subList(
                    0,
                    segments.size,
                ) == segments
        }
    }

    /** The content of the file at [path], or null. */
    fun fileAt(path: String): ByteArray? = (nodes[segmentsOf(path)] as? File)?.bytes

    /** Whether a collection is at [path]. */
    fun isDirectory(path: String): Boolean = nodes[segmentsOf(path)] is Dir

    private fun segmentsOf(path: String) = path.split('/').filter { it.isNotEmpty() }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val method = request.method.value
        val encodedPath = request.url.encodedPath
        lock.withLock { requests += "$method $encodedPath" }
        if (down) throw IOException("Connection refused")
        val fault =
            lock.withLock {
                faults.firstOrNull { it.matches(method, encodedPath) }?.also { faults.remove(it) }
            }
        return when {
            fault != null -> fault.answer(this)
            request.headers[HttpHeaders.Authorization] != basic(credentials) ->
                respond(
                    "",
                    HttpStatusCode.Unauthorized,
                    headersOf("WWW-Authenticate", "Basic realm=\"fake\""),
                )
            else -> lock.withLock { serve(request) }
        }
    }

    private suspend fun MockRequestHandleScope.serve(request: HttpRequestData): HttpResponseData {
        val encodedPath = request.url.encodedPath
        val segments = encodedPath.split('/').filter { it.isNotEmpty() }.map { it.decodeURLPart() }
        return when (request.method.value) {
            "GET" -> get(segments, encodedPath)
            "PUT" ->
                put(segments, request.body.toByteArray(), request.headers["If-None-Match"] == "*")
            "MKCOL" -> mkcol(segments)
            "DELETE" ->
                respond(
                    "",
                    if (nodes.remove(segments) !=
                        null
                    ) {
                        HttpStatusCode.NoContent
                    } else {
                        HttpStatusCode.NotFound
                    },
                )
            "PROPFIND" -> propfind(segments, request.headers["Depth"] ?: "infinity")
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }

    private fun MockRequestHandleScope.get(
        segments: List<String>,
        encodedPath: String,
    ): HttpResponseData =
        when (val node = nodes[segments]) {
            is File ->
                respond(
                    ByteReadChannel(node.bytes),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentLength, node.bytes.size.toString()),
                )
            is Dir ->
                if (getOfCollection == HttpStatusCode.MovedPermanently &&
                    !encodedPath.endsWith("/")
                ) {
                    respond("", getOfCollection, headersOf(HttpHeaders.Location, "$encodedPath/"))
                } else if (getOfCollection == HttpStatusCode.MovedPermanently) {
                    respond("<html>listing</html>", HttpStatusCode.OK)
                } else {
                    respond("", getOfCollection)
                }
            null -> respond("", HttpStatusCode.NotFound)
        }

    private fun MockRequestHandleScope.put(
        segments: List<String>,
        body: ByteArray,
        noReplace: Boolean,
    ): HttpResponseData {
        val existing = nodes[segments]
        return when {
            nodes[segments.dropLast(1)] !is Dir ->
                respond(
                    "",
                    if (nodes[
                            segments.dropLast(
                                1,
                            ),
                        ] is File
                    ) {
                        HttpStatusCode.BadRequest
                    } else {
                        HttpStatusCode.Conflict
                    },
                )
            existing is Dir -> respond("", HttpStatusCode.MethodNotAllowed)
            existing != null && noReplace && !ignoreIfNoneMatch ->
                respond(
                    "",
                    HttpStatusCode.PreconditionFailed,
                )
            else -> {
                nodes[segments] = File(body.copyOf())
                respond(
                    "Created",
                    if (existing ==
                        null
                    ) {
                        HttpStatusCode.Created
                    } else {
                        HttpStatusCode.NoContent
                    },
                )
            }
        }
    }

    private fun MockRequestHandleScope.mkcol(segments: List<String>): HttpResponseData =
        when {
            nodes.containsKey(segments) -> respond("", HttpStatusCode.MethodNotAllowed)
            nodes[segments.dropLast(1)] !is Dir -> respond("", HttpStatusCode.Conflict)
            else -> {
                nodes[segments] = Dir()
                respond("", HttpStatusCode.Created)
            }
        }

    private fun MockRequestHandleScope.propfind(
        segments: List<String>,
        depth: String,
    ): HttpResponseData {
        val node = nodes[segments] ?: return respond("", HttpStatusCode.NotFound)
        val listed =
            if (node is Dir && depth == "1") {
                listOf(segments) +
                    nodes.keys.filter {
                        it.size == segments.size + 1 &&
                            it.subList(0, segments.size) == segments
                    }
            } else {
                listOf(segments)
            }
        val d = davPrefix
        val xml =
            buildString {
                append("""<?xml version="1.0" encoding="utf-8"?>""")
                append("""<$d:multistatus xmlns:$d="DAV:">""")
                listed.forEach { path -> append(response(path, nodes.getValue(path))) }
                append("</$d:multistatus>")
            }
        return respond(
            xml,
            HttpStatusCode.MultiStatus,
            headersOf(HttpHeaders.ContentType, "application/xml; charset=utf-8"),
        )
    }

    private fun response(
        path: List<String>,
        node: Node,
    ): String {
        val d = davPrefix
        val href = hrefStyle.encode(path) + if (node is Dir) "/" else ""
        brokenMembers[path.lastOrNull()]?.let {
            return "<$d:response><$d:href>$href</$d:href>$it</$d:response>"
        }
        val props =
            when (node) {
                is Dir -> "<$d:resourcetype><$d:collection/></$d:resourcetype>"
                is File ->
                    "<$d:resourcetype/>" +
                        "<$d:getcontentlength>${node.bytes.size}</$d:getcontentlength>"
            }
        // As Apache does, a collection's length is reported as not found.
        val missing =
            if (node is Dir) {
                "<$d:propstat><$d:prop><$d:getcontentlength/></$d:prop>" +
                    "<$d:status>HTTP/1.1 404 Not Found</$d:status></$d:propstat>"
            } else {
                ""
            }
        return "<$d:response><$d:href>$href</$d:href><$d:propstat><$d:prop>$props</$d:prop>" +
            "<$d:status>HTTP/1.1 200 OK</$d:status></$d:propstat>$missing</$d:response>"
    }

    /** An answer injected for requests with [method] whose encoded path ends with [pathSuffix]. */
    class Fault(
        private val method: String,
        private val pathSuffix: String,
        val answer: suspend MockRequestHandleScope.() -> HttpResponseData,
    ) {
        fun matches(
            method: String,
            path: String,
        ) = method == this.method && path.endsWith(pathSuffix)
    }

    /** How a server writes `href`s. */
    enum class HrefStyle {
        UPPER_ESCAPES,
        LOWER_ESCAPES,
        RAW_UNICODE,
        FULL_URL,
        ;

        fun encode(path: List<String>): String {
            val encoded = path.joinToString("/", prefix = "/") { escape(it) }
            return if (this == FULL_URL) FAKE_ORIGIN + encoded else encoded
        }

        private fun escape(segment: String): String =
            buildString {
                segment.forEach { char ->
                    when {
                        char.isLetterOrDigit() && char.code < ASCII_END -> append(char)
                        char in "-._~" -> append(char)
                        this@HrefStyle == RAW_UNICODE && char.code >= ASCII_END -> append(char)
                        else ->
                            char.toString().encodeToByteArray().forEach { byte ->
                                val hex =
                                    (byte.toInt() and BYTE_MASK)
                                        .toString(
                                            HEX,
                                        ).padStart(2, '0')
                                append('%').append(
                                    if (this@HrefStyle ==
                                        LOWER_ESCAPES
                                    ) {
                                        hex
                                    } else {
                                        hex.uppercase()
                                    },
                                )
                            }
                    }
                }
            }
    }

    companion object {
        private const val ASCII_END = 128
        private const val BYTE_MASK = 0xFF
        private const val HEX = 16

        fun basic(credentials: WebDavCredentials): String =
            "Basic " +
                kotlin.io.encoding.Base64.encode(
                    "${credentials.username}:${credentials.password}".encodeToByteArray(),
                )
    }
}
