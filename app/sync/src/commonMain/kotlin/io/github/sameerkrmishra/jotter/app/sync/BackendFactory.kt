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
 * (ADR-0016).
 */
public class StandardBackendFactory internal constructor(
    private val secrets: SecretStores,
    private val webDav: WebDavOpener,
) : BackendFactory {
    public constructor(secrets: SecretStores) : this(secrets, ::openWebDav)

    override suspend fun open(backend: Backend): BlobStore =
        when (val config = backend.config) {
            is LocalFolderConfig -> LocalFolderBlobStore(config.path.toPath())
            is WebDavConfig -> {
                val key = SecretKey(backend.id, SecretKind.APP_PASSWORD)
                val password =
                    secrets.of(config.secretPlace).read(key)
                        ?: throw MissingSecretException(NO_PASSWORD)
                webDav.open(config, password)
            }
            is PeerConfig -> {
                val key = SecretKey(backend.id, SecretKind.PAIRING_SECRET)
                val token =
                    secrets.of(config.secretPlace).read(key)
                        ?: throw MissingSecretException(NO_TOKEN)
                openPeer(config, token)
            }
            is UnknownConfig -> throw IllegalArgumentException("Cannot open ${config.location}")
        }
}

/**
 * The hub client for [config] with [token].
 *
 * @throws IllegalArgumentException if the address or the token cannot be used.
 */
internal fun openPeer(
    config: PeerConfig,
    token: Secret,
): PeerBlobStore = PeerBlobStore(PeerAddress(config.host, config.port), token.reveal())

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
