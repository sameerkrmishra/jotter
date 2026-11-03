package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.DeviceInfo
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import io.github.sameerkrmishra.jotter.core.sync.SyncTarget
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.EmptyCoroutineContext

private const val ROUNDS = 3

/**
 * Real devices that sync through a real backend in memory, to make the states the UI shows with
 * core:sync itself: the app's store and [there], another device with a database of its own.
 * Nothing of the sync engine is mocked. To make a file arrive late, [hide] keeps it out of the
 * listing that the app's device sees, as a slow backend would.
 */
class SyncWorld : AutoCloseable {
    val blobs = InMemoryBlobStore()
    private val hidden = mutableSetOf<String>()
    private val database = TestDatabase()

    /** The other device. */
    val there: NoteStore = database.open(EmptyCoroutineContext)

    /** What the app's device sees of the backend: everything but [hidden] files. */
    private val seenHere =
        object : BlobStore by blobs {
            override suspend fun list(prefix: String): List<BlobInfo> =
                blobs.list(prefix).filter { it.path !in hidden }
        }

    /** Runs one sync cycle of [store]: the app's device sees what [hide] left. */
    suspend fun sync(store: NoteStore) {
        val view = if (store === there) blobs else seenHere
        SyncEngine(store, DeviceInfo("device", "test"), EmptyCoroutineContext)
            .sync(SyncTarget("backend", view))
    }

    /** Syncs both devices until they have what the other has. */
    suspend fun exchange(here: NoteStore) {
        repeat(ROUNDS) {
            sync(here)
            sync(there)
        }
    }

    /** Hides from the app's device every file that [block] adds to the backend. */
    suspend fun hide(block: suspend () -> Unit) {
        val before = blobs.list("").map { it.path }.toSet()
        block()
        hidden += blobs.list("").map { it.path } - before
    }

    /**
     * Makes a conflict on a note both devices have: each changes its one line, so neither change
     * can be merged. Returns the note as the app's device first had it.
     */
    suspend fun conflict(
        here: NoteStore,
        title: String = "Groceries",
        line: String = "milk",
        mine: String = "oat milk",
        theirs: String = "soy milk",
    ): Note {
        val note = here.notes.create(title, "eggs\n$line\nbread")
        exchange(here)
        here.notes.edit(note.id, note.revisionId, title, "eggs\n$mine\nbread")
        there.notes.edit(note.id, note.revisionId, title, "eggs\n$theirs\nbread")
        exchange(here)
        return note
    }

    /**
     * Makes an edit from the other device wait on the app's device, as the edit before it is
     * missing there, while the app's device made its own edit. Returns the note.
     */
    suspend fun waiting(
        here: NoteStore,
        theirs: String = "their second edit",
    ): Note {
        val note = here.notes.create("Plans", "start")
        exchange(here)
        val first = there.notes.edit(note.id, note.revisionId, "Plans", "their first edit")
        hide { sync(there) }
        there.notes.edit(note.id, first.revisionId, "Plans", theirs)
        sync(there)
        here.notes.edit(note.id, note.revisionId, "Plans", "my edit")
        sync(here)
        return note
    }

    /**
     * Writes a segment of a device that does not exist, holding one user revision of [noteId] on
     * [parent], as a faulty device would. [state] that is not a state makes it unreadable, and a
     * [millis] below the parent's breaks the note's history. [id] can repeat a revision's id.
     */
    @Suppress("LongParameterList") // The fields of a forged revision, each with its default.
    suspend fun forge(
        noteId: NoteId,
        parent: RevisionId?,
        title: String,
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
                """"counter":0,"device":"$device","merge":false,"snapshot":{"title":"$title",""" +
                """"body":"$body","tags":[],"pinned":false,"pinnedStamp":$stamp,""" +
                """"state":"$state","stateStamp":$stamp}}"""
        val json = """{"format":1,"device":"$device","number":1,"revisions":[$revision]}"""
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(json.encodeToByteArray())
                .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
                .take(HASH_DIGITS)
        val gzip = ByteArrayOutputStream()
        GZIPOutputStream(gzip).use { it.write(json.encodeToByteArray()) }
        blobs.writeNew("jotter/devices/$device/000000000001-$hash.json.gz", gzip.toByteArray())
    }

    override fun close() {
        there.close()
        database.delete()
    }

    private companion object {
        const val HASH_DIGITS = 32
    }
}
