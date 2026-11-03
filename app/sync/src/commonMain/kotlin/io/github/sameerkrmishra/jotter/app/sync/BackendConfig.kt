package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.app.sync.dto.LocalFolderSettings
import io.github.sameerkrmishra.jotter.app.sync.dto.PeerSettings
import io.github.sameerkrmishra.jotter.app.sync.dto.WebDavSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Where a backend is and how to reach it, apart from its secrets. Each kind of backend has its own
 * class. It is stored as the `kind` and `settings` of the `backend` table, so it must never hold a
 * secret: those are in a [SecretStore] (ADR-0016).
 *
 * To add a kind of backend: a class here with its settings in the `dto` package, a branch in
 * [BackendKinds] to store it, and one in [StandardBackendFactory] to open it.
 */
public sealed interface BackendConfig {
    /** Where the backend is, for people: a folder or a URL. */
    public val location: String

    /** The secrets it needs to be opened. [BackendManager.remove] deletes them with it. */
    public val secrets: Set<SecretKind>

    /** Where its [secrets] are kept. */
    public val secretPlace: SecretPlace get() = SecretPlace.KEYRING
}

/**
 * A folder on this computer, such as a USB stick, or one that another tool replicates
 * (`backend:localfolder`, ADR-0012).
 *
 * @property path The folder's absolute path, normalised.
 */
public data class LocalFolderConfig(
    val path: String,
) : BackendConfig {
    override val location: String get() = path

    override val secrets: Set<SecretKind> get() = emptySet()
}

/**
 * A collection on a WebDAV server, such as mailbox.org's (`backend:webdav`, ADR-0017). Its app
 * password is in a [SecretStore], never here.
 *
 * @property url The collection's URL: `https`, or `http` to this machine only, ending in `/`.
 * @property username The account's user name.
 * @property secretPlace Where its app password is kept.
 */
public data class WebDavConfig(
    val url: String,
    val username: String,
    override val secretPlace: SecretPlace = SecretPlace.KEYRING,
) : BackendConfig {
    override val location: String get() = url

    override val secrets: Set<SecretKind> get() = setOf(SecretKind.APP_PASSWORD)
}

/**
 * The hub on the user's PC (`backend:peer`, ADR-0015), reached over Tailscale, or on this machine.
 * Its pairing token is in a [SecretStore], never here.
 *
 * @property host The PC's Tailscale address or MagicDNS name, or a loopback address.
 * @property port The hub's TCP port.
 * @property secretPlace Where its pairing token is kept.
 */
public data class PeerConfig(
    val host: String,
    val port: Int,
    override val secretPlace: SecretPlace = SecretPlace.KEYRING,
) : BackendConfig {
    override val location: String get() = "the PC hub at $host:$port"

    override val secrets: Set<SecretKind> get() = setOf(SecretKind.PAIRING_SECRET)
}

/**
 * A backend this version cannot read the settings of: it was added by a newer version of Jotter,
 * or its settings are damaged. It is listed, so that the user can see and remove it, but it never
 * syncs.
 *
 * @property kind What the database calls its kind.
 */
public data class UnknownConfig(
    val kind: String,
) : BackendConfig {
    override val location: String get() = "an unknown kind of backend ($kind)"

    override val secrets: Set<SecretKind> get() = emptySet()
}

/** How a [BackendConfig] is stored: its kind and its settings as JSON. */
internal object BackendKinds {
    const val LOCAL_FOLDER = "local-folder"
    const val WEBDAV = "webdav"
    const val PEER = "peer"

    private val json = Json { ignoreUnknownKeys = true }

    /** How a [SecretPlace] is stored. */
    private val places = mapOf("keyring" to SecretPlace.KEYRING, "file" to SecretPlace.FILE)

    /** The kind under which [config] is stored. */
    fun kindOf(config: BackendConfig): String =
        when (config) {
            is LocalFolderConfig -> LOCAL_FOLDER
            is WebDavConfig -> WEBDAV
            is PeerConfig -> PEER
            is UnknownConfig -> config.kind
        }

    /** The settings of [config], as stored. */
    fun settingsOf(config: BackendConfig): String =
        when (config) {
            is LocalFolderConfig ->
                json.encodeToString(
                    LocalFolderSettings.serializer(),
                    LocalFolderSettings(config.path),
                )
            is WebDavConfig ->
                json.encodeToString(
                    WebDavSettings.serializer(),
                    WebDavSettings(
                        config.url,
                        config.username,
                        placeName(config.secretPlace),
                    ),
                )
            is PeerConfig ->
                json.encodeToString(
                    PeerSettings.serializer(),
                    PeerSettings(config.host, config.port, placeName(config.secretPlace)),
                )
            is UnknownConfig -> throw IllegalArgumentException("Cannot store ${config.location}")
        }

    /** The config stored as [kind] and [settings], or [UnknownConfig] if this version cannot. */
    fun configOf(
        kind: String,
        settings: String,
    ): BackendConfig =
        try {
            when (kind) {
                LOCAL_FOLDER ->
                    LocalFolderConfig(
                        json.decodeFromString(LocalFolderSettings.serializer(), settings).path,
                    )
                WEBDAV -> webDavOf(settings)
                PEER -> peerOf(settings)
                else -> UnknownConfig(kind)
            }
        } catch (_: SerializationException) {
            UnknownConfig(kind)
        } catch (_: IllegalArgumentException) {
            UnknownConfig(kind)
        }

    private fun placeName(place: SecretPlace) = places.entries.single { it.value == place }.key

    private fun placeOf(name: String) = requireNotNull(places[name]) { "No such place: $name" }

    private fun peerOf(settings: String): PeerConfig {
        val stored = json.decodeFromString(PeerSettings.serializer(), settings)
        return PeerConfig(stored.host, stored.port, placeOf(stored.secrets))
    }

    private fun webDavOf(settings: String): WebDavConfig {
        val stored = json.decodeFromString(WebDavSettings.serializer(), settings)
        val place = requireNotNull(places[stored.secrets]) { "No such place: ${stored.secrets}" }
        return WebDavConfig(stored.url, stored.username, place)
    }
}
