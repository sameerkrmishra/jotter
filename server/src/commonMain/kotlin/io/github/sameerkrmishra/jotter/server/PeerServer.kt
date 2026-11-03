package io.github.sameerkrmishra.jotter.server

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.awaitClosed
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/** How much of an unread body is read and dropped after an answer, so that the client sees it. */
private const val MAX_DRAIN_BYTES = 1024L * 1024
private val DRAIN_TIME = 1.seconds

/** How long a connection may take to close once the hub is done with it, before it is cut. */
private val CLOSE_TIME = 1.seconds

/** How long [PeerServer.stop] waits for connections to end before it cuts them. */
private val STOP_TIME = 5.seconds

/**
 * The hub's HTTP/1.1 server: an accept loop over Ktor's sockets, with one request per connection.
 * It is written out instead of using Ktor's server engine because the engine has no limit on
 * connections or on the time to send headers (`docs/adr/0015-pc-hub-peer-protocol.md`).
 *
 * At most [HubLimits.maxConnections] connections are open at once, and at most
 * [HubLimits.maxConnectionsPerClient] from one address. A connection counts until its socket has
 * closed, not only until its answer was handed to Ktor, which buffers it: a client that never
 * reads holds its slot, and its transfer, until the request deadline cuts it. The loop accepts the
 * next connection only when a slot is free, so further clients wait in the system's queue.
 *
 * If the accept loop ends for any reason but [stop], [onFailure] is told why.
 */
internal class PeerServer(
    private val handler: PeerHandler,
    private val limits: HubLimits,
    dispatcher: CoroutineDispatcher,
    private val onFailure: (reason: String) -> Unit,
) {
    // An exception that escapes a coroutine would end the process on Native, the desktop app's
    // too. Each connection is a child of this supervisor, so an Error in one, such as running out
    // of memory, ends that connection and nothing else.
    private val scope =
        CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> })
    private val selector = SelectorManager(dispatcher)
    private val connections = Semaphore(limits.maxConnections)
    private val lock = reentrantLock()
    private val perClient = HashMap<String, Int>()
    private val open = HashSet<Socket>()
    private var socket: ServerSocket? = null

    @Volatile
    private var stopping = false

    /** The connections open now. */
    val openConnections: Int get() = lock.withLock { open.size }

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
        scope.launch {
            val reason =
                try {
                    acceptLoop(server)
                    "the hub stopped accepting connections"
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable,
                ) {
                    "the hub stopped accepting connections: $e"
                }
            if (!stopping) onFailure(reason)
        }
        return (server.localAddress as InetSocketAddress).port
    }

    /**
     * Stops listening and ends every connection, within a few seconds even if a connection does
     * not end by itself.
     */
    suspend fun stop() {
        stopping = true
        socket?.close()
        val job = scope.coroutineContext.job
        job.cancel()
        if (withTimeoutOrNull(STOP_TIME) { job.join() } == null) {
            lock.withLock { open.toList() }.forEach { it.dispose() }
        }
        selector.close()
    }

    /** Closes the listening socket without stopping, as a failing system would. For tests. */
    internal fun breakListener() {
        socket?.close()
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (true) {
            connections.acquire()
            val client =
                try {
                    server.accept()
                } catch (e: IOException) {
                    connections.release()
                    if (server.isClosed) return
                    throw e
                }
            // A TCP socket's peer is always an internet address.
            val address = (client.remoteAddress as InetSocketAddress).hostname
            if (admit(client, address)) {
                scope.launch {
                    try {
                        serve(client)
                    } finally {
                        withContext(NonCancellable) { close(client) }
                        lock.withLock {
                            open -= client
                            perClient[address] = perClient.getValue(address) - 1
                        }
                        connections.release()
                    }
                }
            } else {
                // One address may not take every slot, whether or not it has a token.
                client.dispose()
                connections.release()
            }
        }
    }

    private fun admit(
        client: Socket,
        address: String,
    ): Boolean =
        lock.withLock {
            val count = perClient[address] ?: 0
            if (count >= limits.maxConnectionsPerClient) return@withLock false
            perClient[address] = count + 1
            open += client
            true
        }

    private suspend fun serve(client: Socket) {
        val input = client.openReadChannel()
        val output = client.openWriteChannel()
        var reply: Reply? = null
        try {
            withTimeout(limits.requestDeadline) {
                val head =
                    withTimeout(limits.headerTimeout) { readRequestHead(input) }
                        ?: return@withTimeout
                var bodyRead = false
                reply =
                    handler.handle(head) { length ->
                        bodyRead = true
                        readBody(input, length, limits.idleTimeout)
                    }
                writeReply(output, reply)
                // Ktor buffers what is written; this waits until the client has taken it.
                output.flushAndClose()
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
            // bug: the connection closes without an answer, and the hub serves on.
        } finally {
            // Only now is the answer's memory no longer needed.
            reply?.release()
        }
    }

    /** Closes [client], and cuts it if it does not close in time, as when it reads nothing. */
    private suspend fun close(client: Socket) {
        client.close()
        if (withTimeoutOrNull(CLOSE_TIME) { client.awaitClosed() } == null) client.dispose()
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
