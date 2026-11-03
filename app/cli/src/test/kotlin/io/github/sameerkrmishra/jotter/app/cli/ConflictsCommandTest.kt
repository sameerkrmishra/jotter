package io.github.sameerkrmishra.jotter.app.cli

import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.DeviceInfo
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import io.github.sameerkrmishra.jotter.core.sync.SyncTarget
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `jotter conflicts` on states that core:sync made: two real databases syncing through a backend in
 * memory, and segments of a faulty device written by hand, as nothing else can make them.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ConflictsCommandTest {
    private val directory = createTempDirectory("jotter-cli-conflicts")
    private val database = directory.resolve("notes.db")
    private val here = NoteStore.open(database.toString(), "Here", EmptyCoroutineContext)
    private val there =
        NoteStore.open(directory.resolve("there.db").toString(), "There", EmptyCoroutineContext)
    private val blobs = InMemoryBlobStore()

    @AfterTest
    fun cleanUp() {
        here.close()
        there.close()
        directory.deleteRecursively()
    }

    private fun exchange() =
        runBlocking {
            repeat(ROUNDS) {
                listOf(here, there).forEach { store ->
                    SyncEngine(store, DeviceInfo("d", "test"), EmptyCoroutineContext)
                        .sync(SyncTarget("b", blobs))
                }
            }
        }

    private fun jotter(vararg args: String): String {
        val result = runJotter(args.toList(), database)
        assertEquals(0, result.statusCode, result.stderr)
        return result.stdout
    }

    private fun conflicted(): Note {
        val note = here.notes.create("Groceries", "eggs\nmilk")
        exchange()
        here.notes.edit(note.id, note.revisionId, "Groceries", "eggs\noat milk")
        there.notes.edit(note.id, note.revisionId, "Groceries", "eggs\nsoy milk")
        exchange()
        return note
    }

    /** A revision of a device that does not exist, as a faulty device would write it. */
    @Suppress("LongParameterList") // The fields of a forged revision, each with its default.
    private fun forge(
        noteId: NoteId,
        parent: RevisionId?,
        body: String,
        millis: Long = System.currentTimeMillis(),
        state: String = "ACTIVE",
        id: RevisionId = RevisionId.generate(),
    ) {
        val device = DeviceId.generate()
        val stamp = """{"physicalMillis":$millis,"counter":0,"device":"$device","revision":"$id"}"""
        val parents = listOfNotNull(parent).joinToString(",") { "\"$it\"" }
        val revision =
            """{"id":"$id","note":"$noteId","parents":[$parents],"physicalMillis":$millis,""" +
                """"counter":0,"device":"$device","merge":false,"snapshot":{"title":"",""" +
                """"body":"$body","tags":[],"pinned":false,"pinnedStamp":$stamp,""" +
                """"state":"$state","stateStamp":$stamp}}"""
        val json = """{"format":1,"device":"$device","number":1,"revisions":[$revision]}"""
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(json.encodeToByteArray())
                .take(HASH_BYTES)
                .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        val gzip = ByteArrayOutputStream()
        GZIPOutputStream(gzip).use { it.write(json.encodeToByteArray()) }
        runBlocking {
            blobs.writeNew("jotter/devices/$device/000000000001-$hash.json.gz", gzip.toByteArray())
        }
    }

    @Test
    fun nothingToLookAtPrintsNothing() {
        here.notes.create("", "a note")
        assertEquals("", jotter("conflicts"))
    }

    @Test
    fun aConflictIsListedAndShowsBothVersions() {
        val note = conflicted()

        assertEquals("${note.id}\tconflict\tGroceries\n", jotter("conflicts"))

        val shown = here.notes.get(note.id)?.body
        val other = setOf("eggs\noat milk", "eggs\nsoy milk").single { it != shown }
        assertEquals(
            "kind: conflict\nversion: shown\ntitle: Groceries\n\n$shown\n\n" +
                "kind: conflict\nversion: other\ntitle: Groceries\n\n$other\n",
            jotter("conflicts", note.id.toString().take(PREFIX)),
        )
    }

    @Test
    fun waitingEditsAreListedWithTheShownVersion() {
        val note = conflicted()
        forge(note.id, note.revisionId, "a third device")
        val other = here.notes.create("", "plans")
        // Older than the note, so that the note shows its own text and this one waits.
        forge(other.id, RevisionId.generate(), "after a lost edit", millis = 1_000)
        exchange()

        val kinds = jotter("conflicts").lines().dropLast(1).map { it.split('\t')[1] }
        assertEquals(listOf("conflict", "waiting", "waiting-for-conflict"), kinds.sorted())
        assertEquals(
            "kind: waiting\nversion: shown\ntitle: \n\nplans\n\n" +
                "kind: waiting\nversion: waiting\ntitle: \n\nafter a lost edit\n",
            jotter("conflicts", other.id.toString()),
        )
        val queued = jotter("conflicts", note.id.toString())
        assertEquals(true, queued.contains("version: waiting\ntitle: \n\na third device"))
    }

    @Test
    fun textSetAsideIsListedWithItsReason() {
        val note = here.notes.create("", "the first words")
        forge(note.id, note.revisionId, "from a bad clock", millis = 1)
        forge(note.id, note.revisionId, "damaged", state = "SHREDDED")
        forge(note.id, null, "twin", id = note.revisionId)
        exchange()

        val lines = jotter("conflicts").lines().dropLast(1)
        assertEquals(List(lines.size) { "set-aside" }, lines.map { it.split('\t')[1] })
        val shown = jotter("conflicts", note.id.toString())
        listOf("bad-history", "damaged", "collision").forEach {
            assertEquals(true, shown.contains("version: $it\n"), it)
        }
        // The note is out of the list, as its first revision no longer counts.
        assertEquals("", jotter("list"))
    }

    private companion object {
        const val ROUNDS = 3
        const val PREFIX = 8
        const val HASH_BYTES = 16
    }
}
