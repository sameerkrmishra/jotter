package io.github.sameerkrmishra.jotter.server

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.time.Duration.Companion.seconds

/** How much of an unread body is read and dropped after an answer, so that the client sees it. */
private const val MAX_DRAIN_BYTES = 1024L * 1024
private val DRAIN_TIME = 1.seconds

/**
 * The hub's HTTP/1.1 server: an accept loop over Ktor's sockets, with one request per connection.
 * It is written out instead of using Ktor's server engine because the engine has no limit on
 * connections or on the time to send headers (`docs/adr/0015-pc-hub-peer-protocol.md`).
 *
 * At most [HubLimits.maxConnections] connections are served at once; the loop accepts the next only
 * when one ends, so further clients wait in the system's queue instead of costing memory here.
 */
internal class PeerServer(
    private val handler: PeerHandler,
    private val limits: HubLimits,
    dispatcher: CoroutineDispatcher,
) {
    // An exception that escapes a coroutine would end the process on Native, the desktop app's
    // too. serve() catches everything, so this only guards the accept loop.
    private val scope =
        CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> })
    private val selector = SelectorManager(dispatcher)
    private val connections = Semaphore(limits.maxConnections)
    private var socket: ServerSocket? = null

    /**
     * Listens on [host] and [port] and returns the port, which differs from [port] if that is 0.
     *
     * @throws IOException if it cannot listen there, for example because the port is taken or
     *   [host] is not an address of this machine.
     */
    suspend fun start(
        host: String,
        port: Int,
    ): Int {
        val server = aSocket(selector).tcp().bind(host, port)
        socket = server
        scope.launch { acceptLoop(server) }
        return (server.localAddress as InetSocketAddress).port
    }

    /** Stops listening and drops every connection. */
    suspend fun stop() {
        socket?.close()
        scope.coroutineContext.job.cancelAndJoin()
        selector.close()
    }

    private suspend fun CoroutineScope.acceptLoop(server: ServerSocket) {
        while (true) {
            connections.acquire()
            val client =
                try {
                    server.accept()
                } catch (_: IOException) {
                    connections.release()
                    if (server.isClosed) return
                    continue
                }
            launch {
                try {
                    serve(client)
                } finally {
                    client.close()
                    connections.release()
                }
            }
        }
    }

    private suspend fun serve(client: Socket) {
        val input = client.openReadChannel()
        val output = client.openWriteChannel()
        try {
            withTimeout(limits.requestDeadline) {
                val head =
                    withTimeout(limits.headerTimeout) { readRequestHead(input) }
                        ?: return@withTimeout
                var bodyRead = false
                val reply =
                    handler.handle(head) { length ->
                        bodyRead = true
                        readBody(input, length, limits.idleTimeout)
                    }
                writeReply(output, reply)
                if (!bodyRead) drain(input)
            }
        } catch (_: TimeoutCancellationException) {
            // Too slow: dropped.
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            // A head that does not parse, a body that broke off, a client that went away, or a
            // bug: the connection closes without an answer, and the hub serves on. On Native an
            // exception that escaped a coroutine would end the process, the desktop app's too.
        }
    }

    /**
     * Reads what the client may still send of a body that was refused, for a moment, after the
     * answer. Closing with unread data would reset the connection, and the client could lose the
     * answer, such as "not authorised", before reading it.
     */
    private suspend fun drain(input: ByteReadChannel) {
        withTimeoutOrNull(DRAIN_TIME) { input.discard(MAX_DRAIN_BYTES) }
    }
}
