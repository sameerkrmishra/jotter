package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.InvalidBlobPathException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.requireValidBlobPath
import io.github.sameerkrmishra.jotter.backend.peer.PeerError
import io.github.sameerkrmishra.jotter.backend.peer.PeerProtocol
import io.github.sameerkrmishra.jotter.backend.peer.dto.BlobEntryDto
import io.github.sameerkrmishra.jotter.backend.peer.dto.ErrorDto
import io.github.sameerkrmishra.jotter.core.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json

private const val JSON = "application/json"
private const val BYTES = "application/octet-stream"

// Enough digits for any length up to the limit and more; a longer one is not a number to us.
private const val MAX_LENGTH_DIGITS = 12
private const val MAX_UTF8_BYTES = 3
private const val ESCAPE_LENGTH = 3
private const val HEX = 16
private const val HEX_DIGITS = "0123456789abcdefABCDEF"

/**
 * Answers one request of the peer protocol (`backend.peer.PeerProtocol`) from [store], for a
 * client that [authenticate] accepts. Nothing reaches the store without a valid token, and no path
 * reaches it unchecked: the store checks again, as every store does.
 *
 * [transfers] bounds how many files are held in memory at once, by reads and writes together.
 */
internal class PeerHandler(
    private val store: BlobStore,
    private val authenticate: (authorization: String?) -> Boolean,
    private val transfers: Semaphore,
) {
    /**
     * Answers [head]. [body] reads the request's body, of the length given, and is only called for
     * a write that is allowed to send one.
     *
     * @throws MalformedRequestException if the body breaks off.
     */
    suspend fun handle(
        head: RequestHead,
        body: suspend (length: Int) -> ByteArray,
    ): Reply =
        try {
            if (!authenticate(head.authorization)) {
                log.info { "Refused ${describe(head)}: no valid token" }
                throw refusal(PeerError.UNAUTHORIZED, "A valid token is needed")
            }
            route(head, body)
        } catch (e: Refusal) {
            e.reply
        } catch (e: InvalidBlobPathException) {
            error(PeerError.INVALID_PATH, e.message.toString())
        } catch (e: BlobNotFoundException) {
            error(PeerError.NOT_FOUND, e.message.toString())
        } catch (e: BlobAlreadyExistsException) {
            error(PeerError.ALREADY_EXISTS, e.message.toString())
        } catch (e: BackendUnavailableException) {
            unavailable(e.reachability)
        } catch (e: BlobStoreFailureException) {
            error(PeerError.FAILED, e.message.toString())
        } catch (e: MalformedRequestException) {
            throw e // The body broke off, so nobody waits for an answer.
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A bug in the store. Its message could say anything, so it stays here and in the log.
            log.error(e) { "The hub failed to answer ${describe(head)}" }
            error(PeerError.FAILED, "The hub failed")
        }

    private suspend fun route(
        head: RequestHead,
        body: suspend (length: Int) -> ByteArray,
    ): Reply {
        val path = head.target.substringBefore('?')
        val parameters = parametersOf(head.target.substringAfter('?', ""))
        val hasBody =
            !(head.contentLength == null || head.contentLength == "0") || head.hasTransferEncoding
        return when {
            head.method == "PUT" && path == PeerProtocol.BLOB ->
                write(parameters.only(PeerProtocol.PATH_PARAMETER), head, body)
            hasBody -> throw refusal(PeerError.BAD_REQUEST, "Only a write has a body")
            head.method == "GET" && path == PeerProtocol.PING && parameters.isEmpty() -> ping()
            head.method == "GET" && path == PeerProtocol.LIST ->
                list(parameters.only(PeerProtocol.PREFIX_PARAMETER))
            head.method == "GET" && path == PeerProtocol.BLOB ->
                read(parameters.only(PeerProtocol.PATH_PARAMETER))
            else ->
                throw refusal(
                    PeerError.BAD_REQUEST,
                    "Not a request of protocol ${PeerProtocol.VERSION}",
                )
        }
    }

    private suspend fun ping(): Reply =
        when (val reachability = store.ping()) {
            Reachability.Reachable -> Reply(HTTP_OK, "{}".encodeToByteArray(), JSON)
            is Reachability.Unreachable -> unavailable(reachability)
        }

    private suspend fun list(prefix: String): Reply {
        val entries = store.list(prefix).map { BlobEntryDto(it.path, it.size) }
        return Reply(HTTP_OK, json.encodeToString(entries).encodeToByteArray(), JSON)
    }

    /**
     * Reads the file under a transfer permit, which the answer keeps until it is sent, so that a
     * slow reader still counts towards the files in memory.
     */
    private suspend fun read(path: String): Reply {
        transfers.acquire()
        var answered = false
        try {
            val bytes = store.read(path)
            if (bytes.size > PeerProtocol.MAX_FILE_BYTES) {
                throw refusal(PeerError.FAILED, "\"$path\" is larger than a file may be")
            }
            return Reply(HTTP_OK, bytes, BYTES, transfers::release).also { answered = true }
        } finally {
            if (!answered) transfers.release()
        }
    }

    private suspend fun write(
        path: String,
        head: RequestHead,
        body: suspend (length: Int) -> ByteArray,
    ): Reply {
        // All of this before reading a byte of the body.
        requireValidBlobPath(path)
        val length = bodyLength(head)
        return transfers.withPermit {
            store.writeNew(path, body(length))
            Reply(HTTP_CREATED, ByteArray(0), BYTES)
        }
    }

    private fun unavailable(reachability: Reachability.Unreachable) =
        error(PeerError.UNAVAILABLE, reachability.detail, reachability.reason.name)

    /** A request the hub answers with [reply] instead of serving it. */
    private class Refusal(
        val reply: Reply,
        cause: Throwable?,
    ) : Exception(reply.status.toString(), cause)

    private companion object {
        val json = Json

        fun error(
            code: PeerError,
            detail: String,
            reason: String? = null,
        ) = Reply(
            code.status,
            json.encodeToString(ErrorDto(code.name, detail, reason)).encodeToByteArray(),
            JSON,
        )

        // Returned and thrown at the call, not thrown here: JaCoCo counts a line whose call
        // throws as not run.
        fun refusal(
            code: PeerError,
            detail: String,
            cause: Throwable? = null,
        ) = Refusal(error(code, detail), cause)

        /** The query's parameters. Every name must appear once. */
        fun parametersOf(query: String): Map<String, String> {
            val pairs =
                query.split('&').filter { it.isNotEmpty() }.map { part ->
                    decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
                }
            if (pairs.map { it.first }.toSet().size != pairs.size) {
                throw refusal(PeerError.BAD_REQUEST, "A parameter repeats")
            }
            return pairs.toMap()
        }

        /**
         * Decodes one part of a query as Ktor's client encodes it: `+` is a space and `%XX` a
         * byte. The bytes must be UTF-8, strictly: a path with invalid UTF-8 is refused, never
         * given replacement characters.
         */
        fun decode(text: String): String {
            val bytes = ByteArray(text.length * MAX_UTF8_BYTES)
            var size = 0
            var index = 0
            while (index < text.length) {
                val char = text[index]
                val escape = text.substring(index + 1, minOf(index + ESCAPE_LENGTH, text.length))
                val escaped =
                    char == '%' &&
                        escape.length == ESCAPE_LENGTH - 1 &&
                        escape.all { it in HEX_DIGITS }
                when {
                    escaped -> bytes[size++] = escape.toInt(HEX).toByte()
                    char == '%' -> throw refusal(
                        PeerError.BAD_REQUEST,
                        "The query has a bad escape",
                    )
                    char == '+' -> bytes[size++] = ' '.code.toByte()
                    else -> char.toString().encodeToByteArray().forEach { bytes[size++] = it }
                }
                index += if (escaped) ESCAPE_LENGTH else 1
            }
            return try {
                bytes.decodeToString(0, size, throwOnInvalidSequence = true)
            } catch (e: CharacterCodingException) {
                throw refusal(PeerError.BAD_REQUEST, "The query is not UTF-8", e)
            }
        }

        /** The value of [name], which must be the only parameter. */
        fun Map<String, String>.only(name: String): String =
            this[name]?.takeIf { size == 1 }
                ?: throw refusal(
                    PeerError.BAD_REQUEST,
                    "The request takes \"$name\" and nothing else",
                )

        /** The length of a write's body, which must be given, plainly, and within the limit. */
        @Suppress("ThrowsCount") // One refusal per HTTP status.
        fun bodyLength(head: RequestHead): Int {
            val declared = head.contentLength?.takeUnless { head.hasTransferEncoding }
            if (declared == null) throw refusal(PeerError.LENGTH_REQUIRED, "Send a Content-Length")
            val isNumber =
                declared.isNotEmpty() &&
                    declared.length <= MAX_LENGTH_DIGITS &&
                    declared.all(Char::isDigit)
            if (!isNumber) throw refusal(PeerError.BAD_REQUEST, "Content-Length is not a number")
            val length = declared.toLong()
            if (length > PeerProtocol.MAX_FILE_BYTES) {
                throw refusal(
                    PeerError.TOO_LARGE,
                    "A file may have ${PeerProtocol.MAX_FILE_BYTES} bytes",
                )
            }
            return length.toInt()
        }
    }
}

private val log = Logger("hub")

/** A request for the log: its method and endpoint, without the path it names. */
private fun describe(head: RequestHead) = "${head.method} ${head.target.substringBefore('?')}"
