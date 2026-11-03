package io.github.sameerkrmishra.jotter.app.android

import io.github.sameerkrmishra.jotter.app.sync.Secret
import io.github.sameerkrmishra.jotter.app.sync.SecretKey
import io.github.sameerkrmishra.jotter.app.sync.SecretStore
import io.github.sameerkrmishra.jotter.app.sync.SecretStoreException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.CoroutineContext

private const val FORMAT = 1
private const val GCM_TAG_BITS = 128
private const val GCM_IV_BYTES = 12
private const val TRANSFORMATION = "AES/GCM/NoPadding"

/** Encrypts and decrypts secrets with a key that never leaves where it is kept. */
internal interface SecretCipher {
    /**
     * Encrypts [plain], bound to [context]: the result opens only with the same context.
     *
     * @throws GeneralSecurityException if the key cannot be used.
     */
    fun seal(
        plain: ByteArray,
        context: ByteArray,
    ): ByteArray

    /**
     * Decrypts what [seal] made with the same [context].
     *
     * @throws UnreadableSecretException if this key did not make it for this context, or it was
     *   changed since.
     * @throws GeneralSecurityException if the key cannot be used.
     */
    fun open(
        sealed: ByteArray,
        context: ByteArray,
    ): ByteArray
}

/** A sealed secret that this key cannot open: another key made it, or it was changed. */
internal class UnreadableSecretException(
    cause: Throwable,
) : GeneralSecurityException("This secret cannot be decrypted with this device's key", cause)

/**
 * AES-256 in GCM mode, with a fresh random IV for every secret that the cipher itself chooses, as
 * the Android Keystore requires. A sealed secret is the 12-byte IV, then the ciphertext and its
 * 128-bit tag. The context is GCM's associated data.
 *
 * @param key The key, read each time, such as one in the Android Keystore ([keystoreKey]).
 */
internal class AesGcmCipher(
    private val key: () -> javax.crypto.SecretKey,
) : SecretCipher {
    override fun seal(
        plain: ByteArray,
        context: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(context)
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES) { "Unexpected IV of ${iv.size} bytes" }
        return iv + cipher.doFinal(plain)
    }

    override fun open(
        sealed: ByteArray,
        context: ByteArray,
    ): ByteArray {
        if (sealed.size < GCM_IV_BYTES + GCM_TAG_BITS / Byte.SIZE_BITS) {
            throw UnreadableSecretException(IllegalArgumentException("too short"))
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, sealed, 0, GCM_IV_BYTES),
        )
        cipher.updateAAD(context)
        return try {
            cipher.doFinal(sealed, GCM_IV_BYTES, sealed.size - GCM_IV_BYTES)
        } catch (e: AEADBadTagException) {
            throw UnreadableSecretException(e)
        }
    }
}

/**
 * Backend secrets on Android (ADR-0016, ADR-0018): each encrypted with [cipher], whose key is in
 * the Android Keystore, and kept in [file], in the app's own storage outside its backups.
 *
 * The file holds, per secret, its backend's id and kind and the sealed text in Base64, with the
 * name of the secret as the cipher's context, so that a sealed secret cannot be moved to another
 * name. It is replaced as a whole: written to a new file beside it, synced and renamed. A damaged
 * file is never replaced, so that what it holds is not lost by a write; the error says so.
 *
 * A secret that the key cannot open, as after the Keystore lost its key, reads as no secret: the
 * user enters it again, as after restoring the notes elsewhere, and the new one replaces it.
 * Nothing here logs, and no message holds a secret.
 *
 * @param io Where the file is read and written.
 */
internal class SealedFileSecretStore(
    private val file: File,
    private val cipher: SecretCipher,
    private val io: CoroutineContext,
) : SecretStore {
    private val lock = Mutex()

    override suspend fun read(key: SecretKey): Secret? =
        access {
            val sealed = load()[nameOf(key)] ?: return@access null
            val plain =
                try {
                    cipher.open(Base64.getDecoder().decode(sealed), contextOf(key))
                } catch (_: UnreadableSecretException) {
                    return@access null
                } catch (_: IllegalArgumentException) {
                    // Not Base64: damaged like an entry that does not open.
                    return@access null
                }
            Secret(plain.decodeToString())
        }

    override suspend fun write(
        key: SecretKey,
        secret: Secret,
    ) {
        access {
            val sealed = cipher.seal(secret.reveal().encodeToByteArray(), contextOf(key))
            save(load() + (nameOf(key) to Base64.getEncoder().encodeToString(sealed)))
        }
    }

    override suspend fun delete(key: SecretKey) {
        access {
            val all = load()
            if (nameOf(key) in all) save(all - nameOf(key))
        }
    }

    /** Runs [block] alone and on [io], with its failures as [SecretStoreException]. */
    private suspend fun <T> access(block: () -> T): T =
        lock.withLock {
            withContext(io) {
                try {
                    block()
                } catch (e: IOException) {
                    throw SecretStoreException("Cannot use the secrets file: ${e.message}", e)
                } catch (e: GeneralSecurityException) {
                    throw SecretStoreException(KEY_UNUSABLE, e)
                } catch (e: ProviderException) {
                    // The Android Keystore reports some failures of its own as this.
                    throw SecretStoreException(KEY_UNUSABLE, e)
                }
            }
        }

    private fun load(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            require(root.getValue("format").jsonPrimitive.int == FORMAT) { "unknown format" }
            root.getValue("secrets").jsonObject.mapValues { (_, value) ->
                value.jsonPrimitive.content
            }
        } catch (e: IllegalArgumentException) {
            // Not JSON (SerializationException is one of these), not an object or a number where
            // one belongs, or another format.
            throw damaged(e)
        } catch (e: NoSuchElementException) {
            // A field is missing.
            throw damaged(e)
        }
    }

    private fun damaged(cause: Exception) =
        SecretStoreException(
            "This device's secrets file is damaged, so Jotter leaves it as it is. Remove the " +
                "backends that need a password and add them again.",
            cause,
        )

    private fun save(secrets: Map<String, String>) {
        val text =
            JsonObject(
                mapOf(
                    "format" to JsonPrimitive(FORMAT),
                    "secrets" to JsonObject(secrets.mapValues { JsonPrimitive(it.value) }),
                ),
            ).toString()
        val folder = file.toPath().toAbsolutePath().parent
        Files.createDirectories(folder)
        val temporary = File(folder.toFile(), ".${file.name}.${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { out ->
                out.write(text.encodeToByteArray())
                out.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("cannot replace ${file.name}")
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val KEY_UNUSABLE =
            "The Android Keystore could not use Jotter's key. Try again; if it keeps failing, " +
                "remove the backend and add it again."

        fun nameOf(key: SecretKey) = "${key.backendId}/${key.kind.name}"

        /** Binds a sealed secret to its name and to this format. */
        fun contextOf(key: SecretKey) = "jotter-secret:$FORMAT:${nameOf(key)}".encodeToByteArray()
    }
}
