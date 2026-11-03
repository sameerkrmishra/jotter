package io.github.sameerkrmishra.jotter.app.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_BITS = 256

/** The alias of the key that encrypts backend secrets. */
internal const val SECRETS_KEY_ALIAS = "jotter-backend-secrets"

/**
 * The AES-256 key [alias] in the Android Keystore, made on first use (ADR-0018). Its bytes never
 * leave the Keystore, which may keep it in hardware. It is for GCM only, and the Keystore picks
 * every IV. It needs no unlocked screen or fingerprint, so that sync can run in the background.
 * A key made here is not in any backup, so restored secrets cannot be read; the user enters
 * them again.
 *
 * @throws java.security.GeneralSecurityException if the Keystore fails.
 */
internal fun keystoreKey(alias: String = SECRETS_KEY_ALIAS): SecretKey {
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
        KeyGenParameterSpec
            .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .build(),
    )
    return generator.generateKey()
}
