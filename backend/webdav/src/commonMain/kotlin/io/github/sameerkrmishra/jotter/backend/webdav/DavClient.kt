package io.github.sameerkrmishra.jotter.backend.webdav

import io.github.sameerkrmishra.jotter.core.logging.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val MAX_REDIRECTS = 5
private const val READ_CHUNK = 64 * 1024
private const val SCHEME_SEPARATOR = "://"

/** HTTP statuses this code acts on. */
internal object Status {
    const val OK = 200
    const val MULTI_STATUS = 207
    const val MOVED_PERMANENTLY = 301
    const val FOUND = 302
    const val TEMPORARY_REDIRECT = 307
    const val PERMANENT_REDIRECT = 308
    const val UNAUTHORIZED = 401
    const val NOT_FOUND = 404
    const val METHOD_NOT_ALLOWED = 405
    const val CONFLICT = 409
    const val GONE = 410
    const val PRECONDITION_FAILED = 412
    const val TOO_MANY_REQUESTS = 429
    const val BAD_GATEWAY = 502
    const val SERVICE_UNAVAILABLE = 503
    const val GATEWAY_TIMEOUT = 504
    const val INSUFFICIENT_STORAGE = 507

    val REDIRECTS = setOf(MOVED_PERMANENTLY, FOUND, TEMPORARY_REDIRECT, PERMANENT_REDIRECT)

    /** Answers that say "not now" rather than "no", so that a retry may get another one. */
    val TRANSIENT = setOf(TOO_MANY_REQUESTS, BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT)

    fun isSuccess(status: Int) = status / STATUS_CLASS == SUCCESS_CLASS

    /** Whether [status] says something about the server or account rather than about a path. */
    fun isFinal(status: Int) =
        status == UNAUTHORIZED || status in TRANSIENT || status == INSUFFICIENT_STORAGE

    private const val STATUS_CLASS = 100
    private const val SUCCESS_CLASS = 2
}

/**
 * A request's answer.
 *
 * @property status The HTTP status.
 * @property url The URL that answered, after any redirects.
 * @property body The body of a success if it was asked for, and empty otherwise.
 * @property retryAfter What the server said in `Retry-After`, in seconds, if anything.
 */
internal class DavResponse(
    val status: Int,
    val url: String,
    val body: ByteArray,
    val retryAfter: Duration?,
)

/** The server could not be reached, or the connection broke, every time the request was sent. */
internal class DavTransportException(
    override val message: String,
    cause: Throwable?,
) : Exception(message, cause)

/** The server asked for something this client refuses, such as a redirect to another host. */
internal class DavProtocolException(
    override val message: String,
) : Exception(message)

/**
 * Sends WebDAV requests with Basic authentication, and handles what any request may meet:
 * timeouts, retries of failures that may pass, redirects and limits on what is read.
 *
 * The password goes into an `Authorization` header and nowhere else: not into a message, not into
 * a log, and not to another origin, as only redirects within the store's origin are followed.
 */
