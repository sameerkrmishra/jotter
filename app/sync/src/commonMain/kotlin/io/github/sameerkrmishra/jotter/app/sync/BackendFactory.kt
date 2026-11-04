package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.localfolder.LocalFolderBlobStore
import io.github.sameerkrmishra.jotter.backend.peer.PeerAddress
import io.github.sameerkrmishra.jotter.backend.peer.PeerBlobStore
import io.github.sameerkrmishra.jotter.backend.webdav.WebDavBlobStore
import io.github.sameerkrmishra.jotter.backend.webdav.WebDavCredentials
import okio.Path.Companion.toPath

/** Opens the [BlobStore] that a configured backend describes. */
public fun interface BackendFactory {
    /**
     * Returns a store for [backend]. Opening touches nothing: a store that cannot be reached says
     * so in its `ping`. A store that is [AutoCloseable] is closed by the caller when done.
     *
     * @throws IllegalArgumentException if this version cannot open such a backend.
     * @throws SecretStoreException if a secret it needs cannot be read.
     */
    public suspend fun open(backend: Backend): BlobStore
}

/**
 * The backend's secret is not where its settings say, as after restoring the notes on another
 * computer: the user must enter it again (ADR-0016).
 */
public class MissingSecretException(
    message: String,
) : SecretStoreException(message)

/**
 * The backends of this version: local folders, WebDAV servers and the PC hub. A WebDAV server's
 * app password and a hub's pairing token are read from [secrets] each time it is opened
 * (ADR-0016). A hub that [tailnet] refuses now is unreachable, and its token is not sent.
 */
public class StandardBackendFactory internal constructor(
    private val secrets: SecretStores,
    private val webDav: WebDavOpener,
    private val tailnet: TailnetCheck = TailnetCheck.NONE,
) : BackendFactory {
    public constructor(
        secrets: SecretStores,
        tailnet: TailnetCheck = TailnetCheck.NONE,
    ) : this(secrets, ::openWebDav, tailnet)

    override suspend fun open(backend: Backend): BlobStore =
        when (val config = backend.config) {
            is LocalFolderConfig -> LocalFolderBlobStore(config.path.toPath())
            is WebDavConfig -> {
                val key = SecretKey(backend.id, SecretKind.APP_PASSWORD)
                val password =
                    secrets.of(config.secretPlace).read(key)
                        ?: throw MissingSecretException(noSecret(config, secrets.home))
                webDav.open(config, password)
            }
            is PeerConfig -> {
                val key = SecretKey(backend.id, SecretKind.PAIRING_SECRET)
                val token =
                    secrets.of(config.secretPlace).read(key)
                        ?: throw MissingSecretException(noSecret(config, secrets.home))
                openPeer(config, token, tailnet)
            }
            is UnknownConfig -> throw IllegalArgumentException("Cannot open ${config.location}")
        }
}

/**
 * The hub client for [config] with [token], or a hub that cannot be reached and is sent nothing
 * while [tailnet] refuses its host. It reaches the hub through [tailnet]'s transport, if it has
 * one.
 *
 * @throws IllegalArgumentException if the address or the token cannot be used.
 */
internal fun openPeer(
    config: PeerConfig,
    token: Secret,
    tailnet: TailnetCheck,
): BlobStore {
    tailnet.refuse(config.host)?.let { return OffTailnet(it) }
    val address = PeerAddress(config.host, config.port)
    val transport = tailnet.transport(config.host)
    return when {
        transport == null -> PeerBlobStore(address, token.reveal())
        else ->
            try {
                PeerBlobStore(address, token.reveal(), transport)
            } catch (e: IllegalArgumentException) {
                transport.engine.close()
                throw e
            }
    }
}

/** What a hub backend without its token says. */
internal const val NO_TOKEN: String =
    "Jotter could not read this hub's pairing token: the keyring may be locked, or the token is " +
        "not stored on this device, as after restoring the notes on another computer. Unlock " +
        "your keyring, or pair this device on the PC again and enter the new token."

/** A store that a new backend may need to set up, as `initialize()` does. */
internal interface NewStore :
    BlobStore,
    AutoCloseable {
    suspend fun initialize()
}

/** Opens the WebDAV store of [config] with [password]. Tests replace it. */
internal fun interface WebDavOpener {
    /** @throws IllegalArgumentException if the URL cannot be used, such as plain `http`. */
    fun open(
        config: WebDavConfig,
        password: Secret,
    ): NewStore
}

/** The real WebDAV client. */
internal fun openWebDav(
    config: WebDavConfig,
    password: Secret,
): NewStore {
    val store = WebDavBlobStore(config.url, WebDavCredentials(config.username, password.reveal()))
    return object : NewStore, BlobStore by store {
        override suspend fun initialize() = store.initialize()

        override fun close() = store.close()
    }
}

/** What a WebDAV backend without its password says, for the status screen. */
internal const val NO_PASSWORD: String =
    "Jotter could not read this backend's password: the keyring may be locked, or the password " +
        "is not stored on this device, as after restoring the notes on another computer. Unlock " +
        "your keyring, or enter the password again."

/** [NO_PASSWORD] on Android, which has no keyring. */
internal const val NO_PASSWORD_ANDROID: String =
    "This backend's password is not stored on this phone, as after restoring your notes from a " +
        "backup or another device, which never hold passwords. Enter the password again."

/** [NO_TOKEN] on Android. */
internal const val NO_TOKEN_ANDROID: String =
    "This hub's pairing token is not stored on this phone, as after restoring your notes from a " +
        "backup or another device, which never hold tokens. Pair this phone on the PC again and " +
        "enter the new token."

/** What a backend of [config] says when its secret is not where its settings say, on [home]. */
internal fun noSecret(
    config: BackendConfig,
    home: SecretHome,
): String =
    when {
        config is PeerConfig && home == SecretHome.ANDROID -> NO_TOKEN_ANDROID
        config is PeerConfig -> NO_TOKEN
        home == SecretHome.ANDROID -> NO_PASSWORD_ANDROID
        else -> NO_PASSWORD
    }

/**
 * What a backend of [config] says when its secret could not be read, for [reason], on [home]: the
 * user enters it again, or on a desktop may first unlock the keyring. Never network advice: the
 * backend was not even asked.
 */
internal fun unreadableSecret(
    config: BackendConfig,
    reason: String?,
    home: SecretHome,
): String {
    val peer = config is PeerConfig
    val what = if (peer) "this hub's pairing token" else "this backend's password"
    val why = reason?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
    val again =
        if (peer) {
            "pair this device on the PC again and enter the new token"
        } else {
            "enter the password again"
        }
    return if (home == SecretHome.ANDROID) {
        "Jotter could not read $what on this phone$why. ${again.replaceFirstChar(Char::titlecase)}."
    } else {
        "Jotter could not read $what$why. Unlock your keyring if it is locked, or $again."
    }
}
