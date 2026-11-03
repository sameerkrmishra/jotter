package io.github.sameerkrmishra.jotter.backend.webdav

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Duration

/**
 * What a test server does with one connection, numbered from 0 in the order they arrive.
 */
internal sealed interface ConnectionPlan {
    /** Passes the connection on to the real server, if there is one. */
    data object Forward : ConnectionPlan

    /** Closes the connection after reading [bytes] bytes of the request. */
    data class DropRequestAfter(
        val bytes: Long,
    ) : ConnectionPlan

    /** Forwards the request, then closes after passing on [bytes] bytes of the answer. */
    data class DropResponseAfter(
        val bytes: Long,
    ) : ConnectionPlan

    /** Reads the request head, then sends [raw] and closes, after waiting [hangAfter]. */
    data class Respond(
        val raw: String,
        val hangAfter: Duration = Duration.ZERO,
    ) : ConnectionPlan

    /** Waits [delay] before doing [then]. */
    data class Stall(
        val delay: Duration,
        val then: ConnectionPlan = Forward,
    ) : ConnectionPlan
}

/**
 * A TCP server on 127.0.0.1 that treats each connection as [plan] says, forwarding to the real
 * server at [target] (`host` to `port`) where the plan says so. It stands between the store and a
 * server to break connections, answer with errors or hang, at the byte level, so that the real
 * HTTP engine meets these faults.
 */
@OptIn(ExperimentalAtomicApi::class)
@Suppress("InjectDispatcher") // Tests choose their threads directly.
internal class FaultProxy private constructor(
    private val target: Pair<String, Int>?,
    private val plan: (Int) -> ConnectionPlan,
    private val selector: SelectorManager,
    private val server: ServerSocket,
) : AutoCloseable {
    // A connection that breaks is what this proxy is for, not a failure of the test.
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    private val connections = AtomicInt(0)

    /** The port to connect to. */
    val port: Int = (server.localAddress as InetSocketAddress).port

    /** The URL of [path] through this proxy. */
    fun url(path: String = "/"): String = "http://127.0.0.1:$port$path"

    /** How many connections arrived. */
    val connectionCount: Int get() = connections.load()

    init {
        scope.launch {
            while (true) {
                val client = server.accept()
                val number = connections.fetchAndIncrement()
                launch { serve(client, plan(number)) }
            }
        }
    }

    private suspend fun serve(
        client: Socket,
        plan: ConnectionPlan,
    ) {
        val input = client.openReadChannel()
        val output = client.openWriteChannel(autoFlush = true)
        try {
            serveWith(input, output, plan)
        } finally {
            client.close()
        }
    }

    private suspend fun serveWith(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        plan: ConnectionPlan,
    ) {
        when (plan) {
            is ConnectionPlan.Respond -> {
                do {
                    val line = input.readLine()
                } while (!line.isNullOrEmpty())
                output.writeFully(plan.raw.encodeToByteArray())
                output.flush()
                delay(plan.hangAfter)
                output.flushAndClose()
            }
            is ConnectionPlan.DropRequestAfter -> {
                val upstream = connectUpstream()
                upstream.use { pump(input, it.openWriteChannel(autoFlush = true), plan.bytes) }
            }
            is ConnectionPlan.DropResponseAfter -> relay(input, output, plan.bytes)
            ConnectionPlan.Forward -> relay(input, output, Long.MAX_VALUE)
            is ConnectionPlan.Stall -> {
                delay(plan.delay)
                serveWith(input, output, plan.then)
            }
        }
    }

    private suspend fun relay(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        responseBytes: Long,
    ) {
        connectUpstream().use { upstream ->
            val upstreamOutput = upstream.openWriteChannel(autoFlush = true)
            val requests = scope.launch { pump(input, upstreamOutput, Long.MAX_VALUE) }
            pump(upstream.openReadChannel(), output, responseBytes)
            requests.cancel()
        }
    }

    private suspend fun connectUpstream(): Socket {
        val (host, port) = checkNotNull(target) { "this proxy has no server to forward to" }
        return aSocket(selector).tcp().connect(host, port)
    }

    /**
     * Copies at most [limit] bytes, then stops, which closes the connection. It waits for the
     * first bytes even with a [limit] of 0, so that the other side has sent something by then: a
     * server has handled the request when its answer starts.
     */
    private suspend fun pump(
        from: ByteReadChannel,
        to: ByteWriteChannel,
        limit: Long,
    ) {
        val buffer = ByteArray(BUFFER)
        var left = limit
        do {
            val read = from.readAvailable(buffer)
            val passed = minOf(read.coerceAtLeast(0).toLong(), left).toInt()
            to.writeFully(buffer, 0, passed)
            left -= passed
        } while (read >= 0 && left > 0)
    }

    override fun close() {
        scope.cancel()
        server.close()
        selector.close()
    }

    companion object {
        private const val BUFFER = 8192

        /** Starts a proxy to [target], or a server of [ConnectionPlan.Respond] only if null. */
        suspend fun start(
            target: Pair<String, Int>?,
            plan: (Int) -> ConnectionPlan,
        ): FaultProxy {
            val selector = SelectorManager(Dispatchers.Default)
            return FaultProxy(target, plan, selector, aSocket(selector).tcp().bind("127.0.0.1", 0))
        }
    }
}
