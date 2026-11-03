// A test server: it picks its own threads.
@file:Suppress("InjectDispatcher")

package io.github.sameerkrmishra.jotter.backend.peer

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A server on loopback that answers like a hub that is broken in some way: [answer] gets each
 * request's line and headers and writes whatever it likes, and the connection then closes.
 * [requests] records what arrived.
 */
internal class FakeHub private constructor(
    private val server: ServerSocket,
    private val scope: CoroutineScope,
) {
    val requests = mutableListOf<String>()
    private val lock = Mutex()

    /** Where it listens. */
    val address: PeerAddress get() =
        PeerAddress(
            "127.0.0.1",
            (server.localAddress as InetSocketAddress).port,
        )

    fun close() {
        server.close()
        scope.cancel()
    }

    companion object {
        private val selector = SelectorManager(Dispatchers.Default)

        suspend fun start(answer: suspend ByteWriteChannel.(head: String) -> Unit): FakeHub {
            val server = aSocket(selector).tcp().bind("127.0.0.1", 0)
            // A client that hangs up early is no failure of a test; runTest would report it as one.
            val scope =
                CoroutineScope(
                    SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> },
                )
            val hub = FakeHub(server, scope)
            scope.launch {
                while (true) {
                    val socket = server.accept()
                    launch {
                        socket.use {
                            val input = socket.openReadChannel()
                            val head = readHead(input)
                            val length =
                                Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                                    .find(head)
                                    ?.groupValues
                                    ?.get(1)
                                    ?.toInt() ?: 0
                            input.readByteArray(length)
                            hub.lock.withLock { hub.requests += head }
                            val output = socket.openWriteChannel()
                            output.answer(head)
                            output.flush()
                        }
                    }
                }
            }
            return hub
        }

        /** An answer with [status], the protocol header unless [asHub] is false, and [body]. */
        fun answer(
            status: Int,
            body: String,
            asHub: Boolean = true,
            length: Int? = body.encodeToByteArray().size,
            extra: String = "",
        ): String =
            buildString {
                append("HTTP/1.1 $status X\r\n")
                if (asHub) append("${PeerProtocol.VERSION_HEADER}: ${PeerProtocol.VERSION}\r\n")
                if (length != null) append("Content-Length: $length\r\n")
                append(extra)
                append("Connection: close\r\n\r\n")
                append(body)
            }

        private suspend fun readHead(input: io.ktor.utils.io.ByteReadChannel): String {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.readByte().toInt().toChar())
            return head.toString()
        }

        /** Writes [text]. */
        suspend fun ByteWriteChannel.send(text: String) = writeFully(text.encodeToByteArray())
    }
}
