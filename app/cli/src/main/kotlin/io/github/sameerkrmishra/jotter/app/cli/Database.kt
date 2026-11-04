package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import io.github.sameerkrmishra.jotter.app.sync.describeOpenFailure
import io.github.sameerkrmishra.jotter.app.sync.describeStorageFailure
import io.github.sameerkrmishra.jotter.core.logging.Logger
import io.github.sameerkrmishra.jotter.core.storage.DATABASE_ENVVAR
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.StorageProblem
import io.github.sameerkrmishra.jotter.core.storage.linuxDatabasePath
import io.github.sameerkrmishra.jotter.core.storage.storageProblemOf
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext

/** Used only when the database is created. The file may be shared with the desktop app. */
internal const val DEVICE_NAME = "Linux"

/**
 * Returns the database file, by [linuxDatabasePath].
 *
 * @param option `--db` or `JOTTER_DB`.
 * @param readEnvvar Reads an environment variable.
 * @param userHome The home directory if `HOME` is not set.
 * @throws CliktError if there is no usable location, or the result is not a valid path, which
 *   happens to non-ASCII text under a locale that cannot represent it.
 */
internal fun databasePath(
    option: String?,
    readEnvvar: (String) -> String?,
    userHome: String,
): Path {
    val path =
        linuxDatabasePath(option, readEnvvar, userHome)
            ?: throw CliktError("jotter: no database location: set HOME, --db or $DATABASE_ENVVAR")
    return try {
        Path.of(path)
    } catch (e: InvalidPathException) {
        throw CliktError("jotter: not a valid path: ${e.input}", e)
    }
}

/** The database file, which is known once the root command's options are parsed. */
internal class DatabaseLocation {
    /** The file. The root command sets it before a subcommand runs. */
    lateinit var path: Path
}

/**
 * Opens the notes at [path], creating the file and its directory if needed, runs [block] on them
 * and closes them. A failure the user can meet becomes a [CliktError] that says what happened and
 * what to do, which Clikt prints without a stack trace before exiting with status 1. The exception
 * itself goes to the log.
 *
 * The CLI collects no flows, so the store gets no dispatcher for them. That also keeps the classes
 * of `Dispatchers.IO` out of startup.
 */
internal fun <T> withNotes(
    path: Path,
    block: (NoteService) -> T,
): T = withStore(path) { block(it.notes) }

/**
 * [withNotes], for a command that needs the whole store, such as what sync left to look at. A
 * failure of the database while [block] runs, such as a full disk, becomes a [CliktError] in plain
 * words too; anything else is [block]'s own.
 */
@Suppress("TooGenericExceptionCaught") // Only failures of the database are caught.
internal fun <T> withStore(
    path: Path,
    block: (NoteStore) -> T,
): T =
    open(path).use { store ->
        try {
            block(store)
        } catch (e: CliktError) {
            throw e
        } catch (e: RuntimeException) {
            if (storageProblemOf(e) == StorageProblem.OTHER) throw e
            log.error(e) { "A command failed on the database $path" }
            throw CliktError("jotter: ${describeStorageFailure(e)}", e)
        }
    }

@Suppress("TooGenericExceptionCaught") // The driver's exception is not part of NoteStore's API.
private fun open(path: Path): NoteStore {
    createDirectory(path)
    return try {
        NoteStore.open(path.toAbsolutePath().toString(), DEVICE_NAME, EmptyCoroutineContext)
    } catch (e: RuntimeException) {
        // Nothing was changed: a damaged or foreign file stays as it was (NoteStore.open).
        log.error(e) { "Cannot open the database $path" }
        throw CliktError("jotter: ${describeOpenFailure(e, path.toString())}", e)
    }
}

private fun createDirectory(path: Path) {
    try {
        // The root directory has no parent.
        path.toAbsolutePath().parent?.let(Files::createDirectories)
    } catch (e: IOException) {
        log.error(e) { "Cannot create the folder of $path" }
        throw CliktError(
            "jotter: Jotter cannot create the folder for $path. Check that the path is right " +
                "and that you may create folders there.",
            e,
        )
    }
}

private val log = Logger("cli")
