package io.github.sameerkrmishra.jotter.backend.webdav

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

private const val WRITE_CHUNK = 64 * 1024

/**
 * Cancels a request that sends and receives nothing for [limit]. Ktor's socket timeout does this
 * on the JVM, but CIO ignores it on linuxX64, where a stalled server would hold a request until
 * the whole request timeout ran out. So the client says when bytes move ([touch]), and a watchdog
 * on real time, whatever the caller's dispatcher, ends the request once they stop.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class IdleWatch(
    private val limit: Duration,
) {
    private val start = TimeSource.Monotonic.markNow()
    private val lastActivity = AtomicLong(0)

    /** Records that bytes moved just now. */
    fun touch() {
        lastActivity.store(start.elapsedNow().inWholeNanoseconds)
    }

    /**
     * Runs [block], cancelling it with [IdleTimeoutException] once nothing moved for [limit].
     */
    @Suppress("InjectDispatcher") // Real time, also under a test's virtual clock.
    suspend fun <T> run(block: suspend () -> T): T =
        coroutineScope {
            touch()
            val work = async { block() }
            val watchdog =
                launch(Dispatchers.Default) {
                    var idle = start.elapsedNow() - lastActivity.load().nanoseconds
                    while (idle < limit) {
                        delay(limit - idle)
                        idle = start.elapsedNow() - lastActivity.load().nanoseconds
                    }
                    work.cancel(IdleTimeoutException(limit))
                }
            try {
                work.await()
            } finally {
                watchdog.cancel()
            }
        }

    /** [bytes] as a request body that [touch]es this watch for every chunk the server takes. */
    fun body(
        bytes: ByteArray,
        type: ContentType?,
    ): OutgoingContent =
        object : OutgoingContent.WriteChannelContent() {
            override val contentLength: Long = bytes.size.toLong()
            override val contentType: ContentType? = type

            override suspend fun writeTo(channel: ByteWriteChannel) {
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(offset + WRITE_CHUNK, bytes.size)
                    channel.writeFully(bytes, offset, end)
                    channel.flush()
                    touch()
                    offset = end
                }
            }
        }
}

/** Nothing moved on a connection for [limit]. */
internal class IdleTimeoutException(
    limit: Duration,
) : CancellationException("Nothing was sent or received for $limit")
