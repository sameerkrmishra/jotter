package io.github.sameerkrmishra.jotter.app.desktop

import io.github.sameerkrmishra.jotter.core.storage.DATABASE_ENVVAR
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import io.github.sameerkrmishra.jotter.core.storage.linuxDatabasePath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

/** Used only when the database is created. The file may be shared with the CLI. */
private const val DEVICE_NAME = "Linux"

/** The notes could not be opened. The message says why, for the user. */
internal class DatabaseUnavailableException(
    message: String,
    cause: Exception? = null,
) : Exception(message, cause)

/**
 * Opens the same database as the CLI, creating the file and its directory if needed.
 *
 * @param readEnvvar Reads an environment variable.
 * @param userHome The home directory if `HOME` is not set.
 * @param queryContext Where the store's flows run their queries.
 * @throws DatabaseUnavailableException if that fails.
 */
internal fun openNoteStore(
    readEnvvar: (String) -> String?,
    userHome: String,
    queryContext: CoroutineContext,
): NoteStore {
    val path =
        linuxDatabasePath(readEnvvar(DATABASE_ENVVAR), readEnvvar, userHome)
            ?: throw DatabaseUnavailableException("no database location: set HOME or JOTTER_DB")
    return open(path, queryContext)
}

@Suppress("TooGenericExceptionCaught") // The driver's exception is not part of NoteStore's API.
private fun open(
    path: String,
    queryContext: CoroutineContext,
): NoteStore =
    try {
        val file = Path.of(path).toAbsolutePath()
        Files.createDirectories(file.parent)
        NoteStore.open(file.toString(), DEVICE_NAME, queryContext)
    } catch (e: IOException) {
        throw DatabaseUnavailableException("cannot create the directory of $path", e)
    } catch (e: RuntimeException) {
        throw DatabaseUnavailableException(reason(path, e), e)
    }

private fun reason(
    path: String,
    failure: RuntimeException,
): String =
    if (failure is StorageBusyException) {
        "$path is locked by another process, try again"
    } else {
        "cannot open $path: ${failure.message}"
    }