internal class DavClient(
    private val engine: HttpClientEngine,
    private val origin: String,
    credentials: WebDavCredentials,
    private val settings: DavSettings,
) : AutoCloseable {
    private val authorization =
        "Basic " +
            Base64.encode("${credentials.username}:${credentials.password}".encodeToByteArray())

    private val client =
        HttpClient(engine) {
            // Redirects are followed below, where the origin is checked.
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = settings.connectTimeout.inWholeMilliseconds
                socketTimeoutMillis = settings.socketTimeout.inWholeMilliseconds
                requestTimeoutMillis = settings.requestTimeout.inWholeMilliseconds
            }
        }

    /**
     * Sends [method] to [url], with [body] if given. Reads the body of a success if [limit] is
     * given, and fails if it is longer. Retries a connection failure or a transient status, so a
     * request that already took effect can be sent again: callers send only requests for which
     * that is harmless or detectable, as it is for a `PUT` with `If-None-Match: *`.
     *
     * @throws DavTransportException if no attempt got an answer.
     * @throws DavProtocolException for a redirect it does not follow, or a body over [limit].
     */
    suspend fun send(
        method: HttpMethod,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArrayContent? = null,
        limit: Long? = null,
    ): DavResponse {
        val request = Request(method, headers, body, limit)
        var attempt = 1
        while (true) {
            val response =
                try {
                    followingRedirects(request, url)
                } catch (e: DavTransportException) {
                    if (attempt >= settings.attempts) throw e
                    null
                }
            if (response != null) {
                if (response.status !in Status.TRANSIENT || attempt >= settings.attempts) {
                    return response
                }
                val asked = response.retryAfter
                if (asked != null && asked > settings.maxRetryAfter) return response
            }
            val wait = response?.retryAfter ?: (settings.retryDelay * (1 shl (attempt - 1)))
            log.info {
                "${method.value} ${urlPath(url)}: attempt $attempt " +
                    "${response?.let { "answered ${it.status}" } ?: "failed"}, retrying in $wait"
            }
            delay(wait)
            attempt++
        }
    }

    private class Request(
        val method: HttpMethod,
        val headers: Map<String, String>,
        val body: ByteArrayContent?,
        val limit: Long?,
    )

    private suspend fun followingRedirects(
        request: Request,
        url: String,
    ): DavResponse {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val (response, location) = once(request, current)
            if (response.status !in Status.REDIRECTS || location == null) return response
            val target = resolve(current, location)
            if (!target.startsWith("$origin/")) {
                throw DavProtocolException(
                    "The server redirects $current to $target, outside $origin. " +
                        "Use the URL it redirects to, if that is the right server",
                )
            }
            current = target
        }
        throw DavProtocolException("More than $MAX_REDIRECTS redirects from $url")
    }

    private suspend fun once(
        request: Request,
        url: String,
    ): Pair<DavResponse, String?> {
        val watch = IdleWatch(settings.socketTimeout)
        val started = TimeSource.Monotonic.markNow()
        return try {
            watch.run { exchange(request, url, watch) }.also { (response, _) ->
                log.debug {
                    "${request.method.value} ${urlPath(url)} -> ${response.status} in " +
                        "${started.elapsedNow().inWholeMilliseconds} ms"
                }
            }
        } catch (e: DavProtocolException) {
            throw e
        } catch (
            // Engines throw platform types for a refused connection, a reset or a TLS error.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A timeout cancels the request; a cancelled caller must stay cancelled.
            currentCoroutineContext().ensureActive()
            log.debug(e) { "${request.method.value} ${urlPath(url)} failed" }
            throw DavTransportException(describe(request.method, url, e), e)
        }
    }

    private suspend fun exchange(
        request: Request,
        url: String,
        watch: IdleWatch,
    ): Pair<DavResponse, String?> =
        client
            .prepareRequest(url) {
                method = request.method
                header(HttpHeaders.Authorization, authorization)
                request.headers.forEach { (name, value) -> header(name, value) }
                request.body?.let { setBody(watch.body(it.bytes(), it.contentType)) }
            }.execute { response ->
                watch.touch()
                val status = response.status.value
                val body =
                    if (request.limit != null && Status.isSuccess(status)) {
                        readLimited(response, request.limit, url, watch)
                    } else {
                        ByteArray(0)
                    }
                val retryAfter =
                    response.headers[HttpHeaders.RetryAfter]
                        ?.trim()
                        ?.toLongOrNull()
                        ?.seconds
                DavResponse(status, url, body, retryAfter) to
                    response.headers[HttpHeaders.Location]
            }

    /** Reads the body into memory, never holding more than [limit] bytes and one chunk. */
    private suspend fun readLimited(
        response: HttpResponse,
        limit: Long,
        url: String,
        watch: IdleWatch,
    ): ByteArray {
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        requireWithin(declared ?: 0, limit, url)
        val channel = response.bodyAsChannel()
        val chunk = ByteArray(READ_CHUNK)
        var buffer = ByteArray(minOf(declared ?: READ_CHUNK.toLong(), limit).toInt())
        var size = 0
        while (true) {
            val read = channel.readAvailable(chunk)
            watch.touch()
            if (read < 0) break
            requireWithin(size.toLong() + read, limit, url)
            if (size + read > buffer.size) {
                buffer = buffer.copyOf(maxOf(size + read, minOf(buffer.size * 2L, limit).toInt()))
            }
            chunk.copyInto(buffer, size, 0, read)
            size += read
        }
        if (declared != null && size.toLong() != declared) {
            throw DavTransportException("$url: the body ended after $size of $declared bytes", null)
        }
        return if (size == buffer.size) buffer else buffer.copyOf(size)
    }

    private fun requireWithin(
        size: Long,
        limit: Long,
        url: String,
    ) {
        if (size > limit) {
            throw DavProtocolException("$url is larger than the limit of $limit bytes")
        }
    }

    /** Closes the client and its engine, which a client does not close when it was handed one. */
    override fun close() {
        client.close()
        engine.close()
    }

    private companion object {
        val log = Logger("webdav")

        fun describe(
            method: HttpMethod,
            url: String,
            cause: Throwable,
        ) = "${method.value} $url failed: ${cause.message ?: cause::class.simpleName}"

        val ABSOLUTE = Regex("""[A-Za-z][A-Za-z0-9+.-]*://.*""")

        /**
         * Resolves a `Location` header against [base], a URL this client sent, which always has
         * a path.
         */
        fun resolve(
            base: String,
            location: String,
        ): String {
            val scheme = base.substringBefore(SCHEME_SEPARATOR)
            val origin = base.substring(0, base.length - urlPath(base).length)
            return when {
                ABSOLUTE.matches(location) -> location
                location.startsWith("//") -> "$scheme:$location"
                location.startsWith("/") -> origin + location
                else -> base.substringBeforeLast('/') + "/" + location
            }
        }
    }
}
