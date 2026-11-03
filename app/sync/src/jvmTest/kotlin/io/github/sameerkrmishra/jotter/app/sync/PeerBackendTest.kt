package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.server.HubConfig
import io.github.sameerkrmishra.jotter.server.PeerHub
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The PC hub as a backend, against a real hub from the `server` module on 127.0.0.1. */
@Suppress("LongMethod") // Each test is one story, from pairing to unpairing.
class PeerBackendTest {
    /** A hub in [dir] on 127.0.0.1 and a free port, set up and started. */
    private class Hub(
        dir: TempDir,
        setUp: Boolean = true,
    ) : AutoCloseable {
        val peerHub =
            PeerHub(
                HubConfig(
                    storeRoot = dir.path / "hub",
                    tokenFile = dir.path / "hub-devices.json",
                    host = "127.0.0.1",
                    port = 0,
                ),
            )
        val address: String

        init {
            address =
                runBlocking {
                    if (setUp) peerHub.setUpStore()
                    "127.0.0.1:${peerHub.start().port}"
                }
        }

        fun pair(name: String) = runBlocking { peerHub.pair(name) }

        override fun close() = runBlocking { peerHub.stop() }
    }

    private fun test(block: suspend TempDir.() -> Unit) = runTest { TempDir().use { it.block() } }

    @Test
    fun twoDevicesSyncThroughTheHubUntilOneIsUnpaired() =
        test {
            Hub(this).use { hub ->
                val laptop = hub.pair("laptop")
                val phone = hub.pair("phone")
                Device(this, "a").use { a ->
                    Device(this, "b").use { b ->
                        val added = a.manager.addPeer(hub.address, Secret(laptop.token))
                        val backend = assertIs<AddResult.Added>(added).backend
                        assertEquals("PC hub", backend.name)
                        assertEquals(
                            PeerConfig("127.0.0.1", hub.address.substringAfter(':').toInt()),
                            backend.config,
                        )
                        val stored =
                            a.store.backends
                                .list()
                                .single()
                                .settings
                        assertFalse(stored.contains(laptop.token), stored)
                        assertEquals(
                            Secret(laptop.token),
                            a.secrets.read(SecretKey(backend.id, SecretKind.PAIRING_SECRET)),
                        )
                        assertEquals(
                            "Its pairing token is kept in the keyring.",
                            a.manager.whereSecretsAre(backend),
                        )
                        assertIs<AddResult.AlreadyAdded>(
                            a.manager.addPeer(hub.address, Secret(laptop.token)),
                        )

                        val note = a.store.notes.create("", "milk")
                        assertEquals(
                            Outcome.SYNCED,
                            a.manager
                                .syncAll()
                                .single()
                                .attempt.outcome,
                        )
                        val other =
                            assertIs<AddResult.Added>(
                                b.manager.addPeer(hub.address, Secret(phone.token)),
                            )
                        assertEquals(
                            Outcome.SYNCED,
                            b.manager
                                .syncAll()
                                .single()
                                .attempt.outcome,
                        )
                        assertEquals(
                            "milk",
                            b.store.notes
                                .get(note.id)
                                ?.body,
                        )

                        hub.peerHub.unpair(phone.device.id)
                        val refused =
                            b.manager
                                .syncAll()
                                .single()
                                .attempt
                        assertEquals(Outcome.UNAUTHORIZED, refused.outcome)
                        assertTrue(refused.detail.startsWith(UNPAIRED), refused.detail)
                        assertEquals(
                            UNPAIRED,
                            b.manager
                                .status()
                                .single()
                                .reachabilityText,
                        )

                        val again = hub.pair("phone")
                        assertEquals(
                            PasswordChange.Refused(
                                "The hub refused this pairing token too. Nothing was changed.",
                            ),
                            b.manager.changePassword(other.backend.id, Secret(phone.token)),
                        )
                        assertEquals(
                            PasswordChange.Changed,
                            b.manager.changePassword(other.backend.id, Secret(again.token)),
                        )
                        assertEquals(
                            Outcome.SYNCED,
                            b.manager
                                .syncAll()
                                .single()
                                .attempt.outcome,
                        )

                        assertTrue(b.manager.remove(other.backend.id))
                        assertTrue((b.secrets as InMemorySecretStore).secrets.isEmpty())
                    }
                }
            }
        }

