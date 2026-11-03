package io.github.sameerkrmishra.jotter.backend.webdav

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent

private val PROPFIND = HttpMethod("PROPFIND")
private val MKCOL = HttpMethod("MKCOL")
private val NO_REPLACE = mapOf("If-None-Match" to "*")

/**
 * The requests a [WebDavBlobStore] makes of its collection at [location], and what their answers
 * mean for the store as a whole: whether it is there, and which exception a status stands for.
 */
internal class DavServer(
    private val location: DavLocation,
    private val client: DavClient,
    private val settings: DavSettings,
) {
    private val unauthorized =
        Reachability.Unreachable(
            UnreachableReason.UNAUTHORIZED,
            "$location refused the user name or password",
        )

    /** The collection does not exist. */
    val missing = Reachability.Unreachable(UnreachableReason.MISSING, "$location does not exist")

    /** The collection has no marker. */
    val notInitialized =
        Reachability.Unreachable(
            UnreachableReason.NOT_INITIALIZED,
            "$location is not set up as a store",
        )

    /** `PROPFIND` of [url] with [depth], for the two properties a listing needs. */
    suspend fun propfind(
        url: String,
        depth: Int,
    ): DavResponse =
        client.send(
            PROPFIND,
            url,
            mapOf("Depth" to depth.toString()),
            ByteArrayContent(PROPFIND_BODY.encodeToByteArray(), ContentType.Application.Xml),
            settings.maxListingBytes,
        )

    /** `PUT` of [bytes] at [segments] with `If-None-Match: *`. Returns the status. */
    suspend fun put(
        segments: List<String>,
        bytes: ByteArray,
    ): Int =
        client
            .send(
                HttpMethod.Put,
                location.fileUrl(segments),
                NO_REPLACE,
                ByteArrayContent(bytes, ContentType.Application.OctetStream),
            ).status

    /**
     * Writes [bytes] to [path] with `If-None-Match: *`, creating the collections above it if they
     * are missing.
     *
     * @throws BlobAlreadyExistsException if something is at [path].
     */
    suspend fun create(
        path: String,
        bytes: ByteArray,
    ) {
        val segments = path.split('/')
        var status = put(segments, bytes)
        // RFC 4918 says 409 for a missing parent, but servers also answer 404, and lighttpd 500
        // when only the direct parent is missing. So anything that may be about the path is.
        val mayBeMissingParent = status != Status.PRECONDITION_FAILED && !Status.isFinal(status)
        if (!Status.isSuccess(status) && mayBeMissingParent && segments.size > 1) {
            createCollections(segments.dropLast(1))
            status = put(segments, bytes)
        }
        if (!Status.isSuccess(status)) throw refusal(path, segments, status)
    }

    /** What a `PUT` of [path] that answered [status] means. */
    private suspend fun refusal(
        path: String,
        segments: List<String>,
        status: Int,
    ) = when {
        status == Status.PRECONDITION_FAILED -> BlobAlreadyExistsException(path)
        Status.isFinal(status) -> failure("write \"$path\"", status)
        // Servers answer a PUT to a collection with 405, 409 or 412, and one below a file with
        // 400, 404 or 409. Only a look tells which it was.
        exists(segments) -> BlobAlreadyExistsException(path)
        else -> failure("write \"$path\"", status).also { requireReachable() }
    }

    /**
     * Creates the collection at [segments] and any missing above it, below the store's root, which
     * it never creates: a missing root means the store is gone. Other writers may create the same
     * collections at the same time, which is fine. Something that is in the way, such as a file,
     * is left for the request that follows to fail on.
     */
    private suspend fun createCollections(segments: List<String>) {
        if (segments.isEmpty()) return requireReachable()
        val url = location.collectionUrl(segments)
        val status = client.send(MKCOL, url).status
        if (Status.isFinal(status)) throw failure("create $url", status)
        if (status == Status.CONFLICT || status == Status.NOT_FOUND) {
            createCollections(segments.dropLast(1))
            client.send(MKCOL, url)
        }
    }

    /** Whether there is a file at [segments]. */
    suspend fun isFile(segments: List<String>): Boolean {
        val response = propfind(location.fileUrl(segments), depth = 0)
        return response.status == Status.MULTI_STATUS &&
            parseMultistatus(response.body.decodeToString()).any { it.isCollection == false }
    }

    /** Whether there is anything at [segments], a file or a collection. */
    suspend fun exists(segments: List<String>): Boolean {
        val url = location.fileUrl(segments)
        val response = propfind(url, depth = 0)
        return when (response.status) {
            Status.MULTI_STATUS -> true
            Status.NOT_FOUND, Status.CONFLICT -> false
            else -> throw failure("examine $url", response.status)
        }
    }

    /**
     * Whether the store is there: its marker, or else its collection. May throw what [DavClient]
     * throws.
     */
    suspend fun reachability(): Reachability {
        val marker = propfind(location.fileUrl(listOf(MARKER_NAME)), depth = 0).status
        val root =
            if (marker ==
                Status.NOT_FOUND
            ) {
                propfind(location.rootUrl, depth = 0).status
            } else {
                marker
            }
        return when {
            marker == Status.MULTI_STATUS -> Reachability.Reachable
            root == Status.MULTI_STATUS -> notInitialized
            root == Status.NOT_FOUND || root == Status.CONFLICT -> missing
            root == Status.UNAUTHORIZED -> unauthorized
            else ->
                Reachability.Unreachable(
                    UnreachableReason.FAILED,
                    "$location answered with status $root",
                )
        }
    }

    /** Throws [BackendUnavailableException] unless the store is there. */
    suspend fun requireReachable() {
        val reachability = reachability()
        if (reachability is Reachability.Unreachable) {
            throw BackendUnavailableException(
                reachability,
            )
        }
    }

    /** "Not found" for [path], once it is certain that the store itself is there. */
    suspend fun notFound(path: String): BlobNotFoundException {
        requireReachable()
        return BlobNotFoundException(path)
    }

    /** The failure for [status], which the caller did not expect for [action]. */
    fun failure(
        action: String,
        status: Int,
    ): BlobStoreException =
        when (status) {
            Status.UNAUTHORIZED -> BackendUnavailableException(unauthorized)
            in Status.TRANSIENT ->
                BackendUnavailableException(
                    Reachability.Unreachable(
                        UnreachableReason.FAILED,
                        "Cannot $action: the server is unavailable (status $status)",
                    ),
                )
            Status.INSUFFICIENT_STORAGE ->
                BlobStoreFailureException("Cannot $action: the server is out of space (507)")
            else -> BlobStoreFailureException("Cannot $action: the server answered $status")
        }
}
