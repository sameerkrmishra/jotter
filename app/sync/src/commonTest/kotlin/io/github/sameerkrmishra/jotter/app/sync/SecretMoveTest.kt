package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.core.storage.BackendRecord
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Entering a password again, and moving it between the keyring and the file (ADR-0016). */
class SecretMoveTest {
    private val url = "https://dav.example.org/jotter"
    private val password = Secret("app-password-1")

    private fun test(block: suspend TempDir.() -> Unit) = runTest { TempDir().use { it.block() } }

    /** The keyring of a computer that has none: every call fails. */
    private object NoKeyring : SecretStore {
        override suspend fun read(key: SecretKey): Secret? =
            throw SecretStoreUnavailableException("No keyring here.")

        override suspend fun write(
            key: SecretKey,
            secret: Secret,
        ): Unit = throw SecretStoreUnavailableException("No keyring here.")

        override suspend fun delete(key: SecretKey): Unit =
            throw SecretStoreUnavailableException("No keyring here.")
    }

    @Test
    fun afterARestoreWithoutAKeyringThePasswordCanGoToTheFile() =
        test {
            val server = WebDavBackendTest.Server()
            server.marked = true
            val file = InMemorySecretStore()
            Device(this, "a", secrets = NoKeyring, fileSecrets = file, webDav = server).use {
                // A database restored from a computer that kept the password in its keyring.
                it.store.backends.add(
                    BackendRecord(
                        "dav",
                        "webdav",
                        "dav",
                        """{"url":"$url/","username":"me","secrets":"keyring"}""",
                        Instant.fromEpochMilliseconds(0),
                    ),
                )
                assertEquals(
                    Outcome.FAILED,
                    it.manager
                        .syncAll()
                        .single()
                        .attempt.outcome,
                )
                assertEquals(
                    PasswordChange.NoKeyring("No keyring here."),
                    it.manager.changePassword("dav", password),
                )
                assertTrue(file.secrets.isEmpty())
                assertEquals(
                    SecretPlace.KEYRING,
                    it.manager
                        .list()
                        .single()
                        .config.secretPlace,
                )

                val moved = it.manager.changePassword("dav", password, SecretPlace.FILE)
                val left = assertIs<PasswordChange.Moved>(moved).leftBehind!!
                assertEquals(SecretPlace.FILE, moved.to)
                assertTrue(left.startsWith("Jotter could not delete the old copy"), left)
                assertTrue(left.contains("secret-tool clear application jotter backend dav"), left)
                assertEquals(
                    SecretPlace.FILE,
                    it.manager
                        .list()
                        .single()
                        .config.secretPlace,
                )
                assertEquals(password, file.read(SecretKey("dav", SecretKind.APP_PASSWORD)))
                assertEquals(
                    Outcome.SYNCED,
                    it.manager
                        .syncAll()
                        .single()
                        .attempt.outcome,
                )
                assertEquals(password.reveal(), server.passwords.last())

                // Entered again, it stays in the file.
                val next = Secret("app-password-2")
                assertEquals(PasswordChange.Changed, it.manager.changePassword("dav", next))
                assertEquals(next, file.read(SecretKey("dav", SecretKind.APP_PASSWORD)))
            }
        }

    @Test
    fun aPasswordMovesFromTheFileToTheKeyring() =
        test {
            val server = WebDavBackendTest.Server()
            server.marked = true
            val file = InMemorySecretStore()
            Device(this, "a", fileSecrets = file, webDav = server).use { device ->
                val added =
                    device.manager.addWebDav(url, "me", password, secretPlace = SecretPlace.FILE)
                val backend = assertIs<AddResult.Added>(added).backend
                val key = SecretKey(backend.id, SecretKind.APP_PASSWORD)
                val next = Secret("app-password-2")

                assertEquals(
                    PasswordChange.Moved(SecretPlace.KEYRING, null),
                    device.manager.changePassword(backend.id, next, SecretPlace.KEYRING),
                )
                assertEquals(next, device.secrets.read(key))
                assertTrue(file.secrets.isEmpty())
                val now = device.manager.list().single()
                assertEquals(SecretPlace.KEYRING, now.config.secretPlace)
                assertEquals(
                    "Its password is kept in the keyring.",
                    device.manager.whereSecretsAre(now),
                )
                assertEquals(
                    Outcome.SYNCED,
                    device.manager
                        .syncAll()
                        .single()
                        .attempt.outcome,
                )
                assertEquals(next.reveal(), server.passwords.last())
            }
        }

    /** A file store that does [onWrite] after each write, and whose delete may fail. */
    private class HookedFile : SecretStore {
        val file = InMemorySecretStore()
        var onWrite: () -> Unit = {}
        var deleteFails = false

        override suspend fun read(key: SecretKey): Secret? = file.read(key)

        override suspend fun write(
            key: SecretKey,
            secret: Secret,
        ) {
            file.write(key, secret)
            onWrite()
        }

