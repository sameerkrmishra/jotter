package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sameerkrmishra.jotter.app.sync.DatabaseExport
import io.github.sameerkrmishra.jotter.app.sync.EXPORT_DATABASE_EXPLAINED
import io.github.sameerkrmishra.jotter.app.sync.ExportOutcome
import io.github.sameerkrmishra.jotter.core.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.Path

internal const val EXPORT_DATABASE = "Export database…"
internal const val EXPORT_DATABASE_TITLE = "Export database"
internal const val EXPORT_DATABASE_RUNNING = "Writing the copy…"
internal const val EXPORT_DATABASE_FAILED =
    "The copy could not be written. Your notes have not been changed. Try again. If it keeps " +
        "happening, report it and attach the debug log."
internal const val REPLACE = "Replace"

/** What became of one step of "Export database". */
public sealed interface ExportStep {
    /** The copy is written; [message] says where, and that secrets are not in it. */
    public data class Done(
        val message: String,
    ) : ExportStep

    /** The user cancelled the choice of a place. Nothing was written. */
    public data object Cancelled : ExportStep

    /**
     * A file named [name] is where the user chose: nothing was written. [replace] writes over it,
     * once the user has said so.
     */
    public class Exists(
        public val name: String,
        public val replace: suspend () -> ExportStep,
    ) : ExportStep

    /** Nothing was written, for the reason in [message], in plain words. */
    public data class Failed(
        val message: String,
    ) : ExportStep
}

/**
 * "Export database" of one app: it asks where the copy goes, in the app's own way (a save dialog
 * on the desktop, the document picker on Android), and writes it there.
 */
public fun interface DatabaseExportPlace {
    /** Asks the user where, and writes the copy. */
    public suspend fun export(): ExportStep
}

/**
 * [export] of [file] as an [ExportStep], for an app that has a path: an existing file is replaced
 * only after the user says so, through [ExportStep.Exists].
 */
public suspend fun exportStep(
    export: DatabaseExport,
    file: Path,
    replace: Boolean = false,
): ExportStep =
    when (val outcome = export.export(file, replace)) {
        is ExportOutcome.Exported -> ExportStep.Done(outcome.message)
        is ExportOutcome.Exists ->
            ExportStep.Exists(outcome.file.name) { exportStep(export, file, replace = true) }
        is ExportOutcome.Failed -> ExportStep.Failed(outcome.message)
    }

/**
 * The "Export database" dialog: it says what the copy holds and that secrets are not in it, asks
 * where, asks before replacing a file, and says how it went.
 */
@Stable
public class ExportDatabaseViewModel internal constructor(
    private val place: DatabaseExportPlace,
    private val scope: CoroutineScope,
    private val onClose: () -> Unit,
) {
    /** What the dialog shows now. */
    internal var stage by mutableStateOf<Stage>(Stage.Explaining)
        private set

    /** The user asked to choose a place. */
    internal fun choose() = run { place.export() }

    /** The user said the file may be replaced. */
    internal fun replace() {
        val asked = stage as? Stage.Confirming ?: return
        run { asked.exists.replace() }
    }

    @Suppress("TooGenericExceptionCaught") // An app's own failure is said the same way.
    private fun run(step: suspend () -> ExportStep) {
        if (stage == Stage.Running) return
        stage = Stage.Running
        scope.launch {
            val result =
                try {
                    step()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "Exporting the database failed" }
                    ExportStep.Failed(EXPORT_DATABASE_FAILED)
                }
            when (result) {
                is ExportStep.Done -> stage = Stage.Finished(result.message)
                is ExportStep.Failed -> stage = Stage.Finished(result.message)
                is ExportStep.Exists -> stage = Stage.Confirming(result)
                ExportStep.Cancelled -> onClose()
            }
        }
    }

    /** Closes the dialog. A copy being written is finished first: it is never left half done. */
    internal fun close() {
        if (stage != Stage.Running) onClose()
    }

    /** What the dialog shows. */
    internal sealed interface Stage {
        /** What the copy is, before the user chooses a place. */
        data object Explaining : Stage

        /** A place is being chosen, or the copy written. */
        data object Running : Stage

        /** Whether [exists] may be replaced. */
        data class Confirming(
            val exists: ExportStep.Exists,
        ) : Stage

        /** How it ended, in [message]. */
        data class Finished(
            val message: String,
        ) : Stage
    }

    private companion object {
        val log = Logger("ui")
    }
}

/** The dialog of [export]: see [ExportDatabaseViewModel]. */
@Composable
internal fun ExportDatabaseDialog(export: ExportDatabaseViewModel) {
    val stage = export.stage
    AlertDialog(
        onDismissRequest = export::close,
        title = {
            Text(
                if (stage is ExportDatabaseViewModel.Stage.Confirming) {
                    "Replace ${stage.exists.name}?"
                } else {
                    EXPORT_DATABASE_TITLE
                },
            )
        },
        text = {
            Text(
                when (stage) {
                    ExportDatabaseViewModel.Stage.Explaining -> EXPORT_DATABASE_EXPLAINED
                    ExportDatabaseViewModel.Stage.Running -> EXPORT_DATABASE_RUNNING
                    is ExportDatabaseViewModel.Stage.Confirming ->
                        "${stage.exists.name} is there already. Replacing it with the new copy " +
                            "cannot be undone."
                    is ExportDatabaseViewModel.Stage.Finished -> stage.message
                },
            )
        },
        confirmButton = {
            when (stage) {
                ExportDatabaseViewModel.Stage.Explaining ->
                    TextButton(onClick = export::choose) { Text("Choose where…") }
                is ExportDatabaseViewModel.Stage.Confirming ->
                    TextButton(onClick = export::replace) { Text(REPLACE) }
                is ExportDatabaseViewModel.Stage.Finished ->
                    TextButton(onClick = export::close) { Text("Close") }
                ExportDatabaseViewModel.Stage.Running -> {}
            }
        },
        dismissButton = {
            if (stage == ExportDatabaseViewModel.Stage.Explaining ||
                stage is ExportDatabaseViewModel.Stage.Confirming
            ) {
                TextButton(onClick = export::close) { Text("Cancel") }
            }
        },
    )
}

/** The part of the Sync screen that offers "Export database". */
@Composable
internal fun ExportDatabaseSection(onExport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        Text(EXPORT_DATABASE_TITLE, style = MaterialTheme.typography.titleMedium)
        Text(
            "A copy of all your notes in one file, as a backup or for another computer. " +
                "Passwords and pairing tokens are not in it.",
        )
        OutlinedButton(onClick = onExport) { Text(EXPORT_DATABASE) }
    }
}
