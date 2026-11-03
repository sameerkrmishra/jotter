package io.github.sameerkrmishra.jotter.backend.peer

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.InvalidBlobPathException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.api.isValidBlobSegment
import io.github.sameerkrmishra.jotter.backend.peer.dto.BlobEntryDto
import io.github.sameerkrmishra.jotter.backend.peer.dto.ErrorDto
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

private const val MAX_ERROR_BYTES = 64 * 1024

/**
 * How [PeerBlobStore] reads the answers of the hub at [address]: strictly, so that an answer no
 * hub would give fails instead of passing as a smaller listing or a shorter file.
 */
internal class PeerAnswers(
    private val address: PeerAddress,
) {
    private val unauthorized =
        Reachability.Unreachable(
            UnreachableReason.FAILED,
            "Hub $address did not accept this device's token. Pair the device again.",
        )

    /** Throws unless [response] carries the protocol header, which every hub sends. */
    fun requireHub(response: HttpResponse) {
        if (response.headers[PeerProtocol.VERSION_HEADER] != "${PeerProtocol.VERSION}") {
            throw BackendUnavailableException(
                Reachability.Unreachable(
                    UnreachableReason.FAILED,
                    "$address does not answer as a Jotter hub of protocol ${PeerProtocol.VERSION}",
                ),
            )
        }
    }

    /** The failure for a hub that cannot be reached, because of [cause]. */
    fun gone(cause: Throwable) =
        BackendUnavailableException(
            Reachability.Unreachable(
                UnreachableReason.FAILED,
                "Cannot reach hub $address: $cause",
            ),
            cause,
        )

    /** The body of [response], which must have [status], of at most [limit] bytes. */
    suspend fun body(
        response: HttpResponse,
        status: Int,
        path: String?,
        limit: Int,
    ): ByteArray = boundedBody(expect(response, status, path), limit)

    /**
     * Returns [response] if it has [status], or throws the failure it describes. [path] is the
     * path or prefix of the request, or null for a ping.
     */
    suspend fun expect(
        response: HttpResponse,
        status: Int,
        path: String?,
    ): HttpResponse {
        if (response.status.value == status) return response
        val error =
            try {
                json.decodeFromString<ErrorDto>(
                    boundedBody(response, MAX_ERROR_BYTES).decodeToString(),
                )
            } catch (_: IllegalArgumentException) {
                // A SerializationException is one: the body is not an error of the protocol.
                null
            } catch (_: BlobStoreFailureException) {
                null
            }
        throw failure(response.status.value, error, path)
    }

    /**
     * Turns a listing into [BlobInfo]s, after checking that it is one a store could give for
     * [prefix]: valid paths below it, sizes that are not negative, sorted without repeats.
     */
    fun listing(
        prefix: String,
        body: ByteArray,
    ): List<BlobInfo> {
        val entries =
            decodeListing(body)
                ?: throw BlobStoreFailureException("Hub $address sent a malformed listing")
        val below = if (prefix.isEmpty()) "" else "$prefix/"
        val valid =
            entries.withIndex().all { (index, entry) ->
                entry.path.startsWith(below) &&
                    entry.path.split('/').all(::isValidBlobSegment) &&
                    entry.size >= 0 &&
                    (index == 0 || entries[index - 1].path < entry.path)
            }
        if (!valid) {
            throw BlobStoreFailureException(
                "Hub $address sent a listing no store could give",
            )
        }
        return entries.map { BlobInfo(it.path, it.size) }
    }

    private fun decodeListing(body: ByteArray): List<BlobEntryDto>? =
        try {
            json.decodeFromString<List<BlobEntryDto>>(body.decodeToString())
        } catch (_: IllegalArgumentException) {
            null // SerializationException, or bytes that are not UTF-8 JSON at all.
        }

    private fun failure(
        status: Int,
        error: ErrorDto?,
        path: String?,
    ): BlobStoreException {
        // A code only counts with its own status, so a confused server cannot fake one.
        val code = PeerError.entries.firstOrNull { it.name == error?.code && it.status == status }
        val detail = error?.detail ?: "no reason given"
        return when {
            code == PeerError.UNAUTHORIZED -> BackendUnavailableException(unauthorized)
            code == PeerError.UNAVAILABLE -> {
                val reason = UnreachableReason.entries.firstOrNull { it.name == error?.reason }
                BackendUnavailableException(
                    Reachability.Unreachable(
                        reason ?: UnreachableReason.FAILED,
                        "Hub $address: $detail",
                    ),
                )
            }
            path != null && code == PeerError.INVALID_PATH -> InvalidBlobPathException(path, detail)
            path != null && code == PeerError.NOT_FOUND -> BlobNotFoundException(path)
            path != null && code == PeerError.ALREADY_EXISTS -> BlobAlreadyExistsException(path)
            else -> BlobStoreFailureException("Hub $address answered $status: $detail")
        }
    }

    /**
     * Reads the whole body, which must have the length the answer declares and at most [limit]
     * bytes. A body cut short is a hub that went away, never a shorter file or listing.
     */
    private suspend fun boundedBody(
        response: HttpResponse,
        limit: Int,
    ): ByteArray {
        val declared = response.contentLength()
        if (declared == null || declared > limit) {
            throw BlobStoreFailureException(
                "Hub $address sent an answer of ${declared ?: "unknown"} bytes, not up to $limit",
            )
        }
        val bytes = response.bodyAsChannel().readBuffer(declared + 1).readByteArray()
        if (bytes.size.toLong() != declared) {
            throw BackendUnavailableException(
                Reachability.Unreachable(
                    UnreachableReason.FAILED,
                    "Hub $address sent ${bytes.size} of $declared bytes",
                ),
            )
        }
        return bytes
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
