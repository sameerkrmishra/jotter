package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.core.storage.BackendRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

/**
 * Where a secret is, and what a missing or unreadable one means, in the words of the device: a
 * Linux desktop has a keyring, Android has none. Never network advice for a secret.
 */
class SecretWordsTest {
    private fun test(block: suspend TempDir.() -> Unit) = runTest { TempDir().use { it.block() } }

    private val dav = WebDavConfig("https://dav.example.org/", "me")
    private val hub = PeerConfig("100.64.0.1", 7469)

    private fun Device.addRecord(
        id: String,
        kind: String,
        settings: String,
    ) = store.backends.add(BackendRecord(id, kind, id, settings, Instant.fromEpochMilliseconds(0)))

    @Test
    fun onAndroidSecretsAreInTheKeystoreNotAKeyring() =
        test {
            Device(this, "phone", home = SecretHome.ANDROID).use { device ->
                device.addRecord("dav", "webdav", """{"url":"https://dav.example.org/","username":"me"}""")
                device.addRecord("hub", "peer", """{"host":"127.0.0.1","port":1}""")
                device.manager.list().forEach { backend ->
                    val where = device.manager.whereSecretsAre(backend).orEmpty()
                    assertContains(where, "kept on this phone only")
                    assertContains(where, "Android Keystore")
                    assertFalse("keyring" in where, where)
                    val left = device.manager.secretsLeftBehind(backend).orEmpty()
                    assertContains(left, "Android Keystore")
                    assertFalse("keyring" in left || "secret-tool" in left, left)
                }
                assertEquals(
                    "Its password is kept on this phone only, in Jotter's own storage, sealed " +
                        "with a key in the Android Keystore. No backup copies it.",
                    device.manager.whereSecretsAre(device.manager.list().first { it.id == "dav" }),
                )
            }
        }

    @Test
    fun onAndroidAMissingSecretSaysToEnterItAgain() =
        test {
            Device(this, "phone", home = SecretHome.ANDROID).use { device ->
                device.addRecord("dav", "webdav", """{"url":"https://dav.example.org/","username":"me"}""")
                val sync = device.manager.syncAll().single()
                assertEquals(Outcome.UNAUTHORIZED, sync.attempt.outcome)
                assertEquals(NO_PASSWORD_ANDROID, sync.attempt.detail)
                val status = device.manager.status().single()
                assertEquals(NO_PASSWORD_ANDROID, status.reachabilityText)
                assertEquals(
                    UnreachableReason.UNAUTHORIZED,
                    (status.reachability as Reachability.Unreachable).reason,
                )
                assertContains(status.reachabilityText, "Enter the password again.")
                assertFalse("keyring" in status.reachabilityText)
                assertFalse("network" in status.reachabilityText)
            }
        }

    @Test
    fun theWordsForEachKindOfSecretAndDevice() {
        assertEquals(NO_PASSWORD, noSecret(dav, SecretHome.LINUX))
        assertEquals(NO_TOKEN, noSecret(hub, SecretHome.LINUX))
        assertEquals(NO_PASSWORD_ANDROID, noSecret(dav, SecretHome.ANDROID))
        assertEquals(NO_TOKEN_ANDROID, noSecret(hub, SecretHome.ANDROID))
        assertFalse("keyring" in NO_PASSWORD_ANDROID || "keyring" in NO_TOKEN_ANDROID)

        assertEquals(
            "Jotter could not read this backend's password on this phone: The Keystore key is " +
                "gone. Enter the password again.",
            unreadableSecret(dav, "The Keystore key is gone.", SecretHome.ANDROID),
        )
        assertEquals(
            "Jotter could not read this hub's pairing token. Unlock your keyring if it is " +
                "locked, or pair this device on the PC again and enter the new token.",
            unreadableSecret(hub, null, SecretHome.LINUX),
        )
        // One period, however the reason ends.
        val said = unreadableSecret(dav, "The keyring is locked..", SecretHome.LINUX)
        assertFalse(".." in said, said)
    }

    @Test
    fun aDetailThatEndsWithAPeriodGetsNoSecondOne() {
        val said = describe(Reachability.Unreachable(UnreachableReason.FAILED, "Connection refused."))
        assertEquals(
            "Cannot be reached: Connection refused. Check the network, or that the drive or " +
                "server is there; Jotter tries again by itself.",
            said,
        )
    }
}
