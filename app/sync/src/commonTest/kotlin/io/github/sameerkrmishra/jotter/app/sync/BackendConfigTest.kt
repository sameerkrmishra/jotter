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
            assertIs<LocalFolderBlobStore>(StandardBackendFactory.open(folder))
            assertFailsWith<IllegalArgumentException> {
                StandardBackendFactory.open(backend(UnknownConfig("peer")))
            }
            val stores = NamedStores(mapOf("x" to InMemoryBlobStore()))
            assertIs<InMemoryBlobStore>(stores.open(backend(LocalFolderConfig("/x"))))
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