        override suspend fun delete(key: SecretKey) {
            if (deleteFails) throw SecretStoreException("The disk is read-only.")
            file.delete(key)
        }
    }

    /** Adds a backend to the keyring of [device] and tries to move its password to the file. */
    private suspend fun moveToFile(
        device: Device,
        server: WebDavBackendTest.Server,
        file: HookedFile,
        meanwhile: (Backend) -> Unit,
    ): PasswordChange {
        server.marked = true
        val backend =
            assertIs<AddResult.Added>(device.manager.addWebDav(url, "me", password)).backend
        file.onWrite = { meanwhile(backend) }
        val moved = device.manager.changePassword(backend.id, Secret("x"), SecretPlace.FILE)
        assertEquals(password, device.secrets.read(SecretKey(backend.id, SecretKind.APP_PASSWORD)))
        return moved
    }

    @Test
    fun aMoveToABackendRemovedMeanwhileTakesTheNewCopyBack() =
        test {
            val file = HookedFile()
            val server = WebDavBackendTest.Server()
            Device(this, "a", fileSecrets = file, webDav = server).use { device ->
                var id = ""
                val result =
                    moveToFile(device, server, file) {
                        id = it.id
                        device.store.backends.remove(it.id)
                    }
                assertEquals(PasswordChange.Refused("$id was removed meanwhile."), result)
                assertTrue(file.file.secrets.isEmpty())
            }
        }

    @Test
    fun aMoveThatCannotBeRecordedTakesTheNewCopyBack() =
        test {
            val file = HookedFile()
            val server = WebDavBackendTest.Server()
            Device(this, "a", fileSecrets = file, webDav = server).use { device ->
                // The database goes away between writing the new copy and recording it.
                val result = moveToFile(device, server, file) { device.store.close() }
                val reason = assertIs<PasswordChange.Refused>(result).reason
                assertTrue(reason.startsWith("Cannot record where the secret is kept: "), reason)
                assertTrue(reason.endsWith("It is still kept where it was."), reason)
                assertTrue(file.file.secrets.isEmpty())
            }
        }

    @Test
    fun aRollBackThatCannotDeleteSaysWhereTheNewCopyIs() =
        test {
            val file = HookedFile()
            file.deleteFails = true
            val server = WebDavBackendTest.Server()
            Device(this, "a", fileSecrets = file, webDav = server).use { device ->
                val result = moveToFile(device, server, file) { device.store.close() }
                val reason = assertIs<PasswordChange.Refused>(result).reason
                assertTrue(reason.startsWith("Cannot record where the secret is kept: "), reason)
                assertTrue(
                    reason.contains(
                        "It is still kept where it was. Jotter could not delete the new copy " +
                            "either: The disk is read-only. Its password may still be in " +
                            "/config/secrets.json, under ",
                    ),
                    reason,
                )
                assertEquals(1, file.file.secrets.size)
            }
        }

    @Test
    fun aCancelledMoveTakesTheNewCopyBack() =
        test {
            val server = WebDavBackendTest.Server()
            server.marked = true
            val file = InMemorySecretStore()
            // Cancels the change once the new copy is written, before it is recorded.
            val cancelling =
                object : SecretStore by file {
                    override suspend fun write(
                        key: SecretKey,
                        secret: Secret,
                    ) {
                        file.write(key, secret)
                        currentCoroutineContext().cancel()
                    }
                }
            Device(this, "a", fileSecrets = cancelling, webDav = server).use { device ->
                val backend =
                    assertIs<AddResult.Added>(device.manager.addWebDav(url, "me", password)).backend
                val job =
                    coroutineScope {
                        launch {
                            device.manager.changePassword(
                                backend.id,
                                Secret("x"),
                                SecretPlace.FILE,
                            )
                        }
                    }
                job.join()
                assertTrue(job.isCancelled)
                assertTrue(file.secrets.isEmpty())
                assertEquals(
                    SecretPlace.KEYRING,
                    device.manager
                        .list()
                        .single()
                        .config.secretPlace,
                )
                assertEquals(
                    password,
                    device.secrets.read(SecretKey(backend.id, SecretKind.APP_PASSWORD)),
                )
            }
        }

    @Test
    fun whereBothPlacesAreOneStoreAFailedMoveKeepsTheSecret() =
        test {
            val server = WebDavBackendTest.Server()
            server.marked = true
            val sealed = HookedFile()
            Device(
                this,
                "a",
                secrets = sealed,
                fileSecrets = sealed,
                webDav = server,
                home = SecretHome.ANDROID,
            ).use { device ->
                val backend =
                    assertIs<AddResult.Added>(device.manager.addWebDav(url, "me", password)).backend
                sealed.onWrite = { device.store.close() }
                val result =
                    device.manager.changePassword(
                        backend.id,
                        Secret("x"),
                        SecretPlace.FILE,
                    )
                val reason = assertIs<PasswordChange.Refused>(result).reason
                assertTrue(reason.endsWith("It is still kept where it was."), reason)
                // The one store holds the new secret, which the unchanged record still finds.
                assertEquals(
                    Secret("x"),
                    sealed.read(SecretKey(backend.id, SecretKind.APP_PASSWORD)),
                )
            }
        }