    @Test
    fun whatTheHubOrTheAddressSaysIsShownPlainly() =
        test {
            Hub(this).use { hub ->
                val token = hub.pair("laptop").token
                Device(this, "a").use { device ->
                    suspend fun refused(
                        address: String,
                        secret: String = token,
                    ) = assertIs<AddResult.Refused>(
                        device.manager.addPeer(address, Secret(secret)),
                    ).reason

                    assertTrue(
                        refused("192.168.1.20").startsWith(
                            "192.168.1.20 is not on your tailnet or this machine.",
                        ),
                    )
                    assertTrue(
                        refused("8.8.8.8:4000").contains("only to the PC's Tailscale address"),
                    )
                    assertTrue(refused("bad host!").startsWith("Not a hub address:"))
                    assertTrue(refused(hub.address, "").startsWith("Enter the pairing token"))
                    assertTrue(
                        refused(
                            hub.address,
                            "0".repeat(32) + ".nope",
                        ).contains("refused this pairing token"),
                    )
                    assertTrue(
                        refused(hub.address, "a\nb").startsWith("Cannot use this pairing token"),
                    )
                    assertTrue(
                        refused("127.0.0.1:1").startsWith("Cannot reach the hub at 127.0.0.1:1"),
                    )
                    assertTrue(
                        device.store.backends
                            .list()
                            .isEmpty(),
                    )
                }
            }
            TempDir().use { other ->
                Hub(other, setUp = false).use { bare ->
                    Device(this, "b").use { device ->
                        val result =
                            device.manager.addPeer(
                                bare.address,
                                Secret(bare.pair("x").token),
                            )
                        assertTrue(
                            assertIs<AddResult.Refused>(
                                result,
                            ).reason.contains("Set the hub up on the PC first"),
                        )
                    }
                }
            }
        }

    @Test
    fun withoutAKeyringTheTokenMayGoInTheFile() =
        test {
            Hub(this).use { hub ->
                val noKeyring =
                    object : SecretStore by InMemorySecretStore() {
                        override suspend fun write(
                            key: SecretKey,
                            secret: Secret,
                        ): Unit = throw SecretStoreUnavailableException("No keyring here.")
                    }
                val file = InMemorySecretStore()
                Device(this, "a", secrets = noKeyring, fileSecrets = file).use { device ->
                    val token = Secret(hub.pair("laptop").token)
                    assertEquals(
                        AddResult.NoKeyring("No keyring here."),
                        device.manager.addPeer(hub.address, token),
                    )
                    val added = device.manager.addPeer(hub.address, token, "Desk", SecretPlace.FILE)
                    assertEquals("Desk", assertIs<AddResult.Added>(added).backend.name)
                    assertEquals(1, file.secrets.size)
                }
            }
        }

    @Test
    fun aHubWithoutItsTokenAsksForIt() =
        test {
            Device(this, "a").use { device ->
                device.store.backends.add(
                    io.github.sameerkrmishra.jotter.core.storage.BackendRecord(
                        "hub",
                        "peer",
                        "hub",
                        """{"host":"127.0.0.1","port":1}""",
                        kotlin.time.Instant.fromEpochMilliseconds(0),
                    ),
                )
                val sync = device.manager.syncAll().single()
                assertEquals(Outcome.UNAUTHORIZED, sync.attempt.outcome)
                assertEquals(NO_TOKEN, sync.attempt.detail)
                assertEquals(
                    Reachability.Unreachable(UnreachableReason.FAILED, NO_TOKEN),
                    device.manager
                        .status()
                        .single()
                        .reachability,
                )
            }
        }

    @Test
    fun aHubRoundTripsWithoutItsToken() {
        val config = PeerConfig("100.101.102.103", 4999)
        val settings = BackendKinds.settingsOf(config)
        assertEquals("""{"host":"100.101.102.103","port":4999}""", settings)
        assertEquals(config, BackendKinds.configOf("peer", settings))
        val inFile = config.copy(secretPlace = SecretPlace.FILE)
        assertEquals(inFile, BackendKinds.configOf("peer", BackendKinds.settingsOf(inFile)))
        assertEquals("the PC hub at 100.101.102.103:4999", config.location)
        assertEquals(setOf(SecretKind.PAIRING_SECRET), config.secrets)
        val notSetUp = Reachability.Unreachable(UnreachableReason.NOT_INITIALIZED, "no marker")
        assertTrue(describe(notSetUp, config).contains("Set the hub up again on the PC"))
        assertEquals("Reachable.", describe(Reachability.Reachable, config))
    }
}
