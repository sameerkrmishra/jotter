package io.github.sameerkrmishra.jotter.app.cli

import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
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
import kotlin.test.assertTrue

/** `jotter sync` and `jotter backends`, with real databases and a real folder. */
@OptIn(ExperimentalPathApi::class)
class SyncCommandsTest {
    private val directory = createTempDirectory("jotter-cli-sync")

    // Apart from the folders: a backend may not be in the database's folder.
    private val here = directory.resolve("here/notes.db")
    private val there = directory.resolve("there/notes.db")
    private val stick = directory.resolve("stick").createDirectories()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun jotter(
        database: Path,
        vararg args: String,
    ) = runJotter(args.toList(), database)

    private fun ok(
        database: Path,
        vararg args: String,
    ): String {
        val result = jotter(database, *args)
        assertEquals(0, result.statusCode, result.stderr)
        return result.stdout
    }

    @Test
    fun aNewFolderIsSetUpOnlyWithNew() {
        val refused = jotter(here, "backends", "add", stick.toString())
        assertEquals(1, refused.statusCode)
        assertTrue(refused.stderr.contains("is not a Jotter store yet"), refused.stderr)
        assertTrue(refused.stderr.contains("jotter backends add --new $stick"), refused.stderr)
        assertEquals("", refused.stdout)
        assertFalse(stick.resolve(".jotter-store").exists())
        assertEquals("", ok(here, "backends"))

        val id = ok(here, "backends", "add", "--new", stick.toString()).trim()
        assertTrue(stick.resolve(".jotter-store").exists())
        assertEquals("$id\tstick\t$stick\tnever\tnever\n", ok(here, "backends"))

        val again = jotter(here, "backends", "add", stick.toString())
        assertEquals("jotter: $stick is already the backend $id\n", again.stderr)
    }

