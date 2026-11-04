package io.github.sameerkrmishra.jotter.app.android

import io.github.sameerkrmishra.jotter.app.sharedui.DatabaseExportPlace
import io.github.sameerkrmishra.jotter.app.sharedui.ExportStep
import io.github.sameerkrmishra.jotter.app.sync.ExportOutcome
import io.github.sameerkrmishra.jotter.app.sync.describeExportFailure
import io.github.sameerkrmishra.jotter.core.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/** The suggested name of an exported database. */
internal const val DATABASE_FILE_NAME = "jotter-notes.db"

/** Its type for the document picker: any, so that no provider adds an extension of its own. */
internal const val DATABASE_FILE_TYPE = "application/octet-stream"

/** The folder in the app's cache where the copy is made before it goes where the user chose. */
internal const val EXPORT_STAGING = "export"

/** What the dialog says while no screen can show Android's document picker. */
internal const val NO_SCREEN_TO_EXPORT =
    "Jotter is not on the screen, so it cannot ask where to save the copy. Open it and try again."

/** The phone's own storage, where the copy is made first, as the user knows it. */
internal const val PHONE_STORAGE = "this phone's storage"

/** The place the user chose, as the dialog names it. */
private const val CHOSEN_PLACE = "the place you chose"

/**
 * The export of the screen on top, as [ScreenExport] is for the debug log: only an Activity can
 * show Android's document picker. Each Activity sets itself as [screen] while it exists.
 */
internal class ScreenDatabaseExport : DatabaseExportPlace {
    /** The Activity's export, or null while there is none. */
    @Volatile
    var screen: DatabaseExportPlace? = null

    override suspend fun export(): ExportStep =
        screen?.export() ?: ExportStep.Failed(NO_SCREEN_TO_EXPORT)
}

/**
 * A document the user chose in Android's document picker: one the picker made, or one they agreed
 * there to overwrite.
 *
 * @property name Its name, for the user.
 */
internal interface ChosenDocument {
    val name: String

    /** Opens it to be written from the start. */
    fun open(): OutputStream

    /** Deletes it, after a failed copy: a part of a database is of no use to anyone. */
    fun delete()
}

/**
 * "Export database" on Android. The picker gives a document, not a folder, so the copy cannot be
 * renamed into place there. Instead it is first made with `VACUUM INTO` in [staging], in the
 * app's own storage, where it is checked, before the picker even shows: if that fails, the user
 * is told and no document is touched. Then [choose] shows the picker, and the copy is written byte
 * for byte to the document and synced. If that fails, the document is deleted, so no half copy is
 * left under its name. The staged copy is always deleted.
 *
 * @param export Writes the copy to a file, replacing it if told to: `DatabaseExport.export`.
 * @param choose Shows the picker; null if the user cancelled.
 */
internal suspend fun exportThroughPicker(
    export: suspend (file: Path, replace: Boolean) -> ExportOutcome,
    staging: File,
    io: CoroutineContext,
    choose: suspend () -> ChosenDocument?,
): ExportStep {
    val copy = File(staging, DATABASE_FILE_NAME)
    try {
        val made =
            withContext(io) {
                staging.mkdirs()
                export(copy.toOkioPath(), true)
            }
        return when (made) {
            is ExportOutcome.Exported -> {
                val document = choose() ?: return ExportStep.Cancelled
                writeDocument(copy, document, io)
            }
            is ExportOutcome.Failed -> ExportStep.Failed(made.message)
            is ExportOutcome.Exists -> error("The staged copy may always be replaced")
        }
    } finally {
        withContext(io) { copy.delete() }
    }
}

@Suppress("TooGenericExceptionCaught") // Any failure of the document's provider is told the same.
private suspend fun writeDocument(
    copy: File,
    document: ChosenDocument,
    io: CoroutineContext,
): ExportStep =
    withContext(io) {
        try {
            document.open().use { output ->
                copy.inputStream().use { it.copyTo(output) }
                output.flush()
                if (output is FileOutputStream) output.fd.sync()
            }
            ExportStep.Done(
                "Exported your notes to ${document.name}. Passwords and pairing tokens are not " +
                    "in it: after restoring it, enter them again.",
            )
        } catch (e: CancellationException) {
            discard(document)
            throw e
        } catch (e: Exception) {
            log.warn(e) { "The copy could not be written to the chosen document" }
            discard(document)
            ExportStep.Failed(describeExportFailure(e, CHOSEN_PLACE))
        }
    }

@Suppress("TooGenericExceptionCaught") // Deleting is the best it can do; the failure is logged.
private fun discard(document: ChosenDocument) {
    try {
        document.delete()
    } catch (e: Exception) {
        log.warn(e) { "A failed copy could not be deleted" }
    }
}

private val log = Logger("android")
