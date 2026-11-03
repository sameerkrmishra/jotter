package io.github.sameerkrmishra.jotter.server

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How much of an unread body is read and dropped after an answer, so that the client sees it. */
private const val MAX_DRAIN_BYTES = 1024 * 1024
private val DRAIN_TIME = 1.seconds

/** How often the watch looks at every connection. */
private val WATCH_PERIOD = 50.milliseconds

/** How long [PeerServer.stop] waits for connection threads once their sockets are closed. */
private val STOP_TIME = 5.seconds
private const val BACKLOG = 50

/**
 * The hub's HTTP/1.1 server, on blocking JDK sockets with one thread per connection and one
 * request per connection (`docs/adr/0015-pc-hub-peer-protocol.md`).
 *
 * Everything here is bounded and provably released:
 * - At most [HubLimits.maxConnections] connections are open, each with one thread, and at most
 *   [HubLimits.maxConnectionsPerClient] from one address. A connection's slot is released only
 *   after its socket is closed, which frees its descriptor at once. The accept thread takes the
 *   next connection only when a slot is free; further clients wait in the system's queue.
 * - A watch thread closes any socket that is past its time: its head's, its request's, or the idle
 *   time since its last bytes moved, in either direction. Closing a blocking socket ends any
 *   read or write on it at once, so a client that never reads holds nothing beyond its deadline,
 *   and a blocked thread uses no CPU while it waits.
 * - Answers are written in parts, so the write blocks while the client does not read, and the
 *   answer's transfer stays held until the write ends.
 *
 * If the accept thread ends for any reason but [stop], [onFailure] is told why.
 */
