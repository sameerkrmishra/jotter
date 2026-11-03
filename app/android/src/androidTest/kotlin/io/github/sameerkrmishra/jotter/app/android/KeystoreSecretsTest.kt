package io.github.sameerkrmishra.jotter.app.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sameerkrmishra.jotter.app.sync.Secret
import io.github.sameerkrmishra.jotter.app.sync.SecretKey
import io.github.sameerkrmishra.jotter.app.sync.SecretKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sealed secrets with a real key in the Android Keystore. */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
@RunWith(AndroidJUnit4::class)
class KeystoreSecretsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val alias = "test-${UUID.randomUUID()}"
    private val dir = File(context.cacheDir, alias)
    private val file = File(dir, "secrets.json")
    private val key = SecretKey("backend", SecretKind.APP_PASSWORD)
    private val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun store() =
        SealedFileSecretStore(file, AesGcmCipher { keystoreKey(alias) }, Dispatchers.IO)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
        keystore.deleteEntry(alias)
    }

    @Test
    fun aSecretIsSealedWithAKeystoreKeyAndReadBack() =
        runBlocking {
            store().write(key, Secret("hunter2"))
            assertTrue(keystore.containsAlias(alias))
            assertFalse(file.readText().contains("hunter2"))
            // Another instance, as after a restart, opens it with the same key.
            assertEquals(Secret("hunter2"), store().read(key))
        }

    @Test
    fun theKeyIsMadeOnceAndKeptInTheKeystore() {
        val first = keystoreKey(alias)
        // The Keystore never hands out a key's bytes.
        assertNull(first.encoded)
        val context = "c".encodeToByteArray()
        val sealed = AesGcmCipher { first }.seal("text".encodeToByteArray(), context)
        // Asked again, it is the same key, not a new one.
        val opened = AesGcmCipher { keystoreKey(alias) }.open(sealed, context)
        assertEquals("text", opened.decodeToString())
    }

    @Test
    fun aLostKeyMeansTheSecretMustBeEnteredAgain() =
        runBlocking {
            store().write(key, Secret("hunter2"))
            keystore.deleteEntry(alias)
            assertNull(store().read(key))
            store().write(key, Secret("again"))
            assertEquals(Secret("again"), store().read(key))
        }
}
