package io.github.sameerkrmishra.jotter.app.desktop

import io.github.sameerkrmishra.jotter.app.sharedui.DatabaseExportPlace
import io.github.sameerkrmishra.jotter.app.sharedui.ExportStep
import io.github.sameerkrmishra.jotter.app.sharedui.exportStep
import io.github.sameerkrmishra.jotter.app.sync.DatabaseExport
import okio.Path.Companion.toOkioPath
import java.nio.file.Path

/** The menu item. */
internal const val EXPORT_DATABASE_ITEM = "Export database…"

/** The suggested name of an exported database. */
internal const val DATABASE_FILE_NAME = "jotter-notes.db"

/**
 * "Export database" on the desktop: the user chooses a file with [choose], which returns null if
 * they cancelled, and [export] writes the copy there. A file that is there already is replaced
 * only once the user has said so in the app's own dialog, as a save dialog may not ask.
 */
internal class DatabaseFile(
    private val choose: suspend () -> Path?,
    private val export: DatabaseExport,
) : DatabaseExportPlace {
    override suspend fun export(): ExportStep {
        val file = choose() ?: return ExportStep.Cancelled
        return exportStep(export, file.toAbsolutePath().toOkioPath())
    }
}
