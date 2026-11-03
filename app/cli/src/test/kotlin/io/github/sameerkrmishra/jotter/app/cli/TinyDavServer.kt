package io.github.sameerkrmishra.jotter.app.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Just enough of a WebDAV server, in this process on 127.0.0.1, for the CLI's tests in `check`:
 * `PROPFIND` with depth 0 or 1, `PUT` with `If-None-Match: *`, `GET`, `MKCOL` and `DELETE`, with
 * Basic authentication. `backend:webdav` is tested against real servers by its live test.
 *
 * @property password What it accepts for the user `me`. Change it to make it refuse the old one.
 * @property ignoresIfNoneMatch Replace files as rclone does, which Jotter must refuse.
 */
internal class TinyDavServer : AutoCloseable {
    @Volatile var password: String = "app-password"

    @Volatile var ignoresIfNoneMatch: Boolean = false

    private val files = ConcurrentHashMap<String, ByteArray>()
    private val collections =
        ConcurrentHashMap.newKeySet<String>().apply {
            addAll(listOf("/", "/dav/"))
        }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** The URL of the collection the tests use. */
    val url: String get() = "http://127.0.0.1:${server.address.port}/dav/"

    init {
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.requestBody.readAllBytes().let { body ->
            val expected =
                "Basic " + Base64.getEncoder().encodeToString("me:$password".toByteArray())
            if (exchange.requestHeaders.getFirst("Authorization") != expected) {
                return exchange.sendResponseHeaders(UNAUTHORIZED, -1)
            }
            val path = exchange.requestURI.rawPath
            when (exchange.requestMethod) {
                "PROPFIND" -> propfind(exchange, path)
                "PUT" -> put(exchange, path, body)
                "GET" ->
                    files[path]?.let {
                        exchange.sendResponseHeaders(OK, it.size.toLong())
                        exchange.responseBody.write(it)
                    } ?: exchange.sendResponseHeaders(NOT_FOUND, -1)
                "MKCOL" -> mkcol(exchange, path)
                "DELETE" -> {
                    val gone = files.remove(path) != null || collections.remove(path)
                    exchange.sendResponseHeaders(if (gone) NO_CONTENT else NOT_FOUND, -1)
                }
                else -> exchange.sendResponseHeaders(NOT_ALLOWED, -1)
            }
        }
    }

    private fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/') + "/"

    private fun put(
        exchange: HttpExchange,
        path: String,
        body: ByteArray,
    ) {
        val status =
            when {
                parentOf(path) !in collections -> CONFLICT
                files.containsKey(path) &&
                    exchange.requestHeaders.getFirst("If-None-Match") == "*" &&
                    !ignoresIfNoneMatch -> PRECONDITION_FAILED
                else -> {
                    files[path] = body
                    CREATED
                }
            }
        exchange.sendResponseHeaders(status, -1)
    }

    private fun mkcol(
        exchange: HttpExchange,
        path: String,
    ) {
        val collection = if (path.endsWith('/')) path else "$path/"
        val status =
            when {
                collection in collections || files.containsKey(path) -> NOT_ALLOWED
                parentOf(collection) !in collections -> CONFLICT
                else -> {
                    collections += collection
                    CREATED
                }
            }
        exchange.sendResponseHeaders(status, -1)
    }

    private fun propfind(
        exchange: HttpExchange,
        path: String,
    ) {
        val collection = if (path.endsWith('/')) path else "$path/"
        val responses =
            when {
                files.containsKey(path) -> listOf(file(path))
                collection in collections -> {
                    val members =
                        if (exchange.requestHeaders.getFirst("Depth") == "1") {
                            collections
                                .filter { it != collection && parentOf(it) == collection }
                                .map(::folder) +
                                files.keys.filter { parentOf(it) == collection }.map(::file)
                        } else {
                            emptyList()
                        }
                    listOf(folder(collection)) + members
                }
                else -> return exchange.sendResponseHeaders(NOT_FOUND, -1)
            }
        val body =
            """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">${responses.joinToString("")}""" +
                "</D:multistatus>"
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/xml")
        exchange.sendResponseHeaders(MULTI_STATUS, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun folder(path: String) =
        response(path, "<D:resourcetype><D:collection/></D:resourcetype>")

    private fun file(path: String) =
        response(
            path,
            "<D:resourcetype/><D:getcontentlength>${files.getValue(
                path,
            ).size}</D:getcontentlength>",
        )

    private fun response(
        href: String,
        props: String,
    ) = "<D:response><D:href>$href</D:href><D:propstat><D:prop>$props</D:prop>" +
        "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"

    override fun close() = server.stop(0)

    private companion object {
        const val OK = 200
        const val CREATED = 201
        const val NO_CONTENT = 204
        const val MULTI_STATUS = 207
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val NOT_ALLOWED = 405
        const val CONFLICT = 409
        const val PRECONDITION_FAILED = 412
    }
}
