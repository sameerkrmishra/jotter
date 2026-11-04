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

/** See [BackendManager.secretsLeftBehind]. */
internal fun leftBehindWords(
    backend: Backend,
    secrets: SecretStores,
): String? {
    if (backend.config.secrets.isEmpty()) return null
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
 * and then deletes the old copy. If the record cannot be changed, the new copy is deleted again,
 * also when cancelled, as the record still names the old place; if that delete fails too, the
 * result says where the new copy may still be.
 */
@Suppress("LongParameterList") // What BackendManager holds, passed down.
internal suspend fun moveSecret(
    store: NoteStore,
    secrets: SecretStores,
    io: CoroutineContext,
    backend: Backend,
    key: SecretKey,
    to: SecretPlace,
): PasswordChange {
    val config = backend.config
    val placed =
        backend.copy(
            config =
                when (config) {
                    is WebDavConfig -> config.copy(secretPlace = to)
                    is PeerConfig -> config.copy(secretPlace = to)
                    else -> error("${backend.id} needs no secret")
                },
        )
    val old = secrets.of(config.secretPlace)
    val new = secrets.of(to)
    // On Android both places are the one sealed store, which holds the new secret now.
    val separate = new !== old
    val failure =
        try {
            val settings = BackendKinds.settingsOf(placed.config)
            val recorded = withContext(io) { store.backends.updateSettings(backend.id, settings) }
            // Not recorded: another process removed the backend meanwhile.
            if (recorded) null else "${backend.id} was removed meanwhile."
        } catch (e: CancellationException) {
            if (separate) rollBack(new, key, placed, secrets)
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn(e) { "Where the secret of backend ${backend.id} is cannot be recorded" }
            "Cannot record where the secret is kept: ${e.message ?: e::class.simpleName}. " +
                "It is still kept where it was."
        }
    if (failure != null) {
        val left = if (separate) rollBack(new, key, placed, secrets) else null
        return PasswordChange.Refused(listOfNotNull(failure, left).joinToString(" "))
    }
    log.info { "Moved the secret of backend ${backend.id} to the ${to.name}" }
    return PasswordChange.Moved(to, if (separate) deleteOld(old, key, backend, secrets) else null)
}

/**
 * Deletes the new copy of a move that was not recorded, even when cancelled, or says where it may
 * still be, as [placed] would keep it.
 */
private suspend fun rollBack(
    new: SecretStore,
    key: SecretKey,
    placed: Backend,
    secrets: SecretStores,
): String? =
    withContext(NonCancellable) {
        try {
            new.delete(key)
            null
        } catch (e: SecretStoreException) {
            log.warn(e) { "The new secret of backend ${placed.id} cannot be deleted" }
            "Jotter could not delete the new copy either: ${e.message} " +
                leftBehindWords(placed, secrets).orEmpty()
        }
    }

/** Deletes the old copy, or says where it may still be. */
private suspend fun deleteOld(
    old: SecretStore,
    key: SecretKey,
    backend: Backend,
    secrets: SecretStores,
): String? =
    try {
        old.delete(key)
        null
    } catch (e: SecretStoreException) {
        log.warn(e) { "The old secret of backend ${backend.id} cannot be deleted" }
        "Jotter could not delete the old copy, if there was one: ${e.message} " +
            leftBehindWords(backend, secrets).orEmpty()
    }
