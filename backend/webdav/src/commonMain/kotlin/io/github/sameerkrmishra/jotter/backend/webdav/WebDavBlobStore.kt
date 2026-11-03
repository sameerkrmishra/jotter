package io.github.sameerkrmishra.jotter.backend.webdav

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.RESERVED_SEGMENT_PREFIX
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.api.isValidBlobSegment
import io.github.sameerkrmishra.jotter.backend.api.requireValidBlobPath
import io.github.sameerkrmishra.jotter.backend.api.requireValidBlobPrefix
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpMethod
import kotlin.random.Random

/** The file that marks a collection as a store. See [WebDavBlobStore.initialize]. */
internal const val MARKER_NAME: String = "${RESERVED_SEGMENT_PREFIX}store"

/** The start of the name of the file that [WebDavBlobStore.initialize] tests the server with. */
internal const val PROBE_PREFIX: String = "${RESERVED_SEGMENT_PREFIX}probe-"

/**
 * A [BlobStore] in a collection on a WebDAV server, such as mailbox.org's or a Nextcloud. See
 * `docs/adr/0015-webdav-backend.md` for what it relies on and which servers it was tested with.
 *
 * The collection must exist. Before first use it is set up with [initialize], which also refuses
 * a server that would replace files. Until then the store counts as unreachable.
 *
 * [writeNew] is `PUT` with `If-None-Match: *`. It never replaces a file on a server that honours
 * that header, which [initialize] checks. That two writers racing for one path cannot both win
 * is up to the server, as no WebDAV request makes it certain: Apache's `mod_dav` lets both win.
 * Sync does not need it, because a path has one possible content (ADR-0014).
 *
 * Holds an HTTP client: [close] it when done.
 */
