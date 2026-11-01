package io.github.sameerkrmishra.jotter.app.cli

import androidx.sqlite.SQLiteException
import com.github.ajalt.clikt.core.CliktError
import io.github.sameerkrmishra.jotter.core.sqlite.isSqliteBusy
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext

/** The environment variable that names the database file. */
internal const val DATABASE_ENVVAR = "JOTTER_DB"

/** Used only when the database is created. The file may be shared with the desktop app. */
private const val DEVICE_NAME = "Linux"

/**
 * Returns the database file: [option] (`--db` or `JOTTER_DB`), then `jotter/jotter.db` in
 * `$XDG_DATA_HOME`, then in `~/.local/share`. The XDG Base Directory specification says to ignore
 * an empty or relative `XDG_DATA_HOME`. An empty [option] counts as not given.
 *
 * @param readEnvvar Reads an environment variable.
 * @param userHome The home directory if `HOME` is not set.
 */
internal fun databasePath(
    option: String?,
    readEnvvar: (String) -> String?,
    userHome: String,
): Path {
    if (!option.isNullOrEmpty()) return Path.of(option)
    val dataHome =
        readEnvvar("XDG_DATA_HOME")?.let(Path::of)?.takeIf(Path::isAbsolute)
            ?: Path.of(readEnvvar("HOME") ?: userHome, ".local", "share")
    return dataHome.resolve("jotter").resolve("jotter.db")
}

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
        // NoteService translates a locked database only for a write, NoteStore.open not at all.
        val reason = e.message.orEmpty().substringAfter("message: ")
        throw if (isSqliteBusy(e)) locked(path) else cannotOpen(path, reason, e)
    }

private fun open(path: Path): NoteStore {
    val file = path.toAbsolutePath()
    try {
        Files.createDirectories(file.parent)
    } catch (e: IOException) {
        throw cannotOpen(path, "cannot create its directory", e)
    }
    return try {
        NoteStore.open(file.toString(), DEVICE_NAME, EmptyCoroutineContext)
    } catch (e: IllegalStateException) {
        // Another application's database, or one written by a newer version of Jotter.
        throw cannotOpen(path, e.message.orEmpty(), e)
    }
}

private fun locked(path: Path) = CliktError("jotter: $path is locked by another process, try again")

private fun cannotOpen(
    path: Path,
    reason: String,
    cause: Exception,
) = CliktError("jotter: cannot open $path: $reason", cause)