internal class PeerServer(
    private val handler: PeerHandler,
    private val limits: HubLimits,
    private val onFailure: (reason: String) -> Unit,
) {
    private val slots = Semaphore(limits.maxConnections)
    private val open = ConcurrentHashMap.newKeySet<Connection>()
    private val perClient = HashMap<InetAddress, Int>()
    private val watch: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor {
            Thread(it, "jotter-hub-watch").apply {
                isDaemon =
                    true
            }
        }
    private var server: ServerSocket? = null
    private var acceptor: Thread? = null

    @Volatile
    private var stopping = false

    /** The connections open now. */
    val openConnections: Int get() = open.size

    /**
     * Listens on [host], an IP literal, and [port], and returns the port, which differs from
     * [port] if that is 0.
     *
     * @throws IOException if it cannot listen there, for example because the port is taken or
     *   [host] is not an address of this machine.
     */
    fun start(
        host: String,
        port: Int,
    ): Int {
        val socket = ServerSocket()
        try {
            socket.bind(InetSocketAddress(InetAddress.getByName(host), port), BACKLOG)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        server = socket
        watch.scheduleAtFixedRate(
            ::watchTick,
            0,
            WATCH_PERIOD.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
        acceptor =
            Thread({ acceptLoop(socket) }, "jotter-hub-accept").apply {
                isDaemon = true
                start()
            }
        return socket.localPort
    }

    /**
     * Stops listening, closes every connection's socket, and waits a few seconds at most for their
     * threads, which closing has woken.
     */
    fun stop() {
        stopping = true
        server?.close()
        val deadline = System.nanoTime() + STOP_TIME.inWholeNanoseconds

        fun remaining() = ((deadline - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(1)
        // The acceptor may be admitting a connection it has just accepted. Once it has ended, no
        // connection can be added, and admit() closes any that it adds after stopping was set.
        // It may wait for a free slot, which closing the listener does not end.
        acceptor?.interrupt()
        acceptor?.join(remaining())
        val connections = open.toList()
        connections.forEach { it.close() }
        connections.forEach { it.thread.join(remaining()) }
        watch.shutdownNow()
    }

    /** What the watch runs first on each look. Tests make it throw. */
    @Volatile
    internal var beforeWatch: () -> Unit = {}

    /** Address entries kept for the per-address cap. For tests. */
    internal val trackedAddresses: Int get() = synchronized(perClient) { perClient.size }

    /** Closes the listening socket without stopping, as a failing system would. For tests. */
    internal fun breakListener() {
        server?.close()
    }

    private fun acceptLoop(socket: ServerSocket) {
        try {
            while (true) {
                slots.acquire()
                val client =
                    try {
                        socket.accept()
                    } catch (e: IOException) {
                        slots.release()
                        throw e
                    }
                admit(client)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            if (!stopping) onFailure("the hub stopped accepting connections: $e")
        }
    }

    /** Serves [client] on a thread of its own, or closes it at once if its address has too many. */
    private fun admit(client: Socket) {
        val address = client.inetAddress
        val admitted =
            synchronized(perClient) {
                val count = perClient[address] ?: 0
                (count < limits.maxConnectionsPerClient).also {
                    if (it) {
                        perClient[address] =
                            count + 1
                    }
                }
            }
        if (!admitted) {
            // One address may not take every slot, whether or not it has a token.
            client.close()
            slots.release()
            return
        }
        val connection = Connection(client, limits)
        connection.thread =
            Thread({
                try {
                    serve(connection)
                } catch (
                    // Even an Error such as running out of memory ends this connection only.
                    @Suppress("TooGenericExceptionCaught") _: Throwable,
                ) {
                    // Nothing to answer: the connection closes below.
                } finally {
                    connection.close()
                    open -= connection
                    synchronized(perClient) {
                        val left = perClient.getValue(address) - 1
                        if (left == 0) perClient -= address else perClient[address] = left
                    }
                    slots.release()
                }
            }, "jotter-hub-connection").apply { isDaemon = true }
        // A second line of defence, should the watch ever stop: no read waits longer than this.
        client.soTimeout = limits.idleTimeout.inWholeMilliseconds.toInt()
        open += connection
        // stop() may have taken its list of connections before this one was added.
        if (stopping) connection.close()
        connection.thread.start()
    }

    private fun serve(connection: Connection) {
        val input = BufferedInputStream(connection.socket.getInputStream())
        val output = connection.socket.getOutputStream()
        val bytes = readRequestHead(input) ?: return
        connection.headDone()
        var bodyRead = false
        val reply =
            runBlocking {
                handler.handle(parseHead(bytes)) { length ->
                    bodyRead = true
                    readBody(input, length, connection::progress)
                }
            }
        try {
            writeReply(output, reply, connection::progress)
        } finally {
            reply.release()
        }
        connection.socket.shutdownOutput()
        if (!bodyRead) drain(connection, input)
    }

    /**
     * Reads what the client may still send of a body that was refused, for a moment, after the
     * answer. Closing with unread data would reset the connection, and the client could lose the
     * answer, such as "not authorised", before reading it.
     */
    private fun drain(
        connection: Connection,
        input: java.io.InputStream,
    ) {
        connection.socket.soTimeout = DRAIN_TIME.inWholeMilliseconds.toInt()
        connection.limitTo(DRAIN_TIME)
        val buffer = ByteArray(PART_BYTES)
        var drained = 0
        while (drained < MAX_DRAIN_BYTES) {
            val read = input.read(buffer)
            if (read < 0) return
            drained += read
        }
    }

    /**
     * One look of the watch. If it fails, a scheduled task would silently stop running, and no
     * time limit would hold any more: so the hub stops serving instead, closes every connection,
     * and reports the failure.
     */
    private fun watchTick() {
        try {
            beforeWatch()
            val now = System.nanoTime()
            open.forEach { if (it.expired(now)) it.close() }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            onFailure("the hub's watch over its connections failed: $e")
            stopping = true
            server?.close()
            open.forEach { it.close() }
            throw e
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * One client's connection and its times. The watch closes [socket] once [expired]: past the head's
 * time while the head arrives, past the idle time since the last bytes moved after that, and past
 * the request's deadline in any case.
 */
private class Connection(
    val socket: Socket,
    private val limits: HubLimits,
) {
    lateinit var thread: Thread
    private val started = System.nanoTime()
    private val hardDeadline = started + limits.requestDeadline.inWholeNanoseconds

    @Volatile
    private var phaseDeadline = started + limits.headerTimeout.inWholeNanoseconds

    /** The head has arrived: from now on, the idle time counts. */
    fun headDone() = progress()

    /** Bytes moved. */
    fun progress() {
        phaseDeadline = System.nanoTime() + limits.idleTimeout.inWholeNanoseconds
    }

    /** From now on, the connection may last [time] at most. */
    fun limitTo(time: Duration) {
        phaseDeadline = System.nanoTime() + time.inWholeNanoseconds
    }

    fun expired(now: Long): Boolean = now - hardDeadline > 0 || now - phaseDeadline > 0

    /** Closes the socket, which frees its descriptor and ends any read or write on it. */
    fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Closed regardless: the descriptor is released.
        }
    }
}
