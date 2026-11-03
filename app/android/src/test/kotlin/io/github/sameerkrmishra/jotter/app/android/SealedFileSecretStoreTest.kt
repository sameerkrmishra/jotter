package io.github.sameerkrmishra.jotter.app.android

import io.github.sameerkrmishra.jotter.app.sync.Secret
import io.github.sameerkrmishra.jotter.app.sync.SecretKey
import io.github.sameerkrmishra.jotter.app.sync.SecretKind
import io.github.sameerkrmishra.jotter.app.sync.SecretStoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sealed secrets file on the JVM, with a software AES key in place of the Android Keystore's,
 * which the device tests use (KeystoreSecretsTest).
 */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class SealedFileSecretStoreTest {
    private val dir: File = Files.createTempDirectory("jotter-secrets").toFile()
    private val file = File(dir, "secrets/backend-secrets.json")
    private var key = newKey()
    private val cipher = AesGcmCipher { key }
    private val store = SealedFileSecretStore(file, cipher, Dispatchers.IO)
    private val dav = SecretKey("b1", SecretKind.APP_PASSWORD)
    private val hub = SecretKey("b2", SecretKind.PAIRING_SECRET)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun aSecretIsStoredSealedAndReadBack() =
        runTest {
            assertNull(store.read(dav))
            store.write(dav, Secret("hunter2"))
            store.write(hub, Secret("tok.en"))
            assertEquals(Secret("hunter2"), store.read(dav))
            assertEquals(Secret("tok.en"), store.read(hub))
            val text = file.readText()
            assertFalse(text.contains("hunter2"), text)
            assertFalse(text.contains("tok.en"), text)
            assertTrue(text.contains("b1/APP_PASSWORD"), text)
            // No temporary file is left behind.
            assertEquals(listOf(file.name), checkNotNull(file.parentFile).list()?.toList())
        }

    @Test
    fun writingReplacesAndDeletingRemoves() =
        runTest {
            store.write(dav, Secret("old"))
            store.write(dav, Secret("new"))
            assertEquals(Secret("new"), store.read(dav))
            store.delete(dav)
            assertNull(store.read(dav))
            // Deleting what is not there is no error, and writes nothing.
            val before = file.lastModified()
            store.delete(hub)
            assertEquals(before, file.lastModified())
            SealedFileSecretStore(File(dir, "none.json"), cipher, Dispatchers.IO).delete(dav)
            assertFalse(File(dir, "none.json").exists())
        }

    @Test
    fun anotherKeyReadsAsNoSecretAndANewOneReplacesIt() =
        runTest {
            store.write(dav, Secret("hunter2"))
            // As after the Keystore lost its key: the user enters the password again.
            key = newKey()
            assertNull(store.read(dav))
            store.write(dav, Secret("again"))
            assertEquals(Secret("again"), store.read(dav))
        }

    @Test
    fun aSealedSecretDoesNotOpenUnderAnotherName() =
        runTest {
            store.write(dav, Secret("hunter2"))
            val moved = file.readText().replace("b1/APP_PASSWORD", "b9/APP_PASSWORD")
            file.writeText(moved)
            assertNull(store.read(SecretKey("b9", SecretKind.APP_PASSWORD)))
        }

    @Test
    fun aDamagedEntryReadsAsNoSecret() =
        runTest {
            checkNotNull(file.parentFile).mkdirs()
            file.writeText(
                """{"format":1,"secrets":{"b1/APP_PASSWORD":"%%%","b2/PAIRING_SECRET":"AAAA"}}""",
            )
            assertNull(store.read(dav))
            assertNull(store.read(hub))
        }

    @Test
    fun aDamagedFileIsRefusedAndLeftAsItIs() =
        runTest {
            checkNotNull(file.parentFile).mkdirs()
            val damaged =
                listOf(
                    "not json",
                    "[]",
                    """{"secrets":{}}""",
                    """{"format":2,"secrets":{}}""",
                    """{"format":"x","secrets":{}}""",
                    """{"format":1}""",
                    """{"format":1,"secrets":[]}""",
                    """{"format":1,"secrets":{"b1/APP_PASSWORD":{}}}""",
                )
            damaged.forEach { text ->
                file.writeText(text)
                val read = assertFailsWith<SecretStoreException>(text) { store.read(dav) }
                assertTrue(read.message.orEmpty().contains("damaged"), text)
                assertFailsWith<SecretStoreException>(text) { store.write(dav, Secret("p")) }
                assertEquals(text, file.readText())
            }
        }

    @Test
    fun aFileThatCannotBeWrittenFailsWithoutTheSecret() =
        runTest {
            // A directory where the file belongs: it cannot be replaced.
            file.mkdirs()
            val failure =
                assertFailsWith<SecretStoreException> { store.write(dav, Secret("hunter2")) }
            assertFalse(failure.message.orEmpty().contains("hunter2"))
            assertTrue(failure.message.orEmpty().contains("secrets file"), failure.message)
        }

    @Test
    fun aKeyThatCannotBeUsedFailsPlainly() =
        runTest {
            val broken =
                SealedFileSecretStore(
                    file,
                    object : SecretCipher {
                        override fun seal(
                            plain: ByteArray,
                            context: ByteArray,
                        ): ByteArray = throw GeneralSecurityException("keystore says no")

                        override fun open(
                            sealed: ByteArray,
                            context: ByteArray,
                        ): ByteArray = throw ProviderException("keystore crashed")
                    },
                    Dispatchers.IO,
                )
            val write = assertFailsWith<SecretStoreException> { broken.write(dav, Secret("p")) }
            assertTrue(write.message.orEmpty().contains("Keystore"))
            store.write(dav, Secret("p"))
            val read = assertFailsWith<SecretStoreException> { broken.read(dav) }
            assertTrue(read.message.orEmpty().contains("Keystore"))
        }

    @Test
    fun concurrentWritesAllLand() =
        runTest {
            (1..20)
                .map { n ->
                    async(Dispatchers.IO) {
                        store.write(SecretKey("b$n", SecretKind.APP_PASSWORD), Secret("p$n"))
                    }
                }.awaitAll()
            (1..20).forEach { n ->
                assertEquals(
                    Secret("p$n"),
                    store.read(SecretKey("b$n", SecretKind.APP_PASSWORD)),
                )
            }
        }

    @Test
    fun theCipherUsesAFreshIvEachTimeAndChecksItsInput() {
        val context = "c".encodeToByteArray()
        val one = cipher.seal("same".encodeToByteArray(), context)
        val two = cipher.seal("same".encodeToByteArray(), context)
        assertFalse(one.contentEquals(two))
        assertContentEquals("same".encodeToByteArray(), cipher.open(two, context))
        assertFailsWith<UnreadableSecretException> { cipher.open(one, "d".encodeToByteArray()) }
        assertFailsWith<UnreadableSecretException> { cipher.open(ByteArray(10), context) }
        val flipped = one.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<UnreadableSecretException> { cipher.open(flipped, context) }
    }
}
