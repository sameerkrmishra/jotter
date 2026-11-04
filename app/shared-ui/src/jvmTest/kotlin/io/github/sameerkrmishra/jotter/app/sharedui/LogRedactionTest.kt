@file:OptIn(ExperimentalPathApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.app.sync.AppInfo
import io.github.sameerkrmishra.jotter.app.sync.DebugReport
import io.github.sameerkrmishra.jotter.app.sync.Secret
import io.github.sameerkrmishra.jotter.core.logging.LogFiles
import io.github.sameerkrmishra.jotter.core.logging.LogLevel
import io.github.sameerkrmishra.jotter.core.logging.Logging
import io.github.sameerkrmishra.jotter.core.logging.RotatingFileSink
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L

/**
 * No log line and no debug log holds a note's text, title or tags, a password, a token, an
 * `Authorization` header, a URL's credentials or a file's contents (ADR-0020). Each of these is a
 * marker string, sent through the real sync paths at DEBUG, where the most is logged: a WebDAV
 * server with a wrong and a right password, the PC hub with a token before and after it is
 * unpaired, a URL with a password in it, a damaged change file that quotes a note, and a failure
 * the UI did not expect. The log must hold what happened, and none of the markers.
 */
@Suppress("InjectDispatcher") // The test stands in for a composition root.
class LogRedactionTest {
    private val folders: Path = createTempDirectory("jotter-redaction")
    private val logs = folders.resolve("logs")
    private val pc = SyncedDevice("pc", hubFolder = folders.resolve("hub"))
    private val laptop = SyncedDevice("laptop")
    private val dav = TinyDavServer()

    @AfterTest
    fun cleanUp() {
        Logging.uninstall()
        pc.close()
        laptop.close()
        dav.close()
        folders.deleteRecursively()
    }

    @Test
    fun noPrivateTextReachesTheLogOrTheDebugLog() =
        runBlocking {
            val sink = RotatingFileSink(logs.toOkioPath(), "test")
            Logging.install(sink, LogLevel.DEBUG)
            writeNotes()
            syncThroughWebDav()
            val token = syncThroughTheHub()
            refuseAUrlWithAPassword()
            readADamagedFile()
            reportUnexpected(IllegalStateException("Failed on note $BODY"))
            val debugLog =
                DebugReport(AppInfo("desktop", "test", "test"), LogFiles(logs.toOkioPath()))
                    .build(pc.manager)
            sink.close()

            val log = LogFiles(logs.toOkioPath()).recent("test", Int.MAX_VALUE)
            // What happened is there: a log without these lines would prove nothing.
            listOf(
                "DEBUG webdav: PUT ",
                "INFO sync: Sync with ",
                "INFO backends: Added backend ",
                "INFO hub: Paired device ",
                "INFO hub: Refused GET ",
                "not applied (CORRUPT)",
                "ERROR ui: Unexpected failure in the UI",
                "java.lang.IllegalStateException",
            ).forEach { assertContains(log, it) }
            assertContains(debugLog, "(webdav)")
            assertContains(debugLog, "Log: test")

            val markers = MARKERS + token + basic(PASSWORD) + basic(WRONG_PASSWORD)
            for (marker in markers) {
                assertFalse(marker in log, "The log holds $marker:\n$log")
                assertFalse(marker in debugLog, "The debug log holds $marker:\n$debugLog")
            }
        }

    private fun writeNotes() {
        val note = pc.store.notes.create(TITLE, "$BODY\n- [ ] item", setOf(TagName.parse(TAG)))
        pc.store.notes.edit(note.id, note.revisionId, "$TITLE 2", "$BODY\n- [x] item")
    }

    private suspend fun syncThroughWebDav() {
        dav.password = PASSWORD
        val refused = pc.manager.addWebDav(dav.url, "me", Secret(WRONG_PASSWORD))
        assertIs<AddResult.Refused>(refused)
        val added = pc.manager.addWebDav(dav.url, "me", Secret(PASSWORD), setUpNewStore = true)
        assertIs<AddResult.Added>(added)
        assertIs<AddResult.Added>(laptop.manager.addWebDav(dav.url, "me", Secret(PASSWORD)))
        repeat(2) {
            pc.manager.syncAll()
            laptop.manager.syncAll()
        }
        val titles =
            laptop.store.notes
                .list()
                .map { it.title }
        assertTrue("$TITLE 2" in titles)
    }

    /** Returns the token the hub gave, which must be in no log. */
    private suspend fun syncThroughTheHub(): String {
        val hub = checkNotNull(pc.hub)
        assertNull(hub.turnOn(hub.settings))
        waitFor { hub.state.value is HubState.Running }
        val address = (hub.state.value as HubState.Running).address
        val pairing = hub.pair("Laptop")
        assertIs<AddResult.Added>(laptop.manager.addPeer(address, Secret(pairing.token)))
        laptop.manager.syncAll()
        pc.manager.syncAll()
        // A token that is no longer valid: the hub refuses it, and both sides log that.
        hub.unpair(pairing.device.id)
        laptop.manager.syncAll()
        return pairing.token
    }

    private suspend fun refuseAUrlWithAPassword() {
        val url = dav.url.replace("http://", "http://me:$URL_PASSWORD@")
        assertIs<AddResult.Refused>(pc.manager.addWebDav(url, "me", Secret(PASSWORD)))
    }

    /** A change file in a local folder that is not valid JSON and quotes a note's text. */
    private suspend fun readADamagedFile() {
        val folder = folders.resolve("usb")
        Files.createDirectories(folder)
        assertIs<AddResult.Added>(
            pc.manager.addLocalFolder(folder.toString(), setUpNewStore = true),
        )
        pc.manager.syncAll()
        val device = DeviceId.generate()
        val json = """{"format":1,"device":"$device","revisions":[{"body":"$FILE_TEXT"""
        val gzip = ByteArrayOutputStream()
        GZIPOutputStream(gzip).use { it.write(json.encodeToByteArray()) }
        val name = "000000000001-${"a".repeat(HASH_DIGITS)}.json.gz"
        val file = folder.resolve("jotter/devices/$device/$name")
        Files.createDirectories(file.parent)
        Files.write(file, gzip.toByteArray())
        pc.manager.syncAll()
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun basic(password: String) =
        Base64.getEncoder().encodeToString("me:$password".toByteArray())

    private companion object {
        const val HASH_DIGITS = 32
        const val TITLE = "MARKER-TITLE-5d1e"
        const val BODY = "MARKER-BODY-8c2f"
        const val TAG = "markertag3a7b"
        const val PASSWORD = "MARKER-PASSWORD-91fe"
        const val WRONG_PASSWORD = "MARKER-WRONG-PASSWORD-04ab"
        const val URL_PASSWORD = "MARKER-URL-PASSWORD-77c0"
        const val FILE_TEXT = "MARKER-FILE-TEXT-e6d4"
        val MARKERS = listOf(TITLE, BODY, TAG, PASSWORD, WRONG_PASSWORD, URL_PASSWORD, FILE_TEXT)
    }
}
