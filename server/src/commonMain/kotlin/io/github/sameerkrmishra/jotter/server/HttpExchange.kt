package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.peer.PeerProtocol
import io.ktor.http.cio.parseRequest
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readByte
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.withTimeout
import kotlinx.io.EOFException
import kotlin.time.Duration

/** The most a request line and its headers may take, in bytes. */
internal const val MAX_HEAD_BYTES = 16 * 1024

/**
 * What the hub needs of a request's line and headers. A header that may appear once and appears
 * more than once makes the request malformed, as the two could be read differently by two parsers.
 *
 * @property method The method, such as `GET`.
 * @property target The request target: path and query.
 * @property authorization The `Authorization` header.
 * @property contentLength The `Content-Length` header.
 * @property hasTransferEncoding Whether a `Transfer-Encoding` header is present.
 */
internal data class RequestHead(
    val method: String,
    val target: String,
    val authorization: String?,
    val contentLength: String?,
    val hasTransferEncoding: Boolean,
)

/** A request the hub cannot make sense of. */
internal class MalformedRequestException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Reads a request's line and headers from [input], up to the empty line, and parses them. Returns
 * null if the client closed the connection before sending anything.
 *
 * @throws MalformedRequestException if they take more than [MAX_HEAD_BYTES], end early or do not
 *   parse.
 */
internal suspend fun readRequestHead(input: ByteReadChannel): RequestHead? {
    val head = ByteArray(MAX_HEAD_BYTES)
    var size = 0
    while (!endsWithEmptyLine(head, size)) {
        if (size == head.size) throw MalformedRequestException("The request's head is too long")
        head[size] =
            try {
                input.readByte()
            } catch (_: EOFException) {
                if (size == 0) return null
                throw MalformedRequestException("The request ended in its head")
            }
        size++
    }
    return parseHead(head.copyOf(size))
}

/** Whether the [size] bytes of [head] end with the empty line that ends a head. */
private fun endsWithEmptyLine(
    head: ByteArray,
    size: Int,
): Boolean =
    size >= END_OF_HEAD.size &&
        END_OF_HEAD.indices.all { head[size - END_OF_HEAD.size + it] == END_OF_HEAD[it] }

private val END_OF_HEAD = "\r\n\r\n".encodeToByteArray()

private suspend fun parseHead(bytes: ByteArray): RequestHead {
    val request =
        try {
            parseRequest(ByteReadChannel(bytes))
        } catch (
            // Ktor's ParserException is one, and its parser also fails through error().
            @Suppress("TooGenericExceptionCaught") e: IllegalStateException,
        ) {
            throw MalformedRequestException("The request does not parse", e)
        } ?: throw MalformedRequestException("The request is empty")
    try {
        return RequestHead(
            method = request.method.value,
            target = request.uri.toString(),
            authorization = request.headers.single("Authorization"),
            contentLength = request.headers.single("Content-Length"),
            hasTransferEncoding = request.headers["Transfer-Encoding"] != null,
        )
    } finally {
        request.release()
    }
}

private fun io.ktor.http.cio.HttpHeadersMap.single(name: String): String? {
    val values = getAll(name).toList()
    if (values.size > 1) throw MalformedRequestException("The request has $name twice")
    return values.singleOrNull()?.toString()
}

/**
 * Reads exactly [length] bytes of body from [input]. Each read must bring bytes within
 * [idleTimeout], so a client that stalls is dropped without holding the connection.
 *
 * @throws MalformedRequestException if the body ends early.
 */
internal suspend fun readBody(
    input: ByteReadChannel,
    length: Int,
    idleTimeout: Duration,
): ByteArray {
    val body = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = withTimeout(idleTimeout) { input.readAvailable(body, offset, length - offset) }
        if (read <
            0
        ) {
            throw MalformedRequestException("The body ended after $offset of $length bytes")
        }
        offset += read
    }
    return body
}

/**
 * An answer: [status], and [body] of [contentType]. Every answer closes its connection, so a
 * connection carries one request.
 */
internal class Reply(
    val status: Int,
    val body: ByteArray,
    val contentType: String,
)

/** Writes [reply] to [output], with the protocol header that marks the hub. */
internal suspend fun writeReply(
    output: ByteWriteChannel,
    reply: Reply,
) {
    val head =
        buildString {
            append("HTTP/1.1 ${reply.status} ${reasonPhrase(reply.status)}\r\n")
            append("${PeerProtocol.VERSION_HEADER}: ${PeerProtocol.VERSION}\r\n")
            append("Content-Type: ${reply.contentType}\r\n")
            append("Content-Length: ${reply.body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
    output.writeFully(head.encodeToByteArray())
    output.writeFully(reply.body)
    output.flush()
}

private fun reasonPhrase(status: Int): String =
    when (status) {
        HTTP_OK -> "OK"
        HTTP_CREATED -> "Created"
        else -> "Error"
    }

internal const val HTTP_OK = 200
internal const val HTTP_CREATED = 201
