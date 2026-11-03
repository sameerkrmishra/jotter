package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.app.sync.dto.LocalFolderSettings
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

    private val json = Json { ignoreUnknownKeys = true }

    /** The kind under which [config] is stored. */
    fun kindOf(config: BackendConfig): String =
        when (config) {
            is LocalFolderConfig -> LOCAL_FOLDER
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
                else -> UnknownConfig(kind)
            }
        } catch (_: SerializationException) {
            UnknownConfig(kind)
        } catch (_: IllegalArgumentException) {
            UnknownConfig(kind)
        }
}
