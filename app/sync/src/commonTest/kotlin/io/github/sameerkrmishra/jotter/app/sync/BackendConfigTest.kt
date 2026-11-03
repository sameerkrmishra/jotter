package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.backend.localfolder.LocalFolderBlobStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** How backends are stored and opened, and how their secrets are kept out of sight. */
class BackendConfigTest {
    private val keyring = InMemorySecretStore()
    private val factory = StandardBackendFactory(SecretStores(keyring, NoSecretStore))

    @Test
    fun aLocalFolderRoundTrips() {
        val config = LocalFolderConfig("/media/stick")
        val kind = BackendKinds.kindOf(config)
        assertEquals("local-folder", kind)
        assertEquals(config, BackendKinds.configOf(kind, BackendKinds.settingsOf(config)))
        assertEquals("/media/stick", config.location)
        assertEquals(emptySet(), config.secrets)
    }

    @Test
    fun whatThisVersionCannotReadIsUnknown() {
        assertEquals(UnknownConfig("webdav"), BackendKinds.configOf("webdav", """{"url":"x"}"""))
        assertEquals(UnknownConfig("local-folder"), BackendKinds.configOf("local-folder", "{"))
        assertEquals(UnknownConfig("local-folder"), BackendKinds.configOf("local-folder", "[]"))
        val unknown = UnknownConfig("peer")
        assertEquals("peer", BackendKinds.kindOf(unknown))
        assertTrue(unknown.location.contains("peer"))
        assertEquals(emptySet(), unknown.secrets)
        assertFailsWith<IllegalArgumentException> { BackendKinds.settingsOf(unknown) }
    }

    @Test
    fun theFactoryOpensWhatThisVersionKnows() =
        runTest {
            val folder = backend(LocalFolderConfig("/media/stick"))
            assertIs<LocalFolderBlobStore>(factory.open(folder))
            assertFailsWith<IllegalArgumentException> {
                factory.open(backend(UnknownConfig("peer")))
            }
            val stores = NamedStores(mapOf("x" to InMemoryBlobStore()))
            assertIs<InMemoryBlobStore>(stores.open(backend(LocalFolderConfig("/x"))))
        }

    @Test
    fun aWebDavServerRoundTripsWithoutItsPassword() {
        val config = WebDavConfig("https://dav.example.org/jotter/", "me@example.org")
        val kind = BackendKinds.kindOf(config)
        assertEquals("webdav", kind)
        val settings = BackendKinds.settingsOf(config)
        assertEquals(
            """{"url":"https://dav.example.org/jotter/","username":"me@example.org"}""",
            settings,
        )
        assertEquals(config, BackendKinds.configOf(kind, settings))
        assertEquals(SecretPlace.KEYRING, config.secretPlace)
        assertEquals(setOf(SecretKind.APP_PASSWORD), config.secrets)
        assertEquals("https://dav.example.org/jotter/", config.location)

        val inFile = config.copy(secretPlace = SecretPlace.FILE)
        val fileSettings = BackendKinds.settingsOf(inFile)
        assertTrue(fileSettings.contains(""""secrets":"file""""), fileSettings)
        assertEquals(inFile, BackendKinds.configOf(kind, fileSettings))
        assertEquals(
            UnknownConfig("webdav"),
            BackendKinds.configOf(kind, """{"url":"u","username":"n","secrets":"cloud"}"""),
        )
        assertEquals(SecretPlace.KEYRING, LocalFolderConfig("/x").secretPlace)
    }

    @Test
    fun theFactoryOpensAWebDavServerWithItsStoredPassword() =
        runTest {
            val server = backend(WebDavConfig("http://127.0.0.1:9/jotter/", "me"))
            assertFailsWith<MissingSecretException> { factory.open(server) }
            keyring.write(SecretKey("x", SecretKind.APP_PASSWORD), Secret("app-password"))
            val store = factory.open(server)
            assertIs<AutoCloseable>(store).close()
            val plain = backend(WebDavConfig("http://dav.example.org/", "me"))
            keyring.write(SecretKey("x", SecretKind.APP_PASSWORD), Secret("app-password"))
            assertFailsWith<IllegalArgumentException> { factory.open(plain) }
        }

    @Test
    fun aSecretIsHiddenFromLogs() {
        val secret = Secret("hunter2")
        assertEquals("Secret(hidden)", secret.toString())
        assertTrue("hunter2" !in "failed with $secret")
        assertEquals("hunter2", secret.reveal())
        assertEquals(Secret("hunter2"), secret)
        assertEquals(Secret("hunter2").hashCode(), secret.hashCode())
        assertTrue(secret != Secret("other") && secret.equals("hunter2").not())
    }

    @Test
    fun theAppWithoutSecretsStoresNone() =
        runTest {
            val key = SecretKey("b1", SecretKind.APP_PASSWORD)
            assertNull(NoSecretStore.read(key))
            assertFailsWith<SecretStoreException> { NoSecretStore.write(key, Secret("x")) }
            NoSecretStore.delete(key)
            val memory = InMemorySecretStore()
            memory.write(key, Secret("x"))
            assertEquals(Secret("x"), memory.read(key))
            memory.delete(key)
            assertNull(memory.read(key))
        }

    private fun backend(config: BackendConfig) =
        Backend("x", "x", config, Instant.fromEpochMilliseconds(0), null, null)
}
