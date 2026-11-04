@file:OptIn(ExperimentalTestApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.sameerkrmishra.jotter.app.sync.DatabaseExport
import io.github.sameerkrmishra.jotter.app.sync.EXPORT_DATABASE_EXPLAINED
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toOkioPath
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 10_000L

/** "Export database": what it says, the questions it asks, and the copy it writes. */
@OptIn(ExperimentalPathApi::class)
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class ExportDatabaseScreenTest {
    private val device = SyncedDevice("here")
    private val directory = createTempDirectory("jotter-export-ui")
    private val file = directory.resolve("copy.db")
    private val export = DatabaseExport(device.store, Dispatchers.IO)

    /** Where the app's own dialog would say: the file, or null as if the user cancelled. */
    private var chosen: java.nio.file.Path? = file

    @AfterTest
    fun close() {
        device.close()
        directory.deleteRecursively()
    }

    private val place =
        DatabaseExportPlace {
            val target = chosen ?: return@DatabaseExportPlace ExportStep.Cancelled
            exportStep(export, target.toOkioPath())
        }

    private fun app(
        place: DatabaseExportPlace = this.place,
        block: ComposeUiTest.(AppViewModel) -> Unit,
    ) = runComposeUiTest {
        lateinit var app: AppViewModel
        setContent {
            val scope = rememberCoroutineScope()
            app =
                remember {
                    AppViewModel(
                        device.store.notes,
                        ConflictResolver(device.store),
                        scope,
                        Dispatchers.IO,
                        device.controls,
                        databaseExport = place,
                    )
                }
            JotterApp(app)
        }
        waitForIdle()
        block(app)
    }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun ComposeUiTest.openFromSyncScreen() {
        onNodeWithText(BACKENDS_TITLE).performClick()
        awaitText("Sync now")
        onNodeWithText(EXPORT_DATABASE).performScrollTo()
        onNodeWithText("Passwords and pairing tokens are not in it.", substring = true)
            .assertExists()
        onNodeWithText(EXPORT_DATABASE).performClick()
        awaitText(EXPORT_DATABASE_EXPLAINED)
    }

    private fun notesIn(copy: java.nio.file.Path) =
        NoteStore.open(copy.toString(), "check", EmptyCoroutineContext).use { copied ->
            copied.notes.list().map { it.body }
        }

    @Test
    fun theSyncScreenExportsACopyAndSaysSecretsAreNotInIt() =
        app { app ->
            device.store.notes.create("", "milk")
            openFromSyncScreen()
            assertFalse(file.exists())
            onNodeWithText("Choose where…").performClick()
            awaitText("Exported your notes to $file")
            awaitText("Passwords and pairing tokens are not in it")
            assertEquals(listOf("milk"), notesIn(file))
            onNodeWithText("Close").performClick()
            waitForIdle()
            assertNull(app.exportView)
        }

    @Test
    fun anExistingFileIsReplacedOnlyAfterAsking() =
        app {
            device.store.notes.create("", "milk")
            file.writeText("keep me")
            openFromSyncScreen()
            onNodeWithText("Choose where…").performClick()
            awaitText("Replace copy.db?")
            assertEquals("keep me", file.readText())
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertEquals("keep me", file.readText())

            onNodeWithText(EXPORT_DATABASE).performClick()
            onNodeWithText("Choose where…").performClick()
            awaitText("Replace copy.db?")
            onNodeWithText(REPLACE).performClick()
            awaitText("Exported your notes to $file")
            assertEquals(listOf("milk"), notesIn(file))
            assertEquals(listOf(file), Files.list(directory).use { it.toList() })
        }

    @Test
    fun aCancelledChoiceClosesTheDialogAndWritesNothing() =
        app { app ->
            chosen = null
            runOnIdle { app.openDatabaseExport() }
            awaitText(EXPORT_DATABASE_EXPLAINED)
            onNodeWithText("Choose where…").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { app.exportView == null }
            assertFalse(file.exists())
        }

    @Test
    fun theMenuOpensItOverTheNoteList() =
        app { app ->
            assertTrue(app.exportsDatabase)
            runOnIdle { app.openDatabaseExport() }
            awaitText(EXPORT_DATABASE_EXPLAINED)
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertNull(app.exportView)
            assertFalse(file.exists())
        }

    @Test
    fun aFailureIsSaidInPlainWords() =
        app(place = { throw IOException("No space left on device") }) { app ->
            runOnIdle { app.openDatabaseExport() }
            onNodeWithText("Choose where…").performClick()
            awaitText(EXPORT_DATABASE_FAILED)
            assertTrue(
                onAllNodesWithText("No space", substring = true).fetchSemanticsNodes().isEmpty(),
            )
        }

    @Test
    fun aMissingFolderIsSaidByTheExport() =
        app { app ->
            chosen = directory.resolve("missing").resolve("copy.db")
            runOnIdle { app.openDatabaseExport() }
            onNodeWithText("Choose where…").performClick()
            awaitText("does not exist. Choose another place for the copy.")
        }
}
