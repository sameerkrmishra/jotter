package io.github.sameerkrmishra.jotter.app.sync

import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What `DBUS_SESSION_BUS_ADDRESS` is set to when there is none, so that nothing autolaunches. */
internal const val NO_BUS: String = "disabled:"

/** The most of `secret-tool`'s stderr that a message quotes. */
private const val MAX_QUOTED = 200

/**
 * Secrets in the desktop's keyring, through the freedesktop Secret Service that GNOME Keyring,
 * KWallet and KeePassXC provide, by running libsecret's `secret-tool` (ADR-0016).
 *
 * The secret goes to `secret-tool` on its standard input and comes back on its standard output,
 * never in an argument or the environment. Each is found by the attributes `application=jotter`,
 * `backend=<id>` and `kind=<kind>`.
 *
 * It fails closed: `secret-tool` runs with `DBUS_SESSION_BUS_ADDRESS` set to [busAddress], or to
 * `disabled:` if there is none, so that it never starts a bus or a keyring of its own, whose
 * secrets would not be in the user's keyring and would be gone afterwards. Without a bus, a
 * provider or `secret-tool` itself, every call throws [SecretStoreUnavailableException].
 *
 * @param busAddress The session bus, from `DBUS_SESSION_BUS_ADDRESS`, or null if there is none.
 * @param io Where the process is waited for.
 * @param command How `secret-tool` is started. Tests replace it.
 * @param timeout How long a call may take: a locked keyring waits for the user to unlock it.
 */
public class SecretServiceStore(
    private val busAddress: String?,
    private val io: CoroutineContext,
    private val command: List<String> = listOf("secret-tool"),
    private val timeout: Duration = 2.minutes,
) : SecretStore {
    override suspend fun read(key: SecretKey): Secret? {
        val result = run(listOf("lookup") + attributes(key), input = null)
        return when {
            result.exit == 0 -> Secret(result.output.decodeToString())
            // Exit 1 without a word: no such item, or, as far as secret-tool says, an unlock prompt
            // the user dismissed. Callers word null as "locked or missing" and never overwrite,
            // delete or forget a backend because of it.
            result.isNothingFound -> null
            else -> throw failure("read", result)
        }
    }

    override suspend fun write(
        key: SecretKey,
        secret: Secret,
    ) {
        val label = "--label=${label(key)}"
        val result =
            run(listOf("store", label) + attributes(key), secret.reveal().encodeToByteArray())
        if (result.exit != 0) throw failure("store", result)
    }

    override suspend fun delete(key: SecretKey) {
        val result = run(listOf("clear") + attributes(key), input = null)
        // Only exit 0 means deleted (ADR-0016). `clear` deletes only unlocked items, and ends with
        // 1 without a word when it deleted none: no such item, a locked one, or a dismissed unlock
        // prompt. No other call tells these apart for every provider: libsecret's search lists
        // locked items, but KeePassXC, its database locked, answers a search with no items and no
        // error. So the caller hears that the item may be locked, or already gone, and the user
        // decides, by unlocking and trying again or by removing the backend anyway.
        when {
            result.exit == 0 -> Unit
            result.isNothingFound -> throw SecretStoreException(mayBeLocked(key))
            else -> throw failure("delete", result)
        }
    }

    private fun attributes(key: SecretKey) =
        listOf("application", "jotter", "backend", key.backendId, "kind", key.kind.name)

    private suspend fun run(
        arguments: List<String>,
        input: ByteArray?,
    ): Result =
        runInterruptible(io) {
            val builder = ProcessBuilder(command + arguments)
            // Replaces whatever this process inherited, so that only this bus is ever asked.
            builder.environment()[BUS_VARIABLE] = busAddress ?: NO_BUS
            val process =
                try {
                    builder.start()
                } catch (_: IOException) {
                    throw SecretStoreUnavailableException(NOT_INSTALLED)
                }
            try {
                val output = drain(process.inputStream)
                val errors = drain(process.errorStream)
                give(process, input)
                if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                    throw SecretStoreException(
                        "The keyring did not answer within ${timeout.inWholeMinutes} minutes. If " +
                            "it asked to be unlocked, unlock it and try again.",
                    )
                }
                Result(process.exitValue(), output.join(), errors.join().decodeToString())
            } finally {
                process.destroyForcibly()
            }
        }

    /** Writes [input] to the process's stdin and closes it. */
    @Suppress("SwallowedException") // A process that ended early says why itself.
    private fun give(
        process: Process,
        input: ByteArray?,
    ) {
        try {
            process.outputStream.use { stdin -> input?.let(stdin::write) }
        } catch (_: IOException) {
            // A broken pipe: its exit status and stderr tell what happened.
        }
    }

    private fun failure(
        action: String,
        result: Result,
    ): SecretStoreException {
        val said =
            result.error
                .trim()
                .removePrefix("secret-tool: ")
                .take(MAX_QUOTED)
        return when {
            busAddress == null -> SecretStoreUnavailableException(NO_SESSION)
            said.contains("org.freedesktop.secrets") || said.contains("ServiceUnknown") ->
                SecretStoreUnavailableException(NO_PROVIDER)
            said.contains("address", ignoreCase = true) ||
                said.contains("connect", ignoreCase = true) ->
                SecretStoreUnavailableException(
                    "Jotter cannot reach a keyring: the session bus does not answer ($said).",
                )
            else -> {
                val reason = said.ifEmpty { "no reason given" }
                SecretStoreException("The keyring could not $action the password: $reason.")
            }
        }
    }

    /** What `secret-tool` printed and how it ended. [output] may be a secret. */
    private class Result(
        val exit: Int,
        val output: ByteArray,
        val error: String,
    ) {
        /** `secret-tool` ends with 1 and says nothing when no item matches. */
        val isNothingFound: Boolean get() = exit == 1 && error.isBlank()
    }

    /** Reads [stream] to its end on a thread of its own, so that neither pipe fills up. */
    private class Drain(
        stream: InputStream,
    ) {
        @Volatile private var bytes = ByteArray(0)
        private val reader =
            thread(isDaemon = true, name = "secret-tool output") {
                bytes =
                    try {
                        stream.readAllBytes()
                    } catch (_: IOException) {
                        ByteArray(0)
                    }
            }

        fun join(): ByteArray {
            reader.join()
            return bytes
        }
    }

    private fun drain(stream: InputStream) = Drain(stream)

    private companion object {
        const val BUS_VARIABLE = "DBUS_SESSION_BUS_ADDRESS"

        const val NOT_INSTALLED =
            "Jotter cannot reach a keyring: secret-tool is not installed. It is in the package " +
                "libsecret (Arch, Fedora) or libsecret-tools (Debian, Ubuntu)."

        const val NO_SESSION =
            "Jotter cannot reach a keyring: there is no desktop session here, as over SSH or in " +
                "a cron job."

        /** What a delete that the keyring did not confirm says, naming the item by its label. */
        fun mayBeLocked(key: SecretKey) =
            "The keyring did not confirm that the item \"${label(key)}\" was deleted: the " +
                "keyring may be locked, or the item already gone. Unlock your keyring and try " +
                "again."

        fun label(key: SecretKey) = "Jotter: ${describe(key.kind)} of backend ${key.backendId}"

        const val NO_PROVIDER =
            "Jotter cannot reach a keyring: no keyring program, such as GNOME Keyring, KWallet " +
                "or KeePassXC, offers the Secret Service in this session."

        fun describe(kind: SecretKind) =
            when (kind) {
                SecretKind.APP_PASSWORD -> "app password"
                SecretKind.PAIRING_SECRET -> "pairing secret"
            }
    }
}
