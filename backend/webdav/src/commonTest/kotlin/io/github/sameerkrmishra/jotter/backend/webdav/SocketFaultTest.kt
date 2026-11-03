package io.github.sameerkrmishra.jotter.backend.webdav

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The real CIO engine against a TCP server that misbehaves. */
class SocketFaultTest {
    private val settings =
        DavSettings(
            connectTimeout = 2.seconds,
            socketTimeout = 500.milliseconds,
            // Far above the socket timeout, so that only the socket timeout can end a stall.
            requestTimeout = 5.minutes,
            attempts = 2,
            retryDelay = 10.milliseconds,
        )

    private suspend fun withServer(
        plan: ConnectionPlan,
        test: suspend (WebDavBlobStore, FaultProxy) -> Unit,
    ) {
        FaultProxy.start(null) { plan }.use { server ->
            val store =
                WebDavBlobStore(
                    DavLocation.parse(server.url("/dav/")),
                    FAKE_CREDENTIALS,
                    CIO.create {
                        requestTimeout =
                            0
                    },
                    settings,
                )
            store.use { test(it, server) }
        }
    }

    @Test
    fun aRefusedConnectionIsUnreachable() =
        runTest {
            val port = FaultProxy.start(null) { ConnectionPlan.Forward }.use { it.port }
            WebDavBlobStore(
                DavLocation.parse("http://127.0.0.1:$port/"),
                FAKE_CREDENTIALS,
                CIO.create {
                    requestTimeout =
                        0
                },
                settings,
            ).use { store ->
                assertEquals(
                    UnreachableReason.FAILED,
                    assertIs<Reachability.Unreachable>(store.ping()).reason,
                )
                assertFailsWith<BackendUnavailableException> { store.list("") }
            }
        }

    @Test
    fun aServerThatHangsTimesOutAndIsRetriedOnce() =
        runTest(timeout = 1.minutes) {
            withServer(ConnectionPlan.Stall(1.minutes)) { store, server ->
                val start = TimeSource.Monotonic.markNow()
                assertFailsWith<BackendUnavailableException> { store.read("file") }
                assertTrue(start.elapsedNow() < 20.seconds, "took ${start.elapsedNow()}")
                assertEquals(2, server.connectionCount)
            }
        }

    @Test
    fun aServerThatStopsInTheMiddleOfAnAnswerTimesOut() =
        runTest(timeout = 1.minutes) {
            val head = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nstart"
            withServer(ConnectionPlan.Respond(head, hangAfter = 1.minutes)) { store, server ->
                val start = TimeSource.Monotonic.markNow()
                assertFailsWith<BackendUnavailableException> { store.read("file") }
                assertTrue(start.elapsedNow() < 20.seconds, "took ${start.elapsedNow()}")
                assertEquals(2, server.connectionCount)
            }
        }

    @Test
    fun aServerThatStopsTakingAnUploadTimesOut() =
        runTest(timeout = 1.minutes) {
            withServer(ConnectionPlan.Stall(1.minutes)) { store, _ ->
                val start = TimeSource.Monotonic.markNow()
                // Larger than the socket buffers, so that the upload itself stops.
                assertFailsWith<BackendUnavailableException> {
                    store.writeNew("big", ByteArray(UPLOAD))
                }
                assertTrue(start.elapsedNow() < 20.seconds, "took ${start.elapsedNow()}")
            }
        }

    @Test
    fun anAnswerCutShortIsAFailedConnection() =
        runTest {
            withServer(
                ConnectionPlan.Respond("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort"),
            ) { store, server ->
                assertFailsWith<BackendUnavailableException> { store.read("file") }
                assertEquals(2, server.connectionCount)
            }
        }

    @Test
    fun anAnswerThatIsNotHttpIsAFailedConnection() =
        runTest {
            withServer(ConnectionPlan.Respond("SSH-2.0-OpenSSH_9.9\r\n\r\n")) { store, _ ->
                assertEquals(
                    UnreachableReason.FAILED,
                    assertIs<Reachability.Unreachable>(store.ping()).reason,
                )
            }
        }

    @Test
    fun aRefusedPasswordIsTold() =
        runTest {
            val answer =
                "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"x\"\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
            withServer(ConnectionPlan.Respond(answer)) { store, _ ->
                assertEquals(
                    UnreachableReason.UNAUTHORIZED,
                    assertIs<Reachability.Unreachable>(store.ping()).reason,
                )
            }
        }

    private companion object {
        const val UPLOAD = 30 * 1024 * 1024
    }
}
