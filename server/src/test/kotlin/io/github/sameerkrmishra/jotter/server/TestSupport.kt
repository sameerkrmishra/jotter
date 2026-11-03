// Tests pick their threads: real time, for the timeouts of a real server.
@file:Suppress("InjectDispatcher")

package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.localfolder.LocalFolderBlobStore
import io.github.sameerkrmishra.jotter.backend.peer.PeerAddress
import io.github.sameerkrmishra.jotter.backend.peer.PeerBlobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import java.io.InputStream
import java.net.Socket
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.time.Duration.Companion.milliseconds

/** Temporary directories for one test class instance. [deleteAll] removes them. */
internal class TempFolders {
    private val created = mutableListOf<Path>()

    /** A path where nothing exists. */
    fun newPath(): Path {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-hub-${Random.nextULong()}"
        created += path
        return path
    }

    fun deleteAll() {
        created.forEach { FileSystem.SYSTEM.deleteRecursively(it) }
    }
}

/** Short limits, so that tests of timeouts take a moment. */
internal val quickLimits =
    HubLimits(
        headerTimeout = 500.milliseconds,
        idleTimeout = 500.milliseconds,
    )

/**
 * Hubs on loopback, each on a free port with a store folder of its own, and the clients made for
 * them. [close] stops and removes everything.
 */
internal class TestHubs {
    private val folders = TempFolders()
    private val hubs = mutableListOf<PeerHub>()
    private val clients = mutableListOf<PeerBlobStore>()

    /** A started hub, with its store set up unless [setUp] is false. */
    suspend fun start(
        limits: HubLimits = HubLimits(),
        setUp: Boolean = true,
    ): StartedHub {
        val root = folders.newPath()
        val hub =
            PeerHub(HubConfig(root / "store", root / "devices.json", port = 0, limits = limits))
        hubs += hub
        if (setUp) hub.setUpStore()
        val running = hub.start()
        return StartedHub(hub, root / "store", running.port, hub.pair("test device").token)
    }

    /** A client of [hub] with [token]. */
    fun client(
        hub: StartedHub,
        token: String = hub.token,
    ): PeerBlobStore = PeerBlobStore(PeerAddress(LOOPBACK, hub.port), token).also { clients += it }

    /** A path for a test's own files, removed by [close]. */
    fun newPath(): Path = folders.newPath()

    suspend fun close() {
        clients.forEach { it.close() }
        hubs.forEach { it.stop() }
        folders.deleteAll()
    }
}

/**
 * A running hub.
 *
 * @property hub The hub.
 * @property storeRoot Its store's folder.
 * @property port Its port on loopback.
 * @property token The token of a device paired with it.
 */
internal class StartedHub(
    val hub: PeerHub,
    val storeRoot: Path,
    val port: Int,
    val token: String,
) {
    /** The hub's store as the PC's own sync sees it. */
    val localStore: LocalFolderBlobStore get() = LocalFolderBlobStore(storeRoot)
}

internal const val LOOPBACK = "127.0.0.1"

/** A raw connection to [port], for requests the peer client would never send. */
internal fun rawConnection(port: Int): Socket = Socket(LOOPBACK, port)

/** The sending side of a raw connection. */
internal class RawOutput(
    private val socket: Socket,
) {
    fun writeFully(bytes: ByteArray) = socket.getOutputStream().write(bytes)

    fun flush() = socket.getOutputStream().flush()

    /** Sends the rest, then stops sending, as a client that breaks off. */
    fun flushAndClose() {
        flush()
        socket.shutdownOutput()
    }
}

internal fun Socket.openWriteChannel() = RawOutput(this)

internal fun Socket.openReadChannel(): InputStream = getInputStream()

/** Everything [this] holds until it ends. */
internal fun InputStream.readAll(): ByteArray = readBytes()

/**
 * Sends [request] as is over a new connection to [port], then returns everything the hub sends
 * back until it closes the connection. With [close], the client then stops sending, as one that
 * breaks off.
 */
internal suspend fun rawExchange(
    port: Int,
    request: ByteArray,
    close: Boolean = false,
): String =
    withContext(Dispatchers.IO) {
        rawConnection(port).use { socket ->
            val output = socket.openWriteChannel()
            output.writeFully(request)
            if (close) output.flushAndClose() else output.flush()
            socket.openReadChannel().readAll().decodeToString()
        }
    }

/** A request with the usual line endings, and with [token] in `Authorization` unless it is null. */
internal fun request(
    line: String,
    token: String?,
    vararg headers: String,
    body: ByteArray = ByteArray(0),
): ByteArray {
    val head =
        buildString {
            append("$line\r\nHost: $LOOPBACK\r\n")
            if (token != null) append("Authorization: Bearer $token\r\n")
            headers.forEach { append("$it\r\n") }
            append("\r\n")
        }
    return head.encodeToByteArray() + body
}

/** The status code of a raw answer. */
internal fun statusOf(answer: String): Int = answer.substringAfter(' ').substringBefore(' ').toInt()