public class WebDavBlobStore internal constructor(
    private val location: DavLocation,
    credentials: WebDavCredentials,
    engine: HttpClientEngine,
    private val settings: DavSettings,
) : BlobStore,
    AutoCloseable {
    /**
     * Creates a store in the collection at [url], signing in with [credentials]. [url] must be
     * `https`, or `http` to this machine only, because the password and every note would otherwise
     * cross the network in the clear. This sends nothing.
     *
     * @throws IllegalArgumentException if [url] is not `https`, or `http` to this machine, or if
     *   it holds a user name, a query or a fragment.
     */
    public constructor(url: String, credentials: WebDavCredentials) : this(
        DavLocation.parse(url),
        credentials,
        CIO.create {
            // The engine's own limit of 15 s would cut large files short. DavSettings has them.
            requestTimeout = 0
        },
        DavSettings(),
    )

    private val client = DavClient(engine, location.origin, credentials, settings)
    private val server = DavServer(location, client, settings)

    /**
     * Sets the collection up as a store. Doing so again is harmless. Call this when the user adds
     * the server as a backend, never because [ping] said [UnreachableReason.NOT_INITIALIZED].
     *
     * First it checks that the server keeps files: it writes a test file twice with
     * `If-None-Match: *`, and the second write must fail. A server that ignores the header would
     * let a retried write replace a file, so it is refused.
     *
     * @throws BackendUnavailableException if the collection does not exist, the server refuses the
     *   credentials, or it cannot be reached.
     * @throws BlobStoreFailureException if the server replaces files or fails in any other way.
     */
    public suspend fun initialize(): Unit =
        call("set up $location") {
            val root = server.propfind(location.rootUrl, depth = 0)
            val isCollection =
                root.status == Status.MULTI_STATUS &&
                    parseMultistatus(root.body.decodeToString()).any { it.isCollection == true }
            val problem =
                when {
                    isCollection -> null
                    root.status == Status.MULTI_STATUS ->
                        BlobStoreFailureException("$location is not a collection")
                    root.status == Status.NOT_FOUND || root.status == Status.CONFLICT ->
                        BackendUnavailableException(server.missing)
                    else -> server.failure("examine $location", root.status)
                }
            problem?.let { throw it }
            checkNoReplace()
            val marker = server.put(listOf(MARKER_NAME), ByteArray(0))
            if (!Status.isSuccess(marker) && marker != Status.PRECONDITION_FAILED) {
                throw server.failure("create the marker file in $location", marker)
            }
        }

    private suspend fun checkNoReplace() {
        val probe = listOf(PROBE_PREFIX + Random.nextLong().toULong().toString(HEX))
        val first = server.put(probe, PROBE_CONTENT)
        val second = if (Status.isSuccess(first)) server.put(probe, PROBE_CONTENT) else first
        // The test file is the store's own, and a leftover would do no harm, so the answer to
        // this is not checked.
        client.send(HttpMethod.Delete, location.fileUrl(probe))
        if (Status.isSuccess(first) && Status.isSuccess(second)) {
            throw BlobStoreFailureException(
                "The server at $location ignores If-None-Match: *, so it would let one write " +
                    "replace another. Jotter cannot sync through it safely",
            )
        }
        if (second !=
            Status.PRECONDITION_FAILED
        ) {
            throw server.failure("write a test file in $location", second)
        }
    }

    override suspend fun list(prefix: String): List<BlobInfo> {
        requireValidBlobPrefix(prefix)
        return call("list \"$prefix\"") {
            val top = if (prefix.isEmpty()) emptyList() else prefix.split('/')
            // Without a trailing "/", so that a file answers for itself, as Apache refuses "file/".
            val first = listing(top, if (top.isEmpty()) location.rootUrl else location.fileUrl(top))
            val found = mutableListOf<BlobInfo>()
            when {
                first == null -> server.requireReachable()
                prefix.isEmpty() && first.members.none { it.name == MARKER_NAME } ->
                    throw BackendUnavailableException(server.notInitialized)
                first.isCollection -> collect(top, first, found)
            }
            found.sortedBy { it.path }
        }
    }

    private suspend fun collect(
        directory: List<String>,
        listing: Listing,
        into: MutableList<BlobInfo>,
    ) {
        listing.members.filter { isValidBlobSegment(it.name) }.forEach { member ->
            val path = directory + member.name
            if (member.isCollection) {
                val url = location.collectionUrl(path)
                val below = listing(path, url)
                if (below?.isCollection != true) {
                    server.requireReachable()
                    throw BlobStoreFailureException("$url changed while it was listed")
                }
                collect(path, below, into)
            } else {
                into += BlobInfo(path.joinToString("/"), member.size)
            }
        }
    }

    /**
     * `PROPFIND` with `Depth: 1` of [path] at [url]. Returns null if there is nothing there, which
     * includes a path below a file, however the server says so.
     */
    private suspend fun listing(
        path: List<String>,
        url: String,
    ): Listing? {
        val response = server.propfind(url, depth = 1)
        val status = response.status
        return when {
            status == Status.MULTI_STATUS -> listingOf(response)
            status == Status.NOT_FOUND -> null
            Status.isFinal(status) -> throw server.failure("list $url", status)
            // Servers answer for a path below a file with 400, 403 or 409.
            path.indices.any { server.isFile(path.subList(0, it + 1)) } -> null
            else -> throw server.failure("list $url", status)
        }
    }

    override suspend fun read(path: String): ByteArray {
        requireValidBlobPath(path)
        return call("read \"$path\"") {
            val segments = path.split('/')
            val url = location.fileUrl(segments)
            val response = client.send(HttpMethod.Get, url, limit = settings.maxFileBytes)
            val status = response.status
            // A redirect to the same path with a "/" means a collection, as on lighttpd.
            if (Status.isSuccess(status) && response.url != "$url/") return@call response.body
            // Servers refuse a GET of a collection, or below a file, with 403, 405 or 409. Only a
            // file that exists and cannot be read is a failure.
            val isFailure =
                Status.isFinal(status) ||
                    status != Status.NOT_FOUND &&
                    !Status.isSuccess(status) &&
                    server.isFile(segments)
            throw if (isFailure) server.failure("read $url", status) else server.notFound(path)
        }
    }

    override suspend fun writeNew(
        path: String,
        bytes: ByteArray,
    ) {
        requireValidBlobPath(path)
        call("write \"$path\"") {
            if (bytes.size > settings.maxFileBytes) {
                throw BlobStoreFailureException(
                    "\"$path\" is over the limit of ${settings.maxFileBytes} bytes",
                )
            }
            // A copy, so that the caller may change its array while the request is retried.
            server.create(path, bytes.copyOf())
        }
    }

    override suspend fun ping(): Reachability =
        try {
            server.reachability()
        } catch (e: DavTransportException) {
            Reachability.Unreachable(UnreachableReason.FAILED, e.message)
        } catch (e: DavProtocolException) {
            Reachability.Unreachable(UnreachableReason.FAILED, e.message)
        }

    private suspend fun <T> call(
        action: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: DavTransportException) {
            throw BackendUnavailableException(
                Reachability.Unreachable(UnreachableReason.FAILED, "Cannot $action: ${e.message}"),
                e,
            )
        } catch (e: DavProtocolException) {
            throw BlobStoreFailureException("Cannot $action: ${e.message}", e)
        } catch (e: XmlException) {
            throw BlobStoreFailureException(
                "Cannot $action: the server's answer is not valid: ${e.message}",
                e,
            )
        }

    /** Closes the HTTP client. The store cannot be used afterwards. */
    override fun close(): Unit = client.close()

    private companion object {
        const val HEX = 16
        val PROBE_CONTENT =
            "Jotter checks that this server keeps files. Safe to delete."
                .encodeToByteArray()
    }
}
