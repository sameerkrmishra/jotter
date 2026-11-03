package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.core.storage.BackendRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Adding, changing and removing a WebDAV backend, against a stand-in for the server. The real
 * client is tested in `backend:webdav`, and against real servers by `app:shared-ui`'s live test.
 */
class WebDavBackendTest {
    private val url = "https://dav.example.org/jotter"
    private val password = Secret("app-password-1")

    /** A server's collection, shared by every store opened on it, and what the stores were. */
    private class Server : WebDavOpener {
        val files = InMemoryBlobStore()
        var marked = false
        var answer: Reachability.Unreachable? = null
        var setUpFails: Exception? = null
        val passwords = mutableListOf<String>()
        var closed = 0

        override fun open(
            config: WebDavConfig,
            password: Secret,
        ): NewStore {
            passwords += password.reveal()
            return object : NewStore, BlobStore by files {
                override suspend fun ping(): Reachability =
                    answer ?: if (marked) {
                        Reachability.Reachable
                    } else {
                        Reachability.Unreachable(UnreachableReason.NOT_INITIALIZED, "no marker")
                    }

                override suspend fun initialize() {
                    setUpFails?.let { throw it }
                    marked = true
                }

                override fun close() {
                    closed++
                }
            }
        }
    }

    private fun test(block: suspend TempDir.() -> Unit) = runTest { TempDir().use { it.block() } }

    @Test
    fun aNewCollectionIsSetUpOnlyWhenTheUserSaysSo() =
        test {
            val server = Server()
            Device(this, "a", webDav = server).use { device ->
                val asked = device.manager.addWebDav(url, " me ", password)
                assertEquals(AddResult.NotAStoreYet("$url/"), asked)
                assertFalse(server.marked)
                assertEquals(emptyList(), device.manager.list())
                assertTrue((device.secrets as InMemorySecretStore).secrets.isEmpty())

                val added = device.manager.addWebDav(url, "me", password, setUpNewStore = true)
                val backend = assertIs<AddResult.Added>(added).backend
                assertTrue(server.marked)
                assertEquals(WebDavConfig("$url/", "me"), backend.config)
                assertEquals("dav.example.org", backend.name)
                val key = SecretKey(backend.id, SecretKind.APP_PASSWORD)
                assertEquals(password, device.secrets.read(key))
                // The database has the settings, never the password.
                val stored =
                    device.store.backends
                        .list()
                        .single()
                assertFalse(stored.settings.contains(password.reveal()), stored.settings)
                assertEquals(2, server.closed)
                assertEquals(
                    "Its password is kept in the keyring.",
                    device.manager.whereSecretsAre(backend),
                )

                val again = device.manager.addWebDav("$url/", "me", password)
                assertEquals(AddResult.AlreadyAdded(backend), again)
            }
        }

