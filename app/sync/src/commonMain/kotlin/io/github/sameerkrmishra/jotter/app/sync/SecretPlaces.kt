package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.core.logging.Logger
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

// Where a backend's secrets are, in plain words, and moving them from one place to the other
// (ADR-0016). For BackendManager, which calls these under its lock.

private val log = Logger("backends")

/** Where a secret now is, in plain words, after "Stored the new password of NAME in". */
public fun describe(place: SecretPlace): String =
    when (place) {
        SecretPlace.KEYRING -> "the keyring"
        SecretPlace.FILE -> "the secrets file, in plain text"
    }

/** See [BackendManager.secretsLeftBehind]. */
internal fun leftBehindWords(
    backend: Backend,
    secrets: SecretStores,
): String? = if (backend.config.secrets.isEmpty()) null else leftBehindAt(backend, secrets)

/** Where the secrets of [backend], which has some, may still be, and how to delete them. */
private fun leftBehindAt(
    backend: Backend,
    secrets: SecretStores,
): String {
    val what = if (backend.config is PeerConfig) "pairing token" else "password"
    val file = secrets.fileLocation
    return if (secrets.home == SecretHome.ANDROID) {
        "Its $what may still be in Jotter's own storage on this phone, sealed with a key in " +
            "the Android Keystore, under ${backend.id}. No other app can read it, and " +
            "clearing Jotter's storage or uninstalling Jotter deletes it."
    } else if (backend.config.secretPlace == SecretPlace.KEYRING) {
        "Its $what may still be in your keyring, as the item \"Jotter: ... of backend " +
            "${backend.id}\". Delete it later in your keyring program, such as Seahorse or " +
            "KWalletManager, or with: secret-tool clear application jotter backend ${backend.id}"
    } else if (file != null) {
        "Its $what may still be in $file, under ${backend.id}. Delete that entry, or " +
            "the whole file if no other backend uses it."
    } else {
        "Its $what may still be in a secrets file, under ${backend.id}, though Jotter " +
            "cannot tell where on this device, as no home folder is set. Look for " +
            "jotter/secrets.json in your config folder, such as ~/.config, and delete " +
            "that entry."
    }
}

/** See [BackendManager.whereSecretsAre]. */
internal fun secretsWords(
    backend: Backend,
    secrets: SecretStores,
): String? {
    val what = if (backend.config is PeerConfig) "pairing token" else "password"
    return when {
        backend.config.secrets.isEmpty() -> null
        secrets.home == SecretHome.ANDROID ->
            "Its $what is kept on this phone only, in Jotter's own storage, sealed with a " +
                "key in the Android Keystore. No backup copies it."
        backend.config.secretPlace == SecretPlace.KEYRING ->
            "Its $what is kept in the keyring."
        secrets.fileLocation == null ->
            "Its $what is kept in a plain-text secrets file, as you chose, but Jotter " +
                "cannot tell where on this device, as no home folder is set."
        else ->
            "Its $what is kept in plain text in ${secrets.fileLocation}, as you chose."
    }
}

/**
 * Records in [store] that the secret [key] of [backend], already written to [to], is kept there,
 * as [placed] says, and then deletes the old copy. If the record cannot be changed, the new copy
 * is deleted again, also when cancelled, as the record still names the old place; if that delete
 * fails too, the result says where the new copy may still be.
 */
@Suppress("LongParameterList") // What BackendManager holds, passed down.
internal suspend fun moveSecret(
    store: NoteStore,
    secrets: SecretStores,
    io: CoroutineContext,
    backend: Backend,
    placed: Backend,
    key: SecretKey,
): PasswordChange {
    val old = secrets.of(backend.config.secretPlace)
    val new = secrets.of(placed.config.secretPlace)
    val failure =
        try {
            val settings = BackendKinds.settingsOf(placed.config)
            val recorded = withContext(io) { store.backends.updateSettings(backend.id, settings) }
            // Not recorded: another process removed the backend meanwhile.
            if (recorded) null else "${backend.id} was removed meanwhile."
        } catch (e: CancellationException) {
            rollBack(new, old, key, placed, secrets)
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn(e) { "Where the secret of backend ${backend.id} is cannot be recorded" }
            "Cannot record where the secret is kept: ${e.message}. It is still kept where it was."
        }
    if (failure != null) {
        val left = rollBack(new, old, key, placed, secrets)
        return PasswordChange.Refused(listOfNotNull(failure, left).joinToString(" "))
    }
    log.info { "Moved the secret of backend ${backend.id} to the ${placed.config.secretPlace}" }
    val left =
        deleteFrom(old, new, key, backend, secrets)?.let {
            "Jotter could not delete the old copy, if there was one: $it"
        }
    return PasswordChange.Moved(placed.config.secretPlace, left)
}

/**
 * Deletes the new copy of a move that was not recorded, even when cancelled, or says where it may
 * still be, as [placed] would keep it. Where both places are one store, as on Android, that store
 * holds the one secret, which the old record names too, so it stays.
 */
private suspend fun rollBack(
    new: SecretStore,
    old: SecretStore,
    key: SecretKey,
    placed: Backend,
    secrets: SecretStores,
): String? =
    withContext(NonCancellable) {
        deleteFrom(new, old, key, placed, secrets)?.let {
            "Jotter could not delete the new copy either: $it"
        }
    }

/**
 * Deletes the copy of [key] in [store], unless [other] is the same store, and says where it may
 * still be if that fails: where [backend] keeps it.
 */
private suspend fun deleteFrom(
    store: SecretStore,
    other: SecretStore,
    key: SecretKey,
    backend: Backend,
    secrets: SecretStores,
): String? {
    if (store === other) return null
    return try {
        store.delete(key)
        null
    } catch (e: SecretStoreException) {
        log.warn(e) { "A copy of the secret of backend ${backend.id} cannot be deleted" }
        "${e.message} ${leftBehindAt(backend, secrets)}"
    }
}
