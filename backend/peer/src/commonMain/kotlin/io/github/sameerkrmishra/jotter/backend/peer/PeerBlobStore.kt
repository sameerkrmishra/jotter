package io.github.sameerkrmishra.jotter.backend.peer

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.api.requireValidBlobPath
import io.github.sameerkrmishra.jotter.backend.api.requireValidBlobPrefix
import io.github.sameerkrmishra.jotter.core.logging.Logger
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val HTTP_OK = 200
private const val HTTP_CREATED = 201

/**
 * A [BlobStore] on a hub: the `server` module, running on the user's PC and reached over Tailscale
 * (`docs/adr/0015-pc-hub-peer-protocol.md`). Through it, the hub's own store behaves as if it were
 * local: `writeNew` never replaces, `list` is complete or fails, and an unreachable hub never
 * looks empty. A hub that refuses the token is unreachable with
 * [UnreachableReason.UNAUTHORIZED]: the device must be paired again.
 *
 * The connection is plain HTTP, which Tailscale's WireGuard tunnel encrypts. So the token only
 * goes to an address on the tailnet or on this machine: before each request the hub's name is
 * resolved, the address is checked, and the request goes to that address. Nothing is retried, and
 * redirects are not followed. Close the store to free its connections.
 *
 * @property address Where the hub listens.
 */
