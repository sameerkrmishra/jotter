package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.core.logging.Logger
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.StorageProblem
import io.github.sameerkrmishra.jotter.core.storage.storageProblemOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.use
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/** What every app says beside "Export database": what the copy holds, and what it does not. */
public const val EXPORT_DATABASE_EXPLAINED: String =
    "Jotter writes a copy of your notes database to one file: every note, its history, the " +
        "trash, and which backends this device syncs with. Use it as a backup, or open it on " +
        "another computer. Passwords and pairing tokens are not in it: after restoring it, enter " +
        "them again."

/** How an export ended. */
public sealed interface ExportOutcome {
    /** The copy is at [file], [bytes] long. [message] says so, for the user. */
    public data class Exported(
        val file: Path,
        val bytes: Long,
        val message: String,
    ) : ExportOutcome

    /** [file] exists, so nothing was written: the user must confirm that it may be replaced. */
    public data class Exists(
        val file: Path,
    ) : ExportOutcome

    /** Nothing was written, for the reason in [message], in plain words. */
    public data class Failed(
        val message: String,
    ) : ExportOutcome
}

/**
 * "Export database" (ADR-0002): a consistent copy of [store] in a single file, while the app runs
 * and other processes write to the database. The copy is written by `VACUUM INTO` to a new file in
 * the target's folder, checked, synced to the disk and only then renamed to the target, so a
 * half-written copy never has the target's name. An existing file is replaced only when asked to.
 * The live database is only read.
 *
 * @param io Where the blocking work runs.
 * @param describeFolder A folder as the user knows it, for what is said when the copy fails.
 */
public class DatabaseExport(
    private val store: NoteStore,
    private val io: CoroutineContext,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val describeFolder: (Path) -> String = Path::toString,
) {
    /**
     * Writes the copy to [file], an absolute path. If [file] exists and [replace] is false,
     * nothing is written and the outcome is [ExportOutcome.Exists].
     */
    public suspend fun export(
        file: Path,
        replace: Boolean = false,
    ): ExportOutcome =
        withContext(io) {
            val folder = file.parent
            when {
                !file.isAbsolute || folder == null -> ExportOutcome.Failed(notAbsolute(file))
                isLiveDatabase(file) -> ExportOutcome.Failed(LIVE_DATABASE)
                fileSystem.metadataOrNull(folder)?.isDirectory != true ->
                    ExportOutcome.Failed(
                        "The folder ${describeFolder(folder)} does not exist. Choose another " +
                            "place for the copy.",
                    )
                fileSystem.metadataOrNull(file)?.isDirectory == true ->
                    ExportOutcome.Failed("$file is a folder. Choose a file name for the copy.")
                !replace && fileSystem.exists(file) -> ExportOutcome.Exists(file)
                else -> write(file, folder, replace)
            }
        }

    @Suppress("TooGenericExceptionCaught") // The driver's exception is not part of NoteStore's API.
    private fun write(
        file: Path,
        folder: Path,
        replace: Boolean,
    ): ExportOutcome {
        val part = folder / ".${file.name}.${Random.nextLong().toULong().toString(PART_RADIX)}.part"
        return try {
            store.exportTo(part.toString())
            // VACUUM INTO need not sync the copy: it must be on the disk before it is renamed.
            fileSystem.openReadWrite(part).use { it.flush() }
            if (!replace && fileSystem.exists(file)) {
                fileSystem.delete(part)
                return ExportOutcome.Exists(file)
            }
            fileSystem.atomicMove(part, file)
            val bytes = fileSystem.openReadOnly(file).use { it.size() }
            log.info { "Exported the database, $bytes bytes" }
            ExportOutcome.Exported(file, bytes, exported(file))
        } catch (e: CancellationException) {
            removePart(part)
            throw e
        } catch (e: Exception) {
            removePart(part)
            log.warn(e) { "Exporting the database failed" }
            ExportOutcome.Failed(describeExportFailure(e, describeFolder(folder)))
        }
    }

    @Suppress("SwallowedException") // It is gone or was never written; the failure is logged.
    private fun removePart(part: Path) {
        try {
            fileSystem.delete(part)
        } catch (e: IOException) {
            log.warn(e) { "A part of the copy was left behind" }
        }
    }

    /** Whether [file] is the database itself or one of its files beside it. */
    private fun isLiveDatabase(file: Path): Boolean {
        val live = canonical(store.path.toPath())
        val target = canonical(file)
        return SIDE_FILES.any { suffix -> target.toString() == "$live$suffix" }
    }

    @Suppress("SwallowedException") // A file that does not exist yet is compared as written.
    private fun canonical(path: Path): Path =
        try {
            fileSystem.canonicalize(path)
        } catch (e: IOException) {
            path.parent?.let { parent ->
                try {
                    fileSystem.canonicalize(parent) / path.name
                } catch (_: IOException) {
                    null
                }
            } ?: path.normalized()
        }

    private companion object {
        val log = Logger("export")
        const val PART_RADIX = 36
        val SIDE_FILES = listOf("", "-wal", "-shm", "-journal")
    }
}

/** What the user is told after an export to [file]. */
private fun exported(file: Path): String =
    "Exported your notes to $file. Passwords and pairing tokens are not in it: after restoring " +
        "it, enter them again."

private fun notAbsolute(file: Path): String =
    "$file is not a full path. Give the whole path of the new file, such as /media/stick/jotter.db."

internal const val LIVE_DATABASE: String =
    "That is the notes file Jotter is using. Choose another name or folder for the copy."

/**
 * Why an export to [folder] failed, and what to do, from [failure]. In every case the notes were
 * only read, so the words say that they did not change.
 */
public fun describeExportFailure(
    failure: Throwable,
    folder: String,
): String {
    val unchanged = "Your notes have not been changed."
    return when (exportProblemOf(failure)) {
        StorageProblem.DISK_FULL ->
            "There is not enough free space in $folder for the copy. Free some space or choose " +
                "another place. $unchanged"
        StorageProblem.NO_ACCESS, StorageProblem.READ_ONLY ->
            "Jotter is not allowed to write in $folder. Choose a folder you may write to. " +
                unchanged
        StorageProblem.DISK_ERROR ->
            "The disk at $folder could not be written. If it is a removable drive, check that it " +
                "is plugged in, then try again. $unchanged"
        StorageProblem.LOCKED ->
            "Another Jotter program kept the notes busy for too long. Try again. $unchanged"
        else ->
            "The copy could not be written to $folder. $unchanged Try again. If it keeps " +
                "happening, report it and attach the debug log."
    }
}

private const val MAX_CAUSES = 8

/** [storageProblemOf], and the same for a file system's failure, which says it in its message. */
private fun exportProblemOf(failure: Throwable): StorageProblem {
    val sqlite = storageProblemOf(failure)
    if (sqlite != StorageProblem.OTHER) return sqlite
    val messages =
        generateSequence(failure) { it.cause }
            .take(MAX_CAUSES)
            .filter { it is IOException }
            .map { "${it::class.simpleName} ${it.message}" }
            .toList()
    return when {
        messages.any { "No space left" in it || "Disk quota" in it } -> StorageProblem.DISK_FULL
        messages.any {
            "Permission denied" in it || "AccessDenied" in it || "Read-only file system" in it
        } -> StorageProblem.NO_ACCESS
        messages.isNotEmpty() -> StorageProblem.DISK_ERROR
        else -> StorageProblem.OTHER
    }
}
