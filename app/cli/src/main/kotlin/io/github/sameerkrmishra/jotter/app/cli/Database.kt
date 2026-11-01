package io.github.sameerkrmishra.jotter.app.cli

import androidx.sqlite.SQLiteException
import com.github.ajalt.clikt.core.CliktError
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext

/** The environment variable that names the database file. */
internal const val DATABASE_ENVVAR = "JOTTER_DB"

/** Used only when the database is created. The file may be shared with the desktop app. */
private const val DEVICE_NAME = "Linux"

private const val UNABLE_TO_OPEN = "unable to open database file"

/**
 * Returns the database file: [option] (`--db` or `JOTTER_DB`), then `jotter/jotter.db` in
 * `$XDG_DATA_HOME`, then in `~/.local/share`. The XDG Base Directory specification says to ignore
 * an empty or relative `XDG_DATA_HOME`. An empty [option] or `HOME` counts as not given.
 *
 * @param readEnvvar Reads an environment variable.
 * @param userHome The home directory if `HOME` is not set.
 * @throws CliktError if there is no usable location, or a variable is not a valid path, which
 *   happens to non-ASCII text under a locale that cannot represent it.
 */
internal fun databasePath(
    option: String?,
    readEnvvar: (String) -> String?,
    userHome: String,
): Path {
    try {
        if (!option.isNullOrEmpty()) return Path.of(option)
        val dataHome =
            readEnvvar("XDG_DATA_HOME")?.let(Path::of)?.takeIf(Path::isAbsolute)
                ?: homeDirectory(readEnvvar, userHome).resolve(".local").resolve("share")
        return dataHome.resolve("jotter").resolve("jotter.db")
    } catch (e: InvalidPathException) {
        throw CliktError("jotter: not a valid path: ${e.input}", e)
    }
}

// The JVM's user.home is "?" for a user without a passwd entry. A relative home would put the
// notes wherever the command happened to run.
private fun homeDirectory(
    readEnvvar: (String) -> String?,
    userHome: String,
): Path =
    Path
        .of(readEnvvar("HOME")?.takeUnless(String::isEmpty) ?: userHome)
        .takeIf(Path::isAbsolute)
        ?: throw CliktError("jotter: no database location: set HOME, --db or $DATABASE_ENVVAR")

/** The database file, which is known once the root command's options are parsed. */
internal class DatabaseLocation {
    /** The file. The root command sets it before a subcommand runs. */
    lateinit var path: Path
}

/**
 * Opens the notes at [path], creating the file and its directory if needed, runs [block] on them
 * and closes them. A failure the user can meet becomes a [CliktError], which Clikt prints without
 * a stack trace before exiting with status 1.
 *
 * The CLI collects no flows, so the store gets no dispatcher for them. That also keeps the classes
 * of `Dispatchers.IO` out of startup.
 */
internal fun <T> withNotes(
    path: Path,
    block: (NoteService) -> T,
): T =
    try {
        open(path).use { store -> block(store.notes) }
    } catch (_: StorageBusyException) {
        throw locked(path)
    } catch (e: SQLiteException) {
        // Without a message, SQLite could not even open the file: a directory, or no permission.
        val reason = e.message.orEmpty().substringAfter("message: ", UNABLE_TO_OPEN)
        throw cannotOpen(path, reason, e)
    }

private fun open(path: Path): NoteStore {
    createDirectory(path)
    return try {
        NoteStore.open(path.toAbsolutePath().toString(), DEVICE_NAME, EmptyCoroutineContext)
    } catch (e: StorageBusyException) {
        throw e
    } catch (e: IllegalStateException) {
        // Another application's database, or one written by a newer version of Jotter.
        throw cannotOpen(path, e.message.orEmpty(), e)
    }
}

private fun createDirectory(path: Path) {
    try {
        // The root directory has no parent.
        path.toAbsolutePath().parent?.let(Files::createDirectories)
    } catch (e: IOException) {
        throw cannotOpen(path, "cannot create its directory", e)
    }
}

private fun locked(path: Path) = CliktError("jotter: $path is locked by another process, try again")

private fun cannotOpen(
    path: Path,
    reason: String,
    cause: Exception,
) = CliktError("jotter: cannot open $path: $reason", cause)
