package io.github.sameerkrmishra.jotter.app.desktop

import io.github.sameerkrmishra.jotter.core.storage.DATABASE_ENVVAR
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
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
    cause: Exception,
) : Exception(message, cause)

/**
 * Opens the same database as the CLI, creating the file and its directory if needed.
 *
 * @param readEnvvar Reads an environment variable.
 * @param userHome The home directory if `HOME` is not set.
 * @param queryContext Where the store's flows run their queries.
 * @throws DatabaseUnavailableException if that fails.
 */
@Suppress("TooGenericExceptionCaught") // The driver's exception is not part of NoteStore's API.
internal fun openNoteStore(
    readEnvvar: (String) -> String?,
    userHome: String,
    queryContext: CoroutineContext,
): NoteStore {
    val path = linuxDatabasePath(readEnvvar(DATABASE_ENVVAR), readEnvvar, userHome)
    val file = Path.of(path).toAbsolutePath()
    return try {
        Files.createDirectories(file.parent)
        NoteStore.open(file.toString(), DEVICE_NAME, queryContext)
    } catch (e: IOException) {
        throw DatabaseUnavailableException("cannot create the directory of $path", e)
    } catch (e: RuntimeException) {
        throw DatabaseUnavailableException("cannot open $path: ${e.message}", e)
    }
}