    @Test
    fun withoutAKeyringAPasswordCannotMoveThereAndStaysInTheFile() =
        test {
            val server = WebDavBackendTest.Server()
            server.marked = true
            val file = InMemorySecretStore()
            Device(this, "a", secrets = NoKeyring, fileSecrets = file, webDav = server).use {
                val added =
                    it.manager.addWebDav(
                        url,
                        "me",
                        password,
                        secretPlace = SecretPlace.FILE,
                    )
                val backend = assertIs<AddResult.Added>(added).backend
                assertEquals(
                    PasswordChange.NoKeyring("No keyring here."),
                    it.manager.changePassword(backend.id, Secret("x"), SecretPlace.KEYRING),
                )
                assertEquals(password, file.read(SecretKey(backend.id, SecretKind.APP_PASSWORD)))
                assertEquals(
                    SecretPlace.FILE,
                    it.manager
                        .list()
                        .single()
                        .config.secretPlace,
                )
                // Asking for the place it already has is a plain change.
                assertEquals(
                    PasswordChange.Changed,
                    it.manager.changePassword(backend.id, Secret("x"), SecretPlace.FILE),
                )
                assertEquals(Secret("x"), file.read(SecretKey(backend.id, SecretKind.APP_PASSWORD)))
            }
            assertEquals("the keyring", describe(SecretPlace.KEYRING))
            assertEquals("the secrets file, in plain text", describe(SecretPlace.FILE))
        }

    @Test
    fun aFileThatIsUnavailableIsNotOfferedAgain() =
        test {
            val server = WebDavBackendTest.Server()
            server.marked = true
            val gone =
                object : SecretStore by InMemorySecretStore() {
                    override suspend fun write(
                        key: SecretKey,
                        secret: Secret,
                    ): Unit = throw SecretStoreUnavailableException("The config folder is gone.")
                }
            Device(this, "a", fileSecrets = gone, webDav = server).use { device ->
                val backend =
                    assertIs<AddResult.Added>(device.manager.addWebDav(url, "me", password)).backend
                assertEquals(
                    PasswordChange.Refused(
                        "Cannot store the app password: The config folder is gone.",
                    ),
                    device.manager.changePassword(backend.id, Secret("x"), SecretPlace.FILE),
                )
                assertEquals(
                    password,
                    device.secrets.read(SecretKey(backend.id, SecretKind.APP_PASSWORD)),
                )
            }
        }

    @Test
    fun aFailureWithoutAMessageIsNamedByItsKind() {
        assertEquals(
            "Cannot record where the secret is kept: IllegalStateException. It is still kept " +
                "where it was.",
            notRecorded(IllegalStateException()),
        )
        assertEquals(
            "Cannot record where the secret is kept: disk gone. It is still kept where it was.",
            notRecorded(IllegalStateException("disk gone")),
        )
    }

    @Test
    fun aMoveThatCannotWriteChangesNothing() =
        test {
            val server = WebDavBackendTest.Server()
            server.marked = true
            val full =
                object : SecretStore by InMemorySecretStore() {
                    override suspend fun write(
                        key: SecretKey,
                        secret: Secret,
                    ): Unit = throw SecretStoreException("The disk is full.")
                }
            Device(this, "a", fileSecrets = full, webDav = server).use { device ->
                val backend =
                    assertIs<AddResult.Added>(device.manager.addWebDav(url, "me", password)).backend
                val key = SecretKey(backend.id, SecretKind.APP_PASSWORD)
                assertEquals(
                    PasswordChange.Refused("Cannot store the app password: The disk is full."),
                    device.manager.changePassword(backend.id, Secret("x"), SecretPlace.FILE),
                )
                assertEquals(password, device.secrets.read(key))
                assertEquals(
                    SecretPlace.KEYRING,
                    device.manager
                        .list()
                        .single()
                        .config.secretPlace,
                )
            }
        }

    @Test
    fun whereBothPlacesAreOneStoreAMoveKeepsTheSecret() =
        test {
            val server = WebDavBackendTest.Server()
            server.marked = true
            val sealed = InMemorySecretStore()
            Device(
                this,
                "a",
                secrets = sealed,
                fileSecrets = sealed,
                webDav = server,
                home = SecretHome.ANDROID,
            ).use { device ->
                val backend =
                    assertIs<AddResult.Added>(device.manager.addWebDav(url, "me", password)).backend
                assertEquals(
                    PasswordChange.Moved(SecretPlace.FILE, null),
                    device.manager.changePassword(backend.id, password, SecretPlace.FILE),
                )
                assertEquals(password, sealed.read(SecretKey(backend.id, SecretKind.APP_PASSWORD)))
            }
        }
}
