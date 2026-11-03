package io.github.sameerkrmishra.jotter.app.sync

import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

/**
 * The secret stores of the Linux apps (ADR-0016): the keyring through the Secret Service on the
 * session bus of `DBUS_SESSION_BUS_ADDRESS`, and the file that the user may choose instead,
 * `jotter/secrets.json` in `$XDG_CONFIG_HOME` or else in `~/.config`. Nothing is touched until a
 * secret is read or written.
 *
 * @param readEnvvar Reads an environment variable.
 * @param userHome The home directory if `HOME` is not set.
 * @param io Where they block.
 */
public fun linuxSecretStores(
    readEnvvar: (String) -> String?,
    userHome: String,
    io: CoroutineContext,
): SecretStores {
    val keyring = SecretServiceStore(readEnvvar("DBUS_SESSION_BUS_ADDRESS")?.ifEmpty { null }, io)
    val directory = configDirectory(readEnvvar, userHome)?.resolve("jotter")
    return if (directory == null) {
        SecretStores(keyring, NoFileStore)
    } else {
        val file = SecretFileStore(directory, io)
        SecretStores(keyring, file, file.file.toString())
    }
}

/**
 * `$XDG_CONFIG_HOME`, or `~/.config`, or null if neither is an absolute path. The XDG Base
 * Directory specification says to ignore an empty or relative `XDG_CONFIG_HOME`.
 */
internal fun configDirectory(
    readEnvvar: (String) -> String?,
    userHome: String,
): Path? {
    readEnvvar("XDG_CONFIG_HOME")?.takeIf { it.startsWith('/') }?.let { return Path.of(it) }
    val home = readEnvvar("HOME")?.takeUnless(String::isEmpty) ?: userHome
    return if (home.startsWith('/')) Path.of(home, ".config") else null
}

/** Where secrets go when there is no home directory: nowhere. */
private object NoFileStore : SecretStore {
    private const val NO_HOME = "There is no home directory to keep a secrets file in."

    override suspend fun read(key: SecretKey): Secret? = null

    override suspend fun write(
        key: SecretKey,
        secret: Secret,
    ): Unit = throw SecretStoreException(NO_HOME)

    override suspend fun delete(key: SecretKey) {
        // Nothing is ever stored.
    }
}
