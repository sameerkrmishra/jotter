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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val HTTP_OK = 200
private const val HTTP_CREATED = 201

/**
 * A [BlobStore] on a hub: the `server` module, running on the user's PC and reached over Tailscale
 * (`docs/adr/0015-pc-hub-peer-protocol.md`). Through it, the hub's own store behaves as if it were
 * local: `writeNew` never replaces, `list` is complete or fails, and an unreachable hub, or one
 * that refuses the token, never looks empty.
 *
 * The connection is plain HTTP. Tailscale's WireGuard tunnel encrypts it and proves which machine
 * answers, so this must only be pointed at a Tailscale address or name, or at loopback.
 *
 * Nothing is retried here, and redirects are not followed, so the token only ever goes to
 * [address]. Close the store to free its connections.
 *
 * @property address Where the hub listens.
 */
public class PeerBlobStore internal constructor(
    public val address: PeerAddress,
    token: String,
    timeouts: PeerTimeouts,
    private val dispatcher: CoroutineDispatcher,
) : BlobStore,
    AutoCloseable {
    /**
     * A store on the hub at [address], which accepts [token], the secret that pairing on the hub
     * printed. The token is sent with every request, and appears in no message or log.
     *
     * @throws IllegalArgumentException if [token] has characters no token has.
     */
    public constructor(address: PeerAddress, token: String) :
        this(address, token, PeerTimeouts(), Dispatchers.Default)

    init {
        require(token.isNotEmpty() && token.all(::isTokenCharacter)) { "This is not a hub token" }
    }

    private val authorization = "Bearer $token"
    private val baseUrl = "http://${address.host}:${address.port}"
    private val answers = PeerAnswers(address)

    @Volatile
    private var closed = false

    private val client =
        HttpClient(CIO) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = timeouts.connect.inWholeMilliseconds
                socketTimeoutMillis = timeouts.socket.inWholeMilliseconds
                requestTimeoutMillis = timeouts.request.inWholeMilliseconds
            }
            engine {
                // A phone needs few connections, and the hub serves several devices.
                maxConnectionsCount = MAX_CONNECTIONS
                endpoint.maxConnectionsPerRoute = MAX_CONNECTIONS
                endpoint.connectAttempts = 1
            }
        }

    override suspend fun list(prefix: String): List<BlobInfo> {
        requireValidBlobPrefix(prefix)
        return call(PeerProtocol.LIST, { parameter(PeerProtocol.PREFIX_PARAMETER, prefix) }) {
            answers.listing(
                prefix,
                answers.body(this, HTTP_OK, prefix, PeerProtocol.MAX_LIST_BYTES),
            )
        }
    }

    override suspend fun read(path: String): ByteArray {
        requireValidBlobPath(path)
        return call(PeerProtocol.BLOB, { parameter(PeerProtocol.PATH_PARAMETER, path) }) {
            answers.body(this, HTTP_OK, path, PeerProtocol.MAX_FILE_BYTES)
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
        val content = ByteArrayContent(bytes.copyOf(), ContentType.Application.OctetStream)
        val request: HttpRequestBuilder.() -> Unit = {
            method = HttpMethod.Put
            parameter(PeerProtocol.PATH_PARAMETER, path)
            setBody(content)
        }
        call(PeerProtocol.BLOB, request) { answers.expect(this, HTTP_CREATED, path) }
    }

    override suspend fun ping(): Reachability =
        try {
            call(PeerProtocol.PING, {}) { answers.expect(this, HTTP_OK, null) }
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
    }

    /**
     * Sends one request to [endpoint], a `GET` unless [configure] says otherwise, and passes the
     * answer, if it is from a hub, to [handle]. Any failure to talk to the hub is
     * [BackendUnavailableException]: the hub is off, unreachable or went away during the call.
     */
    private suspend fun <T> call(
        endpoint: String,
        configure: HttpRequestBuilder.() -> Unit,
        handle: suspend io.ktor.client.statement.HttpResponse.() -> T,
    ): T =
        // Off the caller's dispatcher: the timeouts must run in real time, also under runTest.
        withContext(dispatcher) {
            try {
                check(!closed) { "This store was closed" }
                client
                    .prepareRequest("$baseUrl$endpoint") {
                        configure()
                        header(HttpHeaders.Authorization, authorization)
                    }.execute { response ->
                        answers.requireHub(response)
                        response.handle()
                    }
            } catch (e: BlobStoreException) {
                throw e
            } catch (e: CancellationException) {
                // The caller's cancellation, or a timeout of the client's own.
                currentCoroutineContext().ensureActive()
                throw answers.gone(e)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // IOException, timeouts and engine failures alike: the hub cannot be used now.
                throw answers.gone(e)
            }
        }

    private companion object {
        const val MAX_CONNECTIONS = 4

        // What a token from the hub is made of: base64url and the dot between its two parts.
        fun isTokenCharacter(char: Char): Boolean =
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_."
    }
}

/**
 * How long [PeerBlobStore] waits.
 *
 * @property connect For a connection to be made.
 * @property socket For the next bytes, in either direction, once connected. Ktor's CIO engine does
 *   not apply this on linuxX64, where only [request] bounds a hub that stops answering.
 * @property request For a whole request, answer included: as long as the hub's own deadline.
 */
internal data class PeerTimeouts(
    val connect: Duration = 10.seconds,
    val socket: Duration = 60.seconds,
    val request: Duration = 15.minutes,
)
