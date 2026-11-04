package io.github.sameerkrmishra.jotter.app.desktop

import io.github.sameerkrmishra.jotter.app.sync.describeOpenFailure
import io.github.sameerkrmishra.jotter.core.logging.Logger
import io.github.sameerkrmishra.jotter.core.storage.DATABASE_ENVVAR
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.linuxDatabasePath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

/** Used only when the database is created. The file may be shared with the CLI. */
internal const val DEVICE_NAME = "Linux"

/** The notes could not be opened. The message says why and what to do, for the user. */
internal class DatabaseUnavailableException(
    message: String,
    cause: Exception? = null,
) : Exception(message, cause)

/**
 * Opens the same database as the CLI, creating the file and its directory if needed. A file that
 * cannot be opened is left as it is, never replaced by a new one.
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
            ?: throw DatabaseUnavailableException(
                "Jotter does not know where your notes are: neither HOME nor JOTTER_DB is set. " +
                    "Start Jotter from your desktop's menu, or set JOTTER_DB to the notes file.",
            )
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
        log.error(e) { "Cannot create the folder of $path" }
        throw DatabaseUnavailableException(
            "Jotter cannot create the folder for $path. Check that you may create folders there.",
            e,
        )
    } catch (e: RuntimeException) {
        log.error(e) { "Cannot open the database $path" }
        throw DatabaseUnavailableException(describeOpenFailure(e, path), e)
    }

private val log = Logger("desktop")
