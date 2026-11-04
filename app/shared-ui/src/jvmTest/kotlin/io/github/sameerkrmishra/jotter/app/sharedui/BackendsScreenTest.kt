@file:OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.core.storage.BackendRecord
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val TIMEOUT_MS = 10_000L

/** The backend screen on a real store, with real folders, the real scheduler and dispatchers. */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class BackendsScreenTest {
    private val device = SyncedDevice("here")
    private val folders: Path = createTempDirectory("jotter-ui-folders")
    private val stick: Path = folders.resolve("stick").createDirectories()

    @AfterTest
    fun cleanUp() {
        device.close()
        folders.deleteRecursively()
    }

    private fun app(block: ComposeUiTest.() -> Unit) =
        runComposeUiTest {
            device.start()
            setContent {
                val scope = rememberCoroutineScope()
                JotterApp(
                    remember {
                        AppViewModel(
                            device.store.notes,
                            ConflictResolver(device.store),
                            scope,
                            Dispatchers.IO,
                            device.controls,
                        )
                    },
                )
            }
            onNodeWithText(BACKENDS_TITLE).performClick()
            block()
        }

    private fun addStick(): String =
        runBlocking {
            val added = device.manager.addLocalFolder(stick.toString(), setUpNewStore = true)
            assertIs<AddResult.Added>(added).backend.id
        }

    private fun ComposeUiTest.awaitText(
        text: String,
        substring: Boolean = false,
    ) = waitUntil(timeoutMillis = TIMEOUT_MS) {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun ComposeUiTest.awaitNoText(text: String) =
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()
        }

    private fun ComposeUiTest.clickInDialog(text: String) =
        onNode(hasText(text) and hasAnyAncestor(isDialog())).performClick()

    private fun ComposeUiTest.typeFolder(path: Any) {
        onNodeWithTag(FOLDER_FIELD).performTextInput(path.toString())
        onNodeWithText("Add folder").performClick()
    }

    @Test
    fun aNewFolderIsSetUpOnlyAfterTheUserAgrees() =
        app {
            awaitText(NO_BACKENDS)
            typeFolder(stick)
            awaitText(SET_UP_TITLE)
            awaitText(setUpExplanation(stick.toString()))
            clickInDialog("Cancel")
            awaitNoText(SET_UP_TITLE)
            assertFalse(stick.resolve(".jotter-store").exists())
            assertEquals(emptyList(), runBlocking { device.manager.list() })

            onNodeWithText("Add folder").performClick()
            awaitText(SET_UP_TITLE)
            clickInDialog("Set up and add")
            awaitText("Added stick. It syncs now.")
            assertTrue(stick.resolve(".jotter-store").exists())
            awaitText("Reachable.")
            awaitText("Last synced just now.")
            awaitNoText(NO_BACKENDS)
        }

    @Test
    fun aFolderAnotherDeviceSetUpIsAddedWithoutAsking() =
        app {
            addStick()
            runBlocking {
                device.manager.remove(
                    device.manager
                        .list()
                        .single()
                        .id,
                )
            }
            typeFolder(stick)
            awaitText("Added stick. It syncs now.")
            assertTrue(onAllNodesWithText(SET_UP_TITLE).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun aMissingOrAddedFolderIsRefusedWithTheReason() =
        app {
            typeFolder(folders.resolve("gone"))
            awaitText("There is no folder at ${folders.resolve("gone")}.")
            onNodeWithText("Dismiss").performClick()
            awaitNoText("There is no folder")
            addStick()
            onNodeWithTag(FOLDER_FIELD).performTextReplacement(stick.toString())
            onNodeWithText("Add folder").performClick()
            awaitText("$stick is already a backend.")
        }

    @Test
    fun anUnpluggedFolderShowsAsNotFoundBesideItsLastSync() =
        app {
            addStick()
            runBlocking { device.manager.syncAll() }
            stick.deleteRecursively()
            onNodeWithText("Back").performClick()
            onNodeWithText(BACKENDS_TITLE).performClick()
            awaitText("Not found ($stick", substring = true)
            awaitText("Last synced just now.")
            onNodeWithText("Sync now").performClick()
            awaitText("The last try, just now, did not sync: Not found", substring = true)
            awaitText("Last synced just now.")
            assertTrue(onAllNodesWithText("Reachable.").fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun anEmptyMountPointShowsAsNotAStore() =
        app {
            addStick()
            stick.deleteRecursively()
            stick.createDirectories()
            onNodeWithText("Sync now").performClick()
            awaitText("is not marked as a Jotter store", substring = true)
            assertEquals(emptyList(), stick.listDirectoryEntries())
        }

    @Test
    fun removingSaysWhereTheFilesStay() =
        app {
            val id = addStick()
            runBlocking { device.manager.syncAll() }
            val files = stick.resolve("jotter/devices").listDirectoryEntries()
            onNode(hasText("Remove") and hasAnyAncestor(hasTestTag(backendTag(id)))).performClick()
            awaitText(REMOVE_TITLE)
            awaitText("Its files stay in $stick", substring = true)
            clickInDialog("Cancel")
            awaitNoText(REMOVE_TITLE)
            onNode(hasText("Remove") and hasAnyAncestor(hasTestTag(backendTag(id)))).performClick()
            clickInDialog("Remove")
            awaitText("Removed stick. Its files stay in $stick.")
            awaitText(NO_BACKENDS)
            assertEquals(files, stick.resolve("jotter/devices").listDirectoryEntries())
            assertEquals(emptyList(), runBlocking { device.manager.list() })
        }

    @Test
    fun issuesOfTheLastSyncAreListed() =
        app {
            addStick()
            val device = "0b5ab3c8-6c43-4bd4-9c1b-3f0d2a7c9e11"
            val folder = stick.resolve("jotter/devices/$device").createDirectories()
            Files.write(
                folder.resolve("000000000001-${"0".repeat(HASH_DIGITS)}.json.gz"),
                byteArrayOf(1),
            )
            onNodeWithText("Sync now").performClick()
            awaitText("• File 1 of device 0b5ab3c8 is damaged", substring = true)
            awaitText("Last synced just now.")
        }

    @Test
    fun aDatabaseFailureIsShownNotThrown() =
        app {
            BundledSQLiteDriver().open(device.file).use {
                it.execSQL(
                    "CREATE TRIGGER fail BEFORE INSERT ON backend " +
                        "BEGIN SELECT RAISE(ABORT, 'disk full'); END",
                )
            }
            typeFolder(stick)
            clickInDialog("Set up and add")
            awaitText("That did not work:", substring = true)
            assertEquals(emptyList(), runBlocking { device.manager.list() })
        }

    @Test
    fun aMissingPasswordIsSaidInItsOwnWordsWithTheWayToEnterIt() =
        app {
            // A database restored on this computer: the backend is there, its password is not.
            device.store.backends.add(
                BackendRecord(
                    "dav",
                    "webdav",
                    "My server",
                    """{"url":"https://dav.example.org/","username":"me"}""",
                    Instant.fromEpochMilliseconds(0),
                ),
            )
            onNodeWithText("Back").performClick()
            onNodeWithText(BACKENDS_TITLE).performClick()
            awaitText("Jotter could not read this backend's password", substring = true)
            awaitText("enter the password again.", substring = true)
            // Nothing on the card speaks of the network, or ends with two periods.
            listOf("Cannot be reached", "network", "..").forEach { words ->
                val onCard = hasText(words, substring = true) and hasAnyAncestor(hasTestTag(backendTag("dav")))
                assertTrue(onAllNodes(onCard).fetchSemanticsNodes().isEmpty(), words)
            }
            onNodeWithText(NEW_PASSWORD).assertExists()
        }

    @Test
    fun backReturnsToTheNotes() =
        app {
            awaitText(NO_BACKENDS)
            onNodeWithText("Back").performClick()
            awaitText("Nothing here.")
        }

    @Test
    fun agoIsInPlainWords() {
        val now = Instant.fromEpochMilliseconds(1_000_000_000)
        assertEquals("just now", ago(now - 59.seconds, now))
        assertEquals("1 minute ago", ago(now - 1.minutes, now))
        assertEquals("59 minutes ago", ago(now - 59.minutes, now))
        assertEquals("1 hour ago", ago(now - 1.hours, now))
        assertEquals("23 hours ago", ago(now - 23.hours, now))
        assertEquals("1 day ago", ago(now - 1.days, now))
        assertEquals("3 days ago", ago(now - 3.days, now))
    }

    private companion object {
        const val HASH_DIGITS = 32
    }
}
