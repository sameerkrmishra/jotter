package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.app.sync.Backend
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.BackendStatus
import io.github.sameerkrmishra.jotter.app.sync.Outcome
import io.github.sameerkrmishra.jotter.app.sync.PasswordChange
import io.github.sameerkrmishra.jotter.app.sync.PeerConfig
import io.github.sameerkrmishra.jotter.app.sync.SecretPlace
import io.github.sameerkrmishra.jotter.app.sync.SecretStoreException
import io.github.sameerkrmishra.jotter.app.sync.SecretStores
import io.github.sameerkrmishra.jotter.app.sync.StandardBackendFactory
import io.github.sameerkrmishra.jotter.app.sync.WebDavConfig
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.core.sync.DeviceInfo
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Instant

/**
 * What the sync commands share: the database, where secrets are kept, and how a password is read.
 * The root command sets [readEnvvar] before a subcommand runs.
 *
 * @param secrets The secret stores, for the environment given.
 */
internal class SyncSetup(
    val location: DatabaseLocation,
    private val secrets: ((String) -> String?) -> SecretStores,
    val passwords: Passwords,
) {
    /** Reads an environment variable, as Clikt's context does. */
    lateinit var readEnvvar: (String) -> String?

    /**
     * Runs [block] with a [BackendManager] over the database. Everything runs on this thread: the
     * CLI does one thing at a time, and each backend brings its own I/O dispatcher.
     */
    fun <T> withBackends(block: suspend (BackendManager) -> T): T =
        withStore(location.path) { store ->
            val stores = secrets(readEnvvar)
            val engine =
                SyncEngine(store, DeviceInfo(DEVICE_NAME, PLATFORM), EmptyCoroutineContext)
            val manager =
                BackendManager(
                    store,
                    engine,
                    StandardBackendFactory(stores),
                    stores,
                    EmptyCoroutineContext,
                )
            runBlocking { block(manager) }
        }
}

private const val PLATFORM = "linux"
private const val WEBDAV = "webdav"
private const val PEER = "peer"

/** `jotter sync`: one sync with every backend, one line each. */
internal class SyncCommand(
    private val setup: SyncSetup,
) : CoreCliktCommand(name = "sync") {
    override fun help(context: Context): String =
        "Sync with every backend once, printing tab-separated lines: id, name, outcome, detail."

    override fun run() {
        val synced = setup.withBackends { it.syncAll() }
        if (synced.isEmpty()) {
            throw CliktError("jotter: there is no backend: add one with jotter backends add PATH")
        }
        synced.forEach { sync ->
            val attempt = sync.attempt
            echo(
                listOf(
                    sync.backend.id,
                    oneLine(sync.backend.name),
                    outcomeName(attempt.outcome),
                    attempt.detail,
                ).joinToString(SEPARATOR),
            )
            attempt.issues.forEach {
                echo(listOf("issue", sync.backend.id, it).joinToString(SEPARATOR), err = true)
            }
        }
        val failed = synced.count { !it.attempt.outcome.succeeded }
        if (failed > 0) throw CliktError("jotter: $failed of ${synced.size} backends did not sync")
    }
}

/** `jotter backends`: lists the backends, or adds, removes or reports on them. */
internal class BackendsCommand(
    private val setup: SyncSetup,
) : CoreCliktCommand(name = "backends") {
    override val invokeWithoutSubcommand: Boolean = true

    init {
        subcommands(
            AddBackendCommand(setup),
            RemoveBackendCommand(setup),
            BackendStatusCommand(setup),
            PasswordCommand(setup),
        )
    }

    override fun help(context: Context): String =
        "List the backends as tab-separated lines: id, name, location, last success, last outcome."

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        setup.withBackends { it.list() }.forEach { backend ->
            val last = backend.lastAttempt?.outcome?.let(::outcomeName) ?: NEVER
            echo(
                listOf(
                    backend.id,
                    oneLine(backend.name),
                    oneLine(backend.config.location),
                    timeOrNever(backend.lastSuccessAt),
                    last,
                ).joinToString(SEPARATOR),
            )
        }
    }
}

/**
 * `jotter backends add`: adds a folder, a WebDAV server or the PC hub as a backend, and prints its
 * id.
 */
