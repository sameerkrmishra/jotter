package io.github.sameerkrmishra.jotter.app.sync

/** A kind of secret that a backend may need (ADR-0016). */
public enum class SecretKind {
    /** The app password of a WebDAV account, made for Jotter alone so that it can be revoked. */
    APP_PASSWORD,

    /** The secret a device and the PC hub agreed on when they were paired. */
    PAIRING_SECRET,
}

/**
 * Names one secret: the [kind] that the backend [backendId] needs.
 *
 * @property backendId The backend's id, as in the `backend` table.
 * @property kind Which of its secrets.
 */
public data class SecretKey(
    val backendId: String,
    val kind: SecretKind,
)

/**
 * A password or similar. Its [toString] hides it, so that a log line or an exception message that
 * mentions it by mistake does not give it away. Only [reveal] gives the text.
 */
public class Secret(
    private val value: String,
) {
    /** The secret's text, for the one call that needs it, such as an HTTP header. */
    public fun reveal(): String = value

    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "Secret(hidden)"
}

/**
 * Where the secrets of backends live, outside the notes database and never in a log
 * (ADR-0016). Implementations block on I/O only inside these suspending calls.
 */
public interface SecretStore {
    /**
     * Returns the secret [key], or null if there is none.
     *
     * @throws SecretStoreException if the store cannot be used, such as a locked keyring.
     */
    public suspend fun read(key: SecretKey): Secret?

    /**
     * Stores [secret] as [key], replacing any secret it had.
     *
     * @throws SecretStoreException if the store cannot be used.
     */
    public suspend fun write(
        key: SecretKey,
        secret: Secret,
    )

    /**
     * Deletes the secret [key]. Deleting one that is not there is no error.
     *
     * @throws SecretStoreException if the store cannot be used.
     */
    public suspend fun delete(key: SecretKey)
}

/** A [SecretStore] cannot be used. The message says why, without the secret. */
public open class SecretStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * No keyring answers at all: no session bus, no Secret Service, or no `secret-tool`. The user may
 * then choose to keep the secret in a file instead (ADR-0016), which the app offers.
 */
public class SecretStoreUnavailableException(
    message: String,
) : SecretStoreException(message)

/** Where the secrets of a backend are kept (ADR-0016). It is a setting, not a secret. */
public enum class SecretPlace {
    /** The desktop's keyring, through the Secret Service. The default. */
    KEYRING,

    /** A file readable by this user alone, in plain text, which the user chose. */
    FILE,
}

/** What kind of device keeps the secrets, for the words that say where they are. */
public enum class SecretHome {
    /** A Linux desktop: the keyring, through the Secret Service, or a file the user chose. */
    LINUX,

    /**
     * Android: a file in the app's own storage, sealed with a key in the Android Keystore. There
     * is no keyring and no plain-text choice (ADR-0018).
     */
    ANDROID,
}

/**
 * The places secrets can be kept in: the [keyring] by default, and the [file] that the user may
 * choose instead (ADR-0016).
 *
 * @property fileLocation Where [file] keeps its secrets, for people, such as
 *   `~/.config/jotter/secrets.json`, or null if there is no such file.
 * @property home The kind of device, for the words that say where secrets are.
 */
public class SecretStores(
    public val keyring: SecretStore,
    public val file: SecretStore,
    public val fileLocation: String? = null,
    public val home: SecretHome = SecretHome.LINUX,
) {
    /** The store of [place]. */
    public fun of(place: SecretPlace): SecretStore =
        when (place) {
            SecretPlace.KEYRING -> keyring
            SecretPlace.FILE -> file
        }

    /** The stores that hold nothing. */
    public companion object {
        /** For apps and tests whose backends need no secrets: both places are [NoSecretStore]. */
        public val NONE: SecretStores = SecretStores(NoSecretStore, NoSecretStore)
    }
}

/**
 * The secret store of an app whose backends need no secrets: today's, as local folders need none.
 * It holds nothing, and refuses to store anything. ADR-0016 says what replaces it.
 */
public object NoSecretStore : SecretStore {
    override suspend fun read(key: SecretKey): Secret? = null

    override suspend fun write(
        key: SecretKey,
        secret: Secret,
    ): Unit = throw SecretStoreException("This version of Jotter has nowhere to keep secrets")

    override suspend fun delete(key: SecretKey) {
        // Nothing is ever stored.
    }
}