public class PeerBlobStore internal constructor(
    public val address: PeerAddress,
    token: String,
    private val timeouts: PeerTimeouts,
    private val dispatcher: CoroutineDispatcher,
    private val transport: PeerTransport?,
) : BlobStore,
    AutoCloseable {
    /**
     * A store on the hub at [address], which accepts [token], the secret that pairing on the hub
     * printed. The token is sent with every request, and appears in no message or log.
     *
     * @throws IllegalArgumentException if [token] has characters no token has.
     */
    public constructor(address: PeerAddress, token: String) :
        this(address, token, PeerTimeouts(), Dispatchers.Default, null)

    /**
     * The same, with its hub reached through [transport] instead of Ktor's CIO engine and the
     * system's name lookup, such as one that an Android app keeps to Tailscale's network
     * (ADR-0018). The store owns the transport's engine and closes it with itself.
     *
     * @throws IllegalArgumentException if [token] has characters no token has.
     */
    public constructor(address: PeerAddress, token: String, transport: PeerTransport) :
        this(address, token, PeerTimeouts(), Dispatchers.Default, transport)

    init {
        require(token.isNotEmpty() && token.all(::isTokenCharacter)) { "This is not a hub token" }
    }

    private val lookup: (String) -> ByteArray? =
        transport?.lookup ?: { InetSocketAddress(it, address.port).resolveAddress() }

    private val authorization = "Bearer $token"
    private val answers = PeerAnswers(address)

    @Volatile
    private var closed = false

    private val client =
        transport?.let { HttpClient(it.engine) { configure() } } ?: HttpClient(CIO) {
            configure()
            engine {
                // A phone needs few connections, and the hub serves several devices.
                maxConnectionsCount = MAX_CONNECTIONS
                endpoint.maxConnectionsPerRoute = MAX_CONNECTIONS
                endpoint.connectAttempts = 1
            }
        }

    private fun HttpClientConfig<*>.configure() {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = timeouts.connect.inWholeMilliseconds
            socketTimeoutMillis = timeouts.idle.inWholeMilliseconds
        }
    }

    override suspend fun list(prefix: String): List<BlobInfo> {
        requireValidBlobPrefix(prefix)
        val query: HttpRequestBuilder.() -> Unit = {
            parameter(
                PeerProtocol.PREFIX_PARAMETER,
                prefix,
            )
        }
        return call(PeerProtocol.LIST, timeouts.small, query) {
            val body = answers.body(response, HTTP_OK, prefix, PeerProtocol.MAX_LIST_BYTES, watch)
            answers.listing(prefix, body)
        }
    }

    override suspend fun read(path: String): ByteArray {
        requireValidBlobPath(path)
        val query: HttpRequestBuilder.() -> Unit = { parameter(PeerProtocol.PATH_PARAMETER, path) }
        return call(PeerProtocol.BLOB, timeouts.transfer, query) {
            answers.body(response, HTTP_OK, path, PeerProtocol.MAX_FILE_BYTES, watch)
        }
    }

    override suspend fun writeNew(
        path: String,
        bytes: ByteArray,
    ) {
        requireValidBlobPath(path)
        if (bytes.size > PeerProtocol.MAX_FILE_BYTES) {
            throw BlobStoreFailureException(
                "Cannot write \"$path\" to hub $address: ${bytes.size} bytes is more than the " +
                    "${PeerProtocol.MAX_FILE_BYTES} a file may have",
            )
        }
        // A copy, so that the request sends what the caller passed even if it changes the array.
        val copy = bytes.copyOf()
        val request: HttpRequestBuilder.() -> Unit = {
            method = HttpMethod.Put
            parameter(PeerProtocol.PATH_PARAMETER, path)
        }
        call(PeerProtocol.BLOB, timeouts.transfer, request, copy) {
            answers.expect(response, HTTP_CREATED, path, watch)
        }
    }

    override suspend fun ping(): Reachability =
        try {
            call(PeerProtocol.PING, timeouts.small, {}) {
                answers.expect(response, HTTP_OK, null, watch)
            }
            Reachability.Reachable
        } catch (e: BackendUnavailableException) {
            e.reachability
        } catch (e: BlobStoreException) {
            Reachability.Unreachable(UnreachableReason.FAILED, e.message.orEmpty())
        }

    /**
     * Closes the connections to the hub. Afterwards every call fails as unavailable, which Ktor's
     * client does not do by itself on every target.
     */
    override fun close() {
        closed = true
        client.close()
        // A client made on a given engine leaves it open.
        transport?.engine?.close()
    }

    /**
     * Sends one request to [endpoint], a `GET` unless [configure] says otherwise, with [upload] as
     * its body if given, and passes the answer, if it is from a hub, to [handle]. The request may
     * take [deadline] in all, and stall for [PeerTimeouts.idle] at most. Any failure to talk to the
     * hub is [BackendUnavailableException]: the hub is off, unreachable, outside the tailnet, or
     * went away during the call.
     */
    private suspend fun <T> call(
        endpoint: String,
        deadline: Duration,
        configure: HttpRequestBuilder.() -> Unit,
        upload: ByteArray? = null,
        handle: suspend Exchange.() -> T,
    ): T =
        // Off the caller's dispatcher: the timeouts must run in real time, also under runTest.
        withContext(dispatcher) {
            val started = TimeSource.Monotonic.markNow()
            try {
                check(!closed) { "This store was closed" }
                val host = urlHostOf(resolvedAddress())
                withTimeout(deadline) {
                    IdleWatch.watching(timeouts.idle) { watch ->
                        client
                            .prepareRequest("http://$host:${address.port}$endpoint") {
                                configure()
                                header(HttpHeaders.Authorization, authorization)
                                if (upload != null) setBody(UploadContent(upload, watch))
                            }.execute { response ->
                                watch.progress()
                                log.debug {
                                    "${response.call.request.method.value} ${callName(endpoint)} " +
                                        "at hub $address -> ${response.status.value} in " +
                                        "${started.elapsedNow().inWholeMilliseconds} ms"
                                }
                                answers.requireHub(response)
                                Exchange(response, watch).handle()
                            }
                    }
                }
            } catch (e: BlobStoreException) {
                throw e
            } catch (e: CancellationException) {
                // The caller's cancellation, or a deadline or timeout of the client's own.
                currentCoroutineContext().ensureActive()
                throw answers.gone(e)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // IOException, timeouts and engine failures alike: the hub cannot be used now.
                log.debug(e) { "${callName(endpoint)} at hub $address failed" }
                throw answers.gone(e)
            }
        }

    /**
     * The address that the hub's name resolves to now, by the transport's lookup if there is one,
     * which the request then goes to, so that the address checked is the address connected to.
     *
     * @throws BackendUnavailableException if it does not resolve, or resolves outside the tailnet.
     */
    private fun resolvedAddress(): ByteArray {
        val resolved = lookedUp() ?: throw unreachable("Cannot find hub $address")
        if (!isTailnetOrLoopback(resolved)) {
            throw unreachable(
                "Hub $address is at ${urlHostOf(resolved)}, which is neither on the tailnet nor " +
                    "this machine, so Jotter does not send it the device's token",
            )
        }
        return resolved
    }

    /**
     * The hub's address by [lookup], or null if it has none: with a transport, by its lookup only,
     * never the system's as well. A [BackendUnavailableException] of the lookup's is the request's
     * failure.
     */
    private fun lookedUp(): ByteArray? =
        try {
            lookup(address.host)
        } catch (e: BackendUnavailableException) {
            throw e
        } catch (
            // Null on the JVM, a PosixException on linuxX64: either way, no address.
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }

    private fun unreachable(detail: String) =
        BackendUnavailableException(Reachability.Unreachable(UnreachableReason.FAILED, detail))

    /** An answer, and the watch that reading its body reports to. */
    private class Exchange(
        val response: HttpResponse,
        val watch: IdleWatch,
    )

    /** A body sent in parts, each reported to [watch]: a slow upload is not a stall. */
    private class UploadContent(
        private val bytes: ByteArray,
        private val watch: IdleWatch,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long get() = bytes.size.toLong()
        override val contentType: ContentType get() = ContentType.Application.OctetStream

        override suspend fun writeTo(channel: ByteWriteChannel) {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + UPLOAD_PART, bytes.size)
                channel.writeFully(bytes, offset, end)
                channel.flush()
                offset = end
                watch.progress()
            }
        }
    }

    private companion object {
        val log = Logger("peer")

        // The last part of an endpoint, such as `ping`: the log never holds a URL's path (ADR-0021).
        fun callName(endpoint: String) = endpoint.substringAfterLast('/')

        const val MAX_CONNECTIONS = 4
        const val UPLOAD_PART = 64 * 1024

        // What a token from the hub is made of: base64url and the dot between its two parts.
        fun isTokenCharacter(char: Char): Boolean =
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_."
    }
}

/**
 * How long [PeerBlobStore] waits.
 *
 * @property connect For a connection to be made.
 * @property idle For the next bytes, in either direction, once a request is sent. The client
 *   enforces this itself, as Ktor's CIO engine does not on linuxX64.
 * @property small For a whole `ping` or `list`.
 * @property transfer For a whole `read` or `writeNew`: as long as the hub's own deadline.
 */
internal data class PeerTimeouts(
    val connect: Duration = 10.seconds,
    val idle: Duration = 60.seconds,
    val small: Duration = 60.seconds,
    val transfer: Duration = 15.minutes,
)