private class AddBackendCommand(
    private val setup: SyncSetup,
) : CoreCliktCommand(name = "add") {
    private val target by argument(
        name = "PATH",
        help =
            "The folder, such as a USB stick. Or webdav, followed by the URL of a collection, " +
                "or peer, followed by the PC hub's address.",
    )
    private val url by argument(
        name = "URL",
        help = "With webdav: the collection's URL. With peer: HOST or HOST:PORT.",
    ).optional()
    private val name by option("--name", help = "A name for it. Default: the folder's name.")
    private val setUp by option(
        "--new",
        help = "Set the folder or collection up as a new Jotter store if it is not one yet.",
    ).flag()
    private val user by option("--user", help = "With webdav: the account's user name.")
    private val passwordInFile by option(
        "--password-in-file",
        help =
            "With webdav or peer: keep the app password or token in plain text in a file " +
                "readable by you alone, \$XDG_CONFIG_HOME/jotter/secrets.json, for where no " +
                "keyring answers.",
    ).flag()

    override fun help(context: Context): String =
        "Add a folder, a WebDAV server (webdav URL --user NAME) or the PC hub (peer HOST[:PORT]) " +
            "as a backend and print its id. The app password or pairing token is read from the " +
            "terminal, or else from the first line of stdin."

    override fun run() {
        val webDavUrl = url?.takeIf { target == WEBDAV }
        val hub = url?.takeIf { target == PEER }
        usageProblem(webDavUrl ?: hub)?.let { throw CliktError("jotter: $it") }
        val result =
            when {
                webDavUrl != null -> addWebDav(webDavUrl)
                hub != null -> addPeer(hub)
                else -> addFolder()
            }
        val failure =
            when (result) {
                is AddResult.Added -> return echo(result.backend.id)
                is AddResult.NotAStoreYet -> notAStoreYet(result.path, webDavUrl != null)
                is AddResult.AlreadyAdded -> {
                    val backend = result.backend
                    "jotter: ${backend.config.location} is already the backend ${backend.id}"
                }
                is AddResult.Refused -> "jotter: ${result.reason}"
                is AddResult.NoKeyring -> noKeyring(result.reason)
            }
        throw CliktError(failure)
    }

    /** What is wrong with the arguments, or null. */
    private fun usageProblem(remote: String?): String? =
        when {
            url != null && remote == null ->
                "give one folder, webdav and the URL of a collection, or peer and the hub's address"
            target == WEBDAV && url == null ->
                "webdav needs the URL of a collection: jotter backends add webdav URL --user " +
                    "NAME. For a folder called webdav, write ./webdav"
            target == PEER && url == null ->
                "peer needs the hub's address: jotter backends add peer HOST[:PORT]. For a " +
                    "folder called peer, write ./peer"
            remote == null && (user != null || passwordInFile) ->
                "--user and --password-in-file are for webdav and peer only"
            target == PEER && (user != null || setUp) ->
                "--user and --new are not for peer: the hub's folder is set up on the PC"
            else -> null
        }

    private fun addPeer(address: String): AddResult {
        val place = if (passwordInFile) SecretPlace.FILE else SecretPlace.KEYRING
        val token = setup.passwords.read("Pairing token for the hub at $address: ")
        return setup.withBackends { it.addPeer(address, token, name, place) }
    }

    private fun addFolder(): AddResult {
        if (target.isEmpty()) throw CliktError("jotter: the path is empty")
        val path =
            try {
                Path.of(target).toAbsolutePath().toString()
            } catch (_: InvalidPathException) {
                throw CliktError("jotter: not a valid path: $target")
            }
        return setup.withBackends { it.addLocalFolder(path, name, setUp) }
    }

    private fun addWebDav(url: String): AddResult {
        val account = user ?: throw CliktError("jotter: webdav needs --user NAME")
        val place = if (passwordInFile) SecretPlace.FILE else SecretPlace.KEYRING
        // Read before the database is opened, so that a slow typist holds no lock.
        val password = setup.passwords.read("App password for $account at $url: ")
        return setup.withBackends { it.addWebDav(url, account, password, name, setUp, place) }
    }

    private fun notAStoreYet(
        location: String,
        webDav: Boolean,
    ) = if (webDav) {
        "jotter: $location is not a Jotter store yet. Setting it up checks that the server " +
            "keeps files, creates a hidden file .jotter-store there, and the next sync copies " +
            "the history of all your notes into a jotter folder in it. To set it up, run: " +
            "jotter backends add --new webdav $location --user $user"
    } else {
        "jotter: $location is not a Jotter store yet. Setting it up creates a hidden file " +
            ".jotter-store there, and the next sync copies the history of all your notes into a " +
            "jotter folder in it. If a USB stick or another drive belongs there, plug it in " +
            "first, or your notes go onto this computer's disk instead. To set it up, run: " +
            "jotter backends add --new $location"
    }

    private fun noKeyring(reason: String) =
        "jotter: $reason Nothing was added. To keep the app password in plain text in a file " +
            "only you can read instead, \$XDG_CONFIG_HOME/jotter/secrets.json, run the same " +
            "command with --password-in-file. Anyone who can read your files, or a backup of " +
            "them, can then read it."
}

