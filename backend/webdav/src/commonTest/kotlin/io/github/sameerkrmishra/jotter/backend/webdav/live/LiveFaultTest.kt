package io.github.sameerkrmishra.jotter.backend.webdav.live

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.webdav.ConnectionPlan
import io.github.sameerkrmishra.jotter.backend.webdav.DavLocation
import io.github.sameerkrmishra.jotter.backend.webdav.DavSettings
import io.github.sameerkrmishra.jotter.backend.webdav.FaultProxy
import io.github.sameerkrmishra.jotter.backend.webdav.WebDavBlobStore
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Faults between the store and a real server (lighttpd), caused by a proxy at the TCP level, so
 * that the real HTTP engine meets them: dropped uploads and downloads, a lost answer, errors and a
 * server that hangs.
 */
class LiveFaultTest {
    private val big = ByteArray(BIG) { (it * PATTERN).toByte() }

    private fun faultTest(body: suspend (LiveServer) -> Unit) =
        runTest(timeout = 2.minutes) { LiveServer("JOTTER_WEBDAV_LIGHTTPD_URL").use { body(it) } }

    /** A store in a new collection on [server], reached through a proxy with [plan]. */
    private suspend fun withProxy(
        server: LiveServer,
        plan: (Int) -> ConnectionPlan,
        settings: DavSettings = DavSettings(retryDelay = 10.milliseconds),
        test: suspend (proxied: WebDavBlobStore, direct: WebDavBlobStore) -> Unit,
    ) {
        val collectionUrl = server.newCollection()
        val direct = WebDavBlobStore(collectionUrl, LIVE_CREDENTIALS).also { it.initialize() }
        val origin = DavLocation.parse(server.url).origin
        val (host, port) = origin.removePrefix("http://").split(':')
        val collection = collectionUrl.removePrefix(origin)
        FaultProxy.start(host to port.toInt(), plan).use { proxy ->
            val proxied =
                WebDavBlobStore(
                    DavLocation.parse(proxy.url(collection)),
                    LIVE_CREDENTIALS,
                    CIO.create { requestTimeout = 0 },
                    settings,
                )
            proxied.use { direct.use { test(proxied, direct) } }
        }
    }

    @Test
    fun anUploadDroppedHalfwayIsRetried() =
        faultTest { server ->
            val plan = { n: Int ->
                if (n ==
                    0
                ) {
                    ConnectionPlan.DropRequestAfter(BIG / 2L)
                } else {
                    ConnectionPlan.Forward
                }
            }
            withProxy(server, plan) { proxied, direct ->
                proxied.writeNew("dir/big", big)
                assertContentEquals(big, direct.read("dir/big"))
            }
        }

    @Test
    fun anUploadThatAlwaysDropsLeavesNoPartOfTheFile() =
        faultTest { server ->
            withProxy(server, { ConnectionPlan.DropRequestAfter(BIG / 2L) }) { proxied, direct ->
                direct.writeNew("dir/other", byteArrayOf(1))
                assertFailsWith<BackendUnavailableException> { proxied.writeNew("dir/big", big) }
                assertEquals(listOf(BlobInfo("dir/other", 1)), direct.list(""))
                direct.writeNew("dir/big", big)
                assertContentEquals(big, direct.read("dir/big"))
            }
        }

    @Test
    fun aLostAnswerToAnUploadMakesTheRetryReportTheFileAsThere() =
        faultTest { server ->
            // The first PUT reaches the server in full, and its answer is dropped. The retry finds
            // the file, and cannot know that it is its own: BlobStore.writeNew's KDoc.
            val plan = { n: Int ->
                if (n ==
                    0
                ) {
                    ConnectionPlan.DropResponseAfter(0)
                } else {
                    ConnectionPlan.Forward
                }
            }
            withProxy(server, plan) { proxied, direct ->
                assertFailsWith<BlobAlreadyExistsException> { proxied.writeNew("file", big) }
                assertContentEquals(big, direct.read("file"))
            }
        }

    @Test
    fun aDownloadDroppedHalfwayIsRetried() =
        faultTest { server ->
            val plan = { n: Int ->
                if (n ==
                    0
                ) {
                    ConnectionPlan.DropResponseAfter(BIG / 2L)
                } else {
                    ConnectionPlan.Forward
                }
            }
            withProxy(server, plan) { proxied, direct ->
                direct.writeNew("big", big)
                assertContentEquals(big, proxied.read("big"))
            }
        }

    @Test
    fun aDownloadThatAlwaysDropsFailsAsUnavailable() =
        faultTest { server ->
            withProxy(server, { ConnectionPlan.DropResponseAfter(BIG / 2L) }) { proxied, direct ->
                direct.writeNew("big", big)
                assertFailsWith<BackendUnavailableException> { proxied.read("big") }
            }
        }

    @Test
    fun aServiceUnavailableAnswerIsRetried() =
        faultTest { server ->
            val plan = { n: Int ->
                if (n ==
                    0
                ) {
                    ConnectionPlan.Respond(UNAVAILABLE)
                } else {
                    ConnectionPlan.Forward
                }
            }
            withProxy(server, plan) { proxied, direct ->
                direct.writeNew("a/b", byteArrayOf(1))
                assertEquals(listOf(BlobInfo("a/b", 1)), proxied.list("a"))
            }
        }

    @Test
    fun aServerThatHangsTimesOut() =
        faultTest { server ->
            val settings =
                DavSettings(
                    socketTimeout = 1.seconds,
                    requestTimeout = 5.minutes,
                    attempts = 2,
                    retryDelay = 10.milliseconds,
                )
            withProxy(server, { ConnectionPlan.Stall(1.minutes) }, settings) { proxied, _ ->
                val start = TimeSource.Monotonic.markNow()
                assertIs<Reachability.Unreachable>(proxied.ping())
                assertFailsWith<BackendUnavailableException> { proxied.read("anything") }
                assertTrue(start.elapsedNow() < 30.seconds, "took ${start.elapsedNow()}")
            }
        }

    private companion object {
        const val BIG = 4 * 1024 * 1024 + 7
        const val PATTERN = 7
        const val UNAVAILABLE =
            "HTTP/1.1 503 Service Unavailable\r\nRetry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }
}
