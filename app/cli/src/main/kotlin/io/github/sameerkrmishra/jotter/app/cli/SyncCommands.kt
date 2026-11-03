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
import io.github.sameerkrmishra.jotter.app.sync.NoSecretStore
import io.github.sameerkrmishra.jotter.app.sync.Outcome
import io.github.sameerkrmishra.jotter.app.sync.StandardBackendFactory
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.DeviceInfo
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Instant

/**
 * Runs [block] with a [BackendManager] over the database at [path]. Everything runs on this
 * thread: the CLI does one thing at a time, and the local folder brings its own I/O dispatcher.
 */
private fun <T> withBackends(
    path: Path,
    block: suspend (BackendManager) -> T,
): T =
    withStore(path) { store ->
        runBlocking { block(managerOf(store)) }
    }

private fun managerOf(store: NoteStore): BackendManager {
    val engine = SyncEngine(store, DeviceInfo(DEVICE_NAME, PLATFORM), EmptyCoroutineContext)
    return BackendManager(
        store,
        engine,
        StandardBackendFactory,
        NoSecretStore,
        EmptyCoroutineContext,
    )
}

private const val PLATFORM = "linux"

/** `jotter sync`: one sync with every backend, one line each. */
internal class SyncCommand(
    private val location: DatabaseLocation,
) : CoreCliktCommand(name = "sync") {
    override fun help(context: Context): String =
        "Sync with every backend once, printing tab-separated lines: id, name, outcome, detail."

    override fun run() {
        val synced = withBackends(location.path) { it.syncAll() }
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
    private val location: DatabaseLocation,
) : CoreCliktCommand(name = "backends") {
    override val invokeWithoutSubcommand: Boolean = true

    init {
        subcommands(
            AddBackendCommand(location),
            RemoveBackendCommand(location),
            BackendStatusCommand(location),
        )
    }

    override fun help(context: Context): String =
        "List the backends as tab-separated lines: id, name, location, last success, last outcome."

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        withBackends(location.path) { it.list() }.forEach { backend ->
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

/** `jotter backends add`: adds a folder as a backend, and prints its id. */
private class AddBackendCommand(
    private val location: DatabaseLocation,
) : CoreCliktCommand(name = "add") {
    private val folder by argument(name = "PATH", help = "The folder, such as a USB stick.").path()
    private val name by option("--name", help = "A name for it. Default: the folder's name.")
    private val setUp by option(
        "--new",
        help = "Set the folder up as a new Jotter store if it is not one yet.",
    ).flag()

    override fun help(context: Context): String = "Add a folder as a backend and print its id."

    override fun run() {
        val path = folder.toAbsolutePath().toString()
        when (val result = withBackends(location.path) { it.addLocalFolder(path, name, setUp) }) {
            is AddResult.Added -> echo(result.backend.id)
            is AddResult.NotAStoreYet -> throw CliktError(notAStoreYet(result.path))
            is AddResult.AlreadyAdded ->
                throw CliktError(
                    "jotter: ${result.backend.config.location} is already the backend ${result.backend.id}",
                )
            is AddResult.Refused -> throw CliktError("jotter: ${result.reason}")
        }
    }

    private fun notAStoreYet(path: String) =
        "jotter: $path is not a Jotter store yet. Setting it up creates a hidden file " +
            ".jotter-store there, and the next sync copies the history of all your notes into a " +
            "jotter folder in it. If a USB stick or another drive belongs there, plug it in " +
            "first, or your notes go onto this computer's disk instead. To set it up, run: " +
            "jotter backends add --new $path"
}

/** `jotter backends remove`: stops syncing with a backend, whose files stay. */
private class RemoveBackendCommand(
    private val location: DatabaseLocation,
) : CoreCliktCommand(name = "remove") {
    private val id by argument(name = "ID", help = "A backend id, or a unique start of one.")

    override fun help(context: Context): String =
        "Stop syncing with a backend and forget it here. Its files stay where they are."

    override fun run() {
        val removed =
            withBackends(location.path) { manager ->
                val backend = manager.list().resolve(id)
                manager.remove(backend.id)
                backend
            }
        echo(
            "Removed ${oneLine(removed.name)} (${removed.id}). Its files stay in " +
                "${oneLine(removed.config.location)}: other devices can go on syncing there, and " +
                "Jotter never deletes them. Adding it again copies all your notes there again.",
        )
    }
}

/** `jotter backends status`: whether each backend can be reached now, and how it last synced. */
private class BackendStatusCommand(
    private val location: DatabaseLocation,
) : CoreCliktCommand(name = "status") {
    private val id by argument(
        name = "ID",
        help = "A backend id, or a unique start of one.",
    ).optional()

    override fun help(context: Context): String =
        "Show whether each backend can be reached now, its last sync and its issues."

    override fun run() {
        val statuses =
            withBackends(location.path) { manager ->
                val all = manager.status()
                val wanted = id ?: return@withBackends all
                val backend = all.map { it.backend }.resolve(wanted)
                all.filter { it.backend.id == backend.id }
            }
        echo(statuses.joinToString("\n\n", transform = ::statusText))
    }

    private fun statusText(status: BackendStatus): String {
        val backend = status.backend
        val attempt = backend.lastAttempt
        val lines =
            listOf(
                "id: ${backend.id}",
                "name: ${oneLine(backend.name)}",
                "location: ${oneLine(backend.config.location)}",
                "reachable: ${if (status.reachability == Reachability.Reachable) "yes" else "no"}",
                "state: ${status.reachabilityText}",
                "last-success: ${timeOrNever(backend.lastSuccessAt)}",
                "last-attempt: ${attempt?.let {
                    "${utcSeconds(
                        it.at.toEpochMilliseconds(),
                    )} ${outcomeName(it.outcome)}"
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
