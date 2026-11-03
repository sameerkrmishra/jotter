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
public class SecretStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

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
