package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.InvalidBlobPathException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.server.HubConfig
import io.github.sameerkrmishra.jotter.server.PeerHub
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val OFF = "Tailscale is not running on this phone"

/** A device that is off its tailnet sends a hub nothing, and says why (ADR-0018). */
class TailnetCheckTest {
    private val refuseAll = TailnetCheck { OFF }

    @Test
    fun aHubIsNotAddedWhileTheCheckRefuses() =
        runTest {
            TempDir().use { dir ->
                Device(dir, "phone").use { phone ->
                    val manager =
                        BackendManager(
                            phone.store,
                            phone.engine,
                            StandardBackendFactory(SecretStores(phone.secrets, phone.fileSecrets)),
                            SecretStores(phone.secrets, phone.fileSecrets),
                            EmptyCoroutineContext,
                            tailnet = refuseAll,
                        )
                    // Nothing listens there: without the check this would wait for a timeout.
                    val added = manager.addPeer("100.101.102.103", Secret("id.secret"))
                    val reason = assertIs<AddResult.Refused>(added).reason
                    assertTrue(reason.contains(OFF), reason)
                    assertEquals(emptyList(), manager.list())
                }
            }
        }

    @Test
    fun aHubAddedEarlierIsUnreachableWhileTheCheckRefuses() =
        runTest {
            TempDir().use { dir ->
                val hub =
                    PeerHub(
                        HubConfig(
                            storeRoot = dir.path / "hub",
                            tokenFile = dir.path / "hub-devices.json",
                            host = "127.0.0.1",
                            port = 0,
                        ),
                    )
                hub.setUpStore()
                val port = hub.start().port
                try {
                    Device(dir, "phone").use { phone ->
                        val token = Secret(hub.pair("phone").token)
                        assertIs<AddResult.Added>(phone.manager.addPeer("127.0.0.1:$port", token))
                        val secrets = SecretStores(phone.secrets, phone.fileSecrets)
                        val offline =
                            BackendManager(
                                phone.store,
                                phone.engine,
                                StandardBackendFactory(secrets, refuseAll),
                                secrets,
                                EmptyCoroutineContext,
                                tailnet = refuseAll,
                            )
                        val sync = offline.syncAll().single()
                        assertEquals(Outcome.UNREACHABLE, sync.attempt.outcome)
                        assertTrue(sync.attempt.detail.contains(OFF), sync.attempt.detail)
                        val status = offline.status().single().reachability
                        assertEquals(
                            Reachability.Unreachable(UnreachableReason.FAILED, OFF),
                            status,
                        )
                        // Back on the tailnet, it syncs.
                        assertEquals(
                            Outcome.SYNCED,
                            phone.manager
                                .syncAll()
                                .single()
                                .attempt.outcome,
                        )
                    }
                } finally {
                    hub.stop()
                }
            }
        }

    /** Lets every host through, on engines of its own that it keeps to look at. */
    private class GivesEngines : TailnetCheck {
        val engines = mutableListOf<HttpClientEngine>()

        override fun refuse(host: String): String? = null

        override fun engine(host: String): HttpClientEngine = CIO.create().also { engines += it }
    }

    @Test
    fun aHubIsReachedThroughTheEngineTheCheckGives() =
        runTest {
            TempDir().use { dir ->
                val hub =
                    PeerHub(
                        HubConfig(
                            storeRoot = dir.path / "hub",
                            tokenFile = dir.path / "hub-devices.json",
                            host = "127.0.0.1",
                            port = 0,
                        ),
                    )
                hub.setUpStore()
                val port = hub.start().port
                try {
                    Device(dir, "phone").use { phone ->
                        val check = GivesEngines()
                        val secrets = SecretStores(phone.secrets, phone.fileSecrets)
                        val manager =
                            BackendManager(
                                phone.store,
                                phone.engine,
                                StandardBackendFactory(secrets, check),
                                secrets,
                                EmptyCoroutineContext,
                                tailnet = check,
                            )
                        val token = Secret(hub.pair("phone").token)
                        assertIs<AddResult.Added>(manager.addPeer("127.0.0.1:$port", token))
                        phone.store.notes.create("", "milk")
                        assertEquals(
                            Outcome.SYNCED,
                            manager
                                .syncAll()
                                .single()
                                .attempt.outcome,
                        )
                        assertTrue(check.engines.size >= 2, "adding and syncing")
                        // Each store closed its engine when it was done.
                        check.engines.forEach { it.coroutineContext.job.join() }
                    }
                } finally {
                    hub.stop()
                }
            }
        }

    @Test
    fun anEngineIsClosedWhenTheTokenCannotBeUsed() {
        val check = GivesEngines()
        assertFailsWith<IllegalArgumentException> {
            openPeer(PeerConfig("127.0.0.1", 1), Secret("not a token!"), check)
        }
        runBlocking {
            check.engines
                .single()
                .coroutineContext.job
                .join()
        }
    }

    @Test
    fun theHubThatCannotBeReachedSendsNothingAndChecksPaths() =
        runTest {
            val store = OffTailnet(OFF)
            val unreachable = Reachability.Unreachable(UnreachableReason.FAILED, OFF)
            assertEquals(unreachable, store.ping())
            assertEquals(
                unreachable,
                assertFailsWith<BackendUnavailableException> { store.list("") }.reachability,
            )
            assertFailsWith<BackendUnavailableException> { store.read("a/b") }
            assertFailsWith<BackendUnavailableException> { store.writeNew("a/b", byteArrayOf(1)) }
            assertFailsWith<InvalidBlobPathException> { store.list("/") }
            assertFailsWith<InvalidBlobPathException> { store.read("../a") }
            assertFailsWith<InvalidBlobPathException> { store.writeNew("", byteArrayOf()) }
            assertEquals(null, TailnetCheck.NONE.refuse("100.101.102.103"))
        }
}