/** `jotter backends password`: replaces a WebDAV backend's app password. */
private class PasswordCommand(
    private val setup: SyncSetup,
) : CoreCliktCommand(name = "password") {
    private val id by argument(name = "ID", help = "A backend id, or a unique start of one.")

    override fun help(context: Context): String =
        "Replace the app password of a WebDAV backend, or the pairing token of the PC hub, " +
            "after checking it. It is read from the terminal, or else from the first line of stdin."

    override fun run() {
        val backend = setup.withBackends { it.list() }.resolve(id)
        val (what, prompt) =
            when (val config = backend.config) {
                is WebDavConfig ->
                    "app password" to "New app password for ${config.username} at ${config.url}: "
                is PeerConfig -> "pairing token" to "New pairing token for ${config.location}: "
                else -> throw CliktError("jotter: ${backend.id} needs no password")
            }
        val password = setup.passwords.read(prompt)
        when (val result = setup.withBackends { it.changePassword(backend.id, password) }) {
            PasswordChange.Changed ->
                echo("Stored the new $what of ${oneLine(backend.name)}.")
            is PasswordChange.Refused -> throw CliktError("jotter: ${result.reason}")
        }
    }
}

/** `jotter backends remove`: stops syncing with a backend, whose files stay. */
private class RemoveBackendCommand(
    private val setup: SyncSetup,
) : CoreCliktCommand(name = "remove") {
    private val id by argument(name = "ID", help = "A backend id, or a unique start of one.")
    private val keepSecret by option(
        "--keep-secret",
        help =
            "Remove it without deleting its password or token, as when no keyring answers. " +
                "It says where that stays.",
    ).flag()

    override fun help(context: Context): String =
        "Stop syncing with a backend and forget it here, with its password. Its files stay " +
            "where they are."

    override fun run() {
        val (removed, leftBehind) =
            setup.withBackends { manager ->
                val backend = manager.list().resolve(id)
                try {
                    manager.remove(backend.id, keepSecrets = keepSecret)
                } catch (e: SecretStoreException) {
                    throw CliktError(
                        "jotter: cannot delete the password of ${backend.id}, so nothing was " +
                            "removed: ${e.message} To remove it anyway and delete the password " +
                            "later yourself, run: jotter backends remove --keep-secret ${backend.id}",
                        e,
                    )
                }
                backend to manager.secretsLeftBehind(backend)?.takeIf { keepSecret }
            }
        echo(
            "Removed ${oneLine(removed.name)} (${removed.id}). Its files stay in " +
                "${oneLine(removed.config.location)}: other devices can go on syncing there, and " +
                "Jotter never deletes them. Adding it again copies all your notes there again.",
        )
        leftBehind?.let { echo(it) }
    }
}

/** `jotter backends status`: whether each backend can be reached now, and how it last synced. */
private class BackendStatusCommand(
    private val setup: SyncSetup,
) : CoreCliktCommand(name = "status") {
    private val id by argument(
        name = "ID",
        help = "A backend id, or a unique start of one.",
    ).optional()

    override fun help(context: Context): String =
        "Show whether each backend can be reached now, its last sync and its issues."

    override fun run() {
        val statuses =
            setup.withBackends { manager ->
                val all = manager.status()
                val wanted =
                    id?.let { wanted -> all.map { it.backend }.resolve(wanted) }
                all
                    .filter { wanted == null || it.backend.id == wanted.id }
                    .map { it to manager.whereSecretsAre(it.backend) }
            }
        echo(statuses.joinToString("\n\n") { (status, secret) -> statusText(status, secret) })
    }

    private fun statusText(
        status: BackendStatus,
        secret: String?,
    ): String {
        val backend = status.backend
        val attempt = backend.lastAttempt
        val reachable = if (status.reachability == Reachability.Reachable) "yes" else "no"
        val lines =
            listOf(
                "id: ${backend.id}",
                "name: ${oneLine(backend.name)}",
                "location: ${oneLine(backend.config.location)}",
            ) + listOfNotNull(secret?.let { "secret: $it" }) +
                listOf(
                    "reachable: $reachable",
                    "state: ${status.reachabilityText}",
                    "last-success: ${timeOrNever(backend.lastSuccessAt)}",
                    "last-attempt: ${attempt?.let {
                        "${utcSeconds(it.at.toEpochMilliseconds())} ${outcomeName(it.outcome)}"
                    } ?: NEVER}",
                ) + listOfNotNull(attempt?.let { "detail: ${oneLine(it.detail)}" }) +
                attempt?.issues.orEmpty().map { "issue: ${oneLine(it)}" }
        return lines.joinToString("\n")
    }
}

private const val SEPARATOR = "\t"
private const val NEVER = "never"

private fun timeOrNever(time: Instant?) =
    time?.let { utcSeconds(it.toEpochMilliseconds()) } ?: NEVER

/** An outcome as the output names it: `synced`, `unreachable`, and so on. */
private fun outcomeName(outcome: Outcome) = outcome.name.lowercase()

/** The backend whose id is [idOrPrefix], or the only one whose id starts with it. */
private fun List<Backend>.resolve(idOrPrefix: String): Backend {
    firstOrNull { it.id == idOrPrefix }?.let { return it }
    val matches = filter { idOrPrefix.isNotEmpty() && it.id.startsWith(idOrPrefix) }
    return when (matches.size) {
        1 -> matches.single()
        0 -> throw CliktError("jotter: no backend with id $idOrPrefix")
        else -> throw CliktError(
            "jotter: more than one backend has an id starting with $idOrPrefix",
        )
    }
}