    @Test
    fun twoDatabasesSyncThroughAFolder() {
        ok(here, "backends", "add", "--new", "--name", "USB stick", stick.toString())
        ok(there, "backends", "add", stick.toString())
        val note = ok(here, "add", "buy milk").trim()

        val sent = ok(here, "sync").split("\t")
        assertEquals(
            listOf("USB stick", "synced", "Synced. Received 0 changes, sent 1 file.\n"),
            sent.drop(1),
        )
        assertTrue(ok(there, "sync").contains("Received 1 change"))
        assertTrue(ok(there, "show", note).endsWith("\nbuy milk\n"))

        val line = ok(here, "backends").trim().split("\t")
        assertEquals(listOf("USB stick", stick.toString()), line.subList(1, 3))
        assertTrue(line[3].matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""")), line[3])
        assertEquals("synced", line[4])
    }

    @Test
    fun anUnpluggedFolderFailsTheSyncAndShowsInTheStatus() {
        val id = ok(here, "backends", "add", "--new", stick.toString()).trim()
        ok(here, "sync")
        stick.deleteRecursively()

        val failed = jotter(here, "sync")
        assertEquals(1, failed.statusCode)
        assertTrue(failed.stdout.startsWith("$id\tstick\tunreachable\tNot found"), failed.stdout)
        assertEquals("jotter: 1 of 1 backends did not sync\n", failed.stderr)

        val status = ok(here, "backends", "status", id.take(4)).lines()
        assertEquals("id: $id", status[0])
        assertEquals("reachable: no", status[3])
        assertTrue(status[4].startsWith("state: Not found"), status[4])
        assertTrue(status[5].matches(Regex("last-success: \\d{4}-.*Z")), status[5])
        assertTrue(status[6].endsWith(" unreachable"), status[6])
        assertTrue(status[7].startsWith("detail: Not found"), status[7])
    }

    @Test
    fun statusListsEveryBackendAndItsIssues() {
        val id = ok(here, "backends", "add", "--new", stick.toString()).trim()
        assertEquals(
            listOf(
                "id: $id",
                "name: stick",
                "location: $stick",
                "reachable: yes",
                "state: Reachable.",
                "last-success: never",
                "last-attempt: never",
                "",
            ),
            ok(here, "backends", "status").lines(),
        )
        // A file from a newer version of Jotter, which this one leaves unread and reports.
        val device = "0b5ab3c8-6c43-4bd4-9c1b-3f0d2a7c9e11"
        val folder = stick.resolve("jotter/devices/$device").createDirectories()
        Files.write(folder.resolve("000000000001-${"0".repeat(32)}.json.gz"), byteArrayOf(1, 2, 3))
        val synced = jotter(here, "sync")
        assertEquals(0, synced.statusCode, synced.stderr)
        assertTrue(
            synced.stderr.startsWith("issue\t$id\tFile 1 of device 0b5ab3c8 is damaged"),
            synced.stderr,
        )
        assertTrue(ok(here, "backends", "status").contains("\nissue: File 1 of device 0b5ab3c8"))
    }

    @Test
    fun removingLeavesTheFilesAndForgetsTheBackend() {
        val id = ok(here, "backends", "add", "--new", stick.toString()).trim()
        ok(here, "add", "buy milk")
        ok(here, "sync")
        val files = stick.resolve("jotter/devices").listDirectoryEntries()

        val removed = ok(here, "backends", "remove", id)
        assertTrue(removed.startsWith("Removed stick ($id). Its files stay in $stick"), removed)
        assertEquals("", ok(here, "backends"))
        assertEquals(files, stick.resolve("jotter/devices").listDirectoryEntries())

        val gone = jotter(here, "backends", "remove", id)
        assertEquals("jotter: no backend with id $id\n", gone.stderr)
    }

    @Test
    fun keepingTheSecretOfAFolderSaysNothingMore() {
        val id = ok(here, "backends", "add", "--new", stick.toString()).trim()
        val removed = ok(here, "backends", "remove", "--keep-secret", id)
        assertEquals(1, removed.lines().count { it.isNotEmpty() }, removed)
        assertEquals("", ok(here, "backends"))
    }

    @Test
    fun anAmbiguousIdIsRefused() {
        val other = directory.resolve("other").createDirectories()
        ok(here, "backends", "add", "--new", stick.toString())
        ok(here, "backends", "add", "--new", other.toString())
        val result = jotter(here, "backends", "status", "")
        assertEquals(1, result.statusCode)
        assertEquals("jotter: no backend with id \n", result.stderr)
        // Ids are random, so a one-character prefix is shared only sometimes. A whole id never is.
        val ids =
            ok(
                here,
                "backends",
            ).lines().filter { it.isNotEmpty() }.map { it.substringBefore('\t') }
        val shared = ids[0].commonPrefixWith(ids[1])
        if (shared.isNotEmpty()) {
            val ambiguous = jotter(here, "backends", "remove", shared)
            assertTrue(
                ambiguous.stderr.startsWith("jotter: more than one backend"),
                ambiguous.stderr,
            )
        }
    }

    @Test
    fun theDatabaseFolderCannotBeABackend() {
        ok(here, "list")
        val result = jotter(here, "backends", "add", "--new", here.parent.toString())
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("holds this device's notes database"), result.stderr)
        assertFalse(here.parent.resolve(".jotter-store").exists())
    }

    @Test
    fun syncWithoutBackendsFails() {
        val result = jotter(here, "sync")
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.startsWith("jotter: there is no backend"), result.stderr)
    }

    @Test
    fun aMissingOrUnusableFolderIsRefused() {
        val missing = jotter(here, "backends", "add", directory.resolve("gone").toString())
        assertEquals(
            "jotter: There is no folder at ${directory.resolve("gone")}.\n",
            missing.stderr,
        )
        val file = Files.writeString(directory.resolve("file"), "x")
        val notAFolder = jotter(here, "backends", "add", "--new", file.toString())
        assertEquals(1, notAFolder.statusCode)
        assertTrue(notAFolder.stderr.startsWith("jotter: Cannot "), notAFolder.stderr)
    }

    @Test
    fun aRelativePathIsTakenFromTheCurrentDirectory() {
        val relative = Path.of("").toAbsolutePath().relativize(stick)
        val id = ok(here, "backends", "add", "--new", relative.toString()).trim()
        NoteStore.open(here.toString(), "x", EmptyCoroutineContext).use { store ->
            assertEquals(
                id,
                store.backends
                    .list()
                    .single()
                    .id,
            )
            assertTrue(
                store.backends
                    .list()
                    .single()
                    .settings
                    .contains(stick.toString()),
            )
        }
    }
}