    @Test
    fun aStoreAnotherDeviceSetUpIsAddedAndSyncs() =
        test {
            val server = Server()
            Device(this, "a", webDav = server).use { a ->
                assertIs<AddResult.Added>(
                    a.manager.addWebDav(url, "me", password, setUpNewStore = true),
                )
                val note = a.store.notes.create("", "milk")
                Device(this, "b", webDav = server).use { b ->
                    val added = b.manager.addWebDav(url, "me", password, name = "Mailbox")
                    assertEquals("Mailbox", assertIs<AddResult.Added>(added).backend.name)
                    assertEquals(
                        Outcome.SYNCED,
                        a.manager
                            .syncAll()
                            .single()
                            .attempt.outcome,
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
                }
            }
        }

    @Test
    fun whatTheServerSaysIsShownPlainly() =
        test {
            val server = Server()
            Device(this, "a", webDav = server).use { device ->
                suspend fun refusal(reason: UnreachableReason): String {
                    server.answer = Reachability.Unreachable(reason, "detail")
                    val result = device.manager.addWebDav(url, "me", password, setUpNewStore = true)
                    return assertIs<AddResult.Refused>(result).reason
                }
                assertTrue(refusal(UnreachableReason.MISSING).contains("Create it first"))
                assertTrue(
                    refusal(UnreachableReason.UNAUTHORIZED).contains("refused the user name"),
                )
                assertEquals(
                    "Cannot reach https://dav.example.org/jotter/: detail.",
                    refusal(UnreachableReason.FAILED),
                )
                server.answer = null

                fun refused(result: AddResult) = assertIs<AddResult.Refused>(result).reason
                assertEquals(
                    "Enter the user name of the account.",
                    refused(device.manager.addWebDav(url, " ", password)),
                )
                assertEquals(
                    "Enter the app password.",
                    refused(device.manager.addWebDav(url, "me", Secret(""))),
                )
                assertTrue(
                    device.store.backends
                        .list()
                        .isEmpty(),
                )
            }
        }

    @Test
    fun plainHttpIsRefusedExceptToThisMachine() =
        test {
            Device(this, "a").use { device ->
                val result = device.manager.addWebDav("http://dav.example.org/x/", "me", password)
                val reason = assertIs<AddResult.Refused>(result).reason
                assertTrue(reason.contains("Plain http would send the password"), reason)
                assertFalse(reason.contains(password.reveal()))
            }
        }

    @Test
    fun aServerThatReplacesFilesIsRefusedAndItsPasswordForgotten() =
        test {
            val server = Server()
            server.setUpFails =
                BlobStoreFailureException(
                    "The server at x ignores If-None-Match: *, so it would let one write " +
                        "replace another",
                )
            val keyring = InMemorySecretStore()
            Device(this, "a", secrets = keyring, webDav = server).use { device ->
                val result = device.manager.addWebDav(url, "me", password, setUpNewStore = true)
                val reason = assertIs<AddResult.Refused>(result).reason
                assertTrue(reason.contains("the server replaces a file"), reason)
                assertTrue(reason.contains("Use another server"), reason)
                assertTrue(keyring.secrets.isEmpty())
                assertTrue(
                    device.store.backends
                        .list()
                        .isEmpty(),
                )

                server.setUpFails = BlobStoreFailureException("disk full")
                val other = device.manager.addWebDav(url, "me", password, setUpNewStore = true)
                assertEquals(
                    "Cannot set up https://dav.example.org/jotter/: disk full",
                    assertIs<AddResult.Refused>(other).reason,
                )
                server.setUpFails = IllegalStateException("bug")
                assertFailsWith<IllegalStateException> {
                    device.manager.addWebDav(url, "me", password, setUpNewStore = true)
                }
                assertTrue(keyring.secrets.isEmpty())
            }
        }

    @Test
    fun withoutAKeyringTheUserMayChooseTheFile() =
        test {
            val server = Server()
            server.marked = true
            val noKeyring =
                object : SecretStore by InMemorySecretStore() {
                    override suspend fun write(
                        key: SecretKey,
                        secret: Secret,
                    ): Unit = throw SecretStoreUnavailableException("No keyring here.")
                }
            val file = InMemorySecretStore()
            val device = Device(this, "a", secrets = noKeyring, fileSecrets = file, webDav = server)
            device.use {
                val first = device.manager.addWebDav(url, "me", password)
                assertEquals(AddResult.NoKeyring("No keyring here."), first)
                assertTrue(
                    device.store.backends
                        .list()
                        .isEmpty(),
                )

                val chosen =
                    device.manager.addWebDav(url, "me", password, secretPlace = SecretPlace.FILE)
                val backend = assertIs<AddResult.Added>(chosen).backend
                assertEquals(SecretPlace.FILE, backend.config.secretPlace)
                assertEquals(password, file.read(SecretKey(backend.id, SecretKind.APP_PASSWORD)))
                assertEquals(
                    "Its password is kept in plain text in /config/secrets.json, as chosen when " +
                        "it was added.",
                    device.manager.whereSecretsAre(backend),
                )
                assertTrue(device.manager.remove(backend.id))
                assertTrue(file.secrets.isEmpty())
            }
            val locked =
                object : SecretStore by InMemorySecretStore() {
                    override suspend fun write(
                        key: SecretKey,
                        secret: Secret,
                    ): Unit = throw SecretStoreException("The keyring is locked.")
                }
            Device(this, "b", secrets = locked, webDav = server).use { device ->
                val result = device.manager.addWebDav(url, "me", password)
                assertEquals(
                    "Cannot store the app password: The keyring is locked.",
                    assertIs<AddResult.Refused>(result).reason,
                )
            }
        }

    @Test
    fun removingDeletesThePasswordOrNothing() =
        test {
            val server = Server()
            server.marked = true
            var deleteFails = false
            val keyring = InMemorySecretStore()
            val flaky =
                object : SecretStore by keyring {
                    override suspend fun delete(key: SecretKey) {
                        if (deleteFails) throw SecretStoreUnavailableException("No keyring here.")
                        keyring.delete(key)
                    }
                }
            Device(this, "a", secrets = flaky, webDav = server).use { device ->
                val backend =
                    assertIs<AddResult.Added>(device.manager.addWebDav(url, "me", password)).backend
                deleteFails = true
                assertFailsWith<SecretStoreUnavailableException> {
                    device.manager.remove(
                        backend.id,
                    )
                }
                assertEquals(listOf(backend.id), device.manager.list().map { it.id })
                deleteFails = false
                assertTrue(device.manager.remove(backend.id))
                assertTrue(keyring.secrets.isEmpty())
                assertNull(device.manager.whereSecretsAre(local()))
            }
        }

    @Test
    fun aNewPasswordIsCheckedBeforeItIsStored() =
        test {
            val server = Server()
            server.marked = true
            Device(this, "a", webDav = server).use { device ->
                val backend =
                    assertIs<AddResult.Added>(device.manager.addWebDav(url, "me", password)).backend
                val key = SecretKey(backend.id, SecretKind.APP_PASSWORD)
                val next = Secret("app-password-2")

                server.answer = Reachability.Unreachable(UnreachableReason.UNAUTHORIZED, "401")
                assertEquals(
                    PasswordChange.Refused(
                        "The server refused this app password too. Nothing was changed.",
                    ),
                    device.manager.changePassword(backend.id, next),
                )
                assertEquals(password, device.secrets.read(key))

                server.answer = null
                assertEquals(
                    PasswordChange.Changed,
                    device.manager.changePassword(backend.id, next),
                )
                assertEquals(next, device.secrets.read(key))
                assertEquals("app-password-2", server.passwords.last())

                assertEquals(
                    PasswordChange.Refused("Enter the app password."),
                    device.manager.changePassword(backend.id, Secret("")),
                )
                assertEquals(
                    PasswordChange.Refused("There is no WebDAV backend nope."),
                    device.manager.changePassword("nope", next),
                )
            }
            val locked =
                object : SecretStore by InMemorySecretStore() {
                    override suspend fun write(
                        key: SecretKey,
                        secret: Secret,
                    ): Unit = throw SecretStoreException("The keyring is locked.")
                }
            Device(this, "b", secrets = locked, webDav = server).use { device ->
                device.store.backends.add(
                    BackendRecord(
                        "dav",
                        "webdav",
                        "dav",
                        """{"url":"$url/","username":"me"}""",
                        Instant.fromEpochMilliseconds(0),
                    ),
                )
                assertEquals(
                    PasswordChange.Refused("Cannot store the app password: The keyring is locked."),
                    device.manager.changePassword("dav", Secret("x")),
                )
            }
        }

    @Test
    fun aBackendWhosePasswordIsMissingAsksForIt() =
        test {
            Device(this, "a").use { device ->
                device.store.backends.add(
                    BackendRecord(
                        "dav",
                        "webdav",
                        "dav",
                        """{"url":"https://dav.example.org/","username":"me"}""",
                        Instant.fromEpochMilliseconds(0),
                    ),
                )
                val sync = device.manager.syncAll().single()
                assertEquals(Outcome.UNAUTHORIZED, sync.attempt.outcome)
                assertEquals(NO_PASSWORD, sync.attempt.detail)
                val status = device.manager.status().single()
                assertEquals(
                    Reachability.Unreachable(UnreachableReason.FAILED, NO_PASSWORD),
                    status.reachability,
                )
            }
        }

    private fun local() =
        Backend(
            "f",
            "f",
            LocalFolderConfig("/f"),
            Instant.fromEpochMilliseconds(0),
            null,
            null,
        )
}
