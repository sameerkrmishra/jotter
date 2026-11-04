package io.github.sameerkrmishra.jotter.core.storage

import app.cash.sqldelight.db.SqlDriver
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.sqlite.checkDatabaseCopy
import io.github.sameerkrmishra.jotter.core.sqlite.openSqliteDriver
import io.github.sameerkrmishra.jotter.core.storage.db.JotterDatabase
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * The `PRAGMA application_id` of a Jotter database, the letters "JOTR", so that opening any other
 * SQLite file by mistake is refused instead of adding Jotter's tables to it.
 */
internal const val JOTTER_APPLICATION_ID = 0x4A4F5452

/**
 * One device's local database of notes: a SQLite file that the desktop app and the CLI may have
 * open at the same time. The composition root opens it once, hands [notes] to whoever needs it,
 * and closes it on exit.
 *
 * @property deviceId This installation's identity, created with the database and kept for good.
 * @property notes Creates, changes, lists, observes and searches the notes.
 * @property sync What `core:sync` reads and writes. Nothing else can use it (see [SyncStoreApi]).
 * @property backends The backends this device syncs with, and how their last sync went.
 * @property path The database file, as given to [open].
 */
@OptIn(SyncStoreApi::class)
@Suppress("LongParameterList") // Private: only open() calls it, once.
public class NoteStore private constructor(
    internal val driver: SqlDriver,
    internal val database: JotterDatabase,
    public val deviceId: DeviceId,
    public val notes: NoteService,
    @property:SyncStoreApi public val sync: SyncStore,
    public val backends: BackendStore,
    public val path: String,
) : AutoCloseable {
    /**
     * Closes the database. Waits for a write running on another thread to finish. The store and
     * its [notes] cannot be used afterwards, and observation flows must be cancelled before.
     */
    override fun close() {
        driver.close()
    }

    /**
     * Writes a consistent copy of the whole database to the new file [path], with SQLite's
     * `VACUUM INTO` (ADR-0002), and checks it: it must open and pass `PRAGMA integrity_check`. The
     * copy holds every table: notes, history, the device's id, backends and what sync has read,
     * so opening it elsewhere is a restored device (ADR-0014). It is one file, with no `-wal`.
     *
     * It only reads this database, in one read transaction, so it sees no half-done write, and
     * the CLI or another process may write meanwhile (WAL). Writes of this process wait until it
     * is done. Blocking: call it off the UI thread.
     *
     * @param path A file that does not exist, or is empty, on a local or removable disk. If this
     *   fails, a part of it may be left there: the caller removes it.
     * @throws IllegalStateException if the copy failed its check.
     * @throws RuntimeException the driver's exception if it cannot be written, such as a full
     *   disk; [storageProblemOf] tells which.
     */
    public fun exportTo(path: String) {
        driver.execute(null, "VACUUM INTO ?", 1) { bindString(0, path) }
        checkDatabaseCopy(path, JOTTER_APPLICATION_ID, SCHEMA_VERSION)
    }

    /** Opens a [NoteStore]. */
    public companion object {
        /** The schema version of the databases this version of Jotter writes. */
        public val SCHEMA_VERSION: Long get() = JotterDatabase.Schema.version

        /**
         * Opens the database file at [path], or creates it with a new [deviceId] named
         * [deviceName] if it does not exist. Blocking: call it off the UI thread.
         *
         * If the file cannot be opened for any other reason than a lock, this throws the SQLite
         * driver's exception (androidx `SQLiteException`, which is not part of this module's API)
         * and leaves nothing open. [storageProblemOf] tells what any of these failures means. None
         * of them changes or replaces the file: a damaged or foreign file is never recreated.
         *
         * @param path The database file, on a local file system (see core/sqlite).
         * @param deviceName A name for this device, such as "Laptop". Used only when the database
         *   is created.
         * @param queryContext Where observation flows run their queries, e.g. an IO dispatcher.
         * @param clock The wall clock that the hybrid logical clock follows.
         * @param ids Where new ids come from. Random, except in a simulation that must replay.
         * @throws StorageBusyException if another process kept the database locked.
         * @throws IllegalStateException if the file was written by a newer version of Jotter, or
         *   is a SQLite database that is not Jotter's, which is left untouched, or if upgrading it
         *   to this version failed and was rolled back.
         */
        public fun open(
            path: String,
            deviceName: String,
            queryContext: CoroutineContext,
            clock: Clock = Clock.System,
            ids: IdSource = RandomIds,
        ): NoteStore {
            val driver =
                reportingBusy {
                    openSqliteDriver(path, JotterDatabase.Schema, JOTTER_APPLICATION_ID)
                }
            var opened = false
            try {
                val database = JotterDatabase(driver)
                val deviceId = reportingBusy { readOrCreateDeviceId(database, deviceName, ids) }
                val notes = NoteService(database, deviceId, clock, queryContext, ids)
                val sync = SyncStore(database, deviceId, clock, queryContext, ids)
                val backends = BackendStore(database, queryContext)
                opened = true
                return NoteStore(driver, database, deviceId, notes, sync, backends, path)
            } finally {
                if (!opened) driver.close()
            }
        }

        // INSERT OR IGNORE: another process may create the row between the two reads.
        private fun readOrCreateDeviceId(
            database: JotterDatabase,
            deviceName: String,
            ids: IdSource,
        ): DeviceId {
            val queries = database.deviceQueries
            val stored =
                queries.selectDevice().executeAsOneOrNull()
                    ?: run {
                        queries.insertDeviceIfAbsent(
                            ids.newDeviceId().toString(),
                            deviceName.toStorableText(),
                        )
                        queries.selectDevice().executeAsOne()
                    }
            return DeviceId.parse(stored.id)
        }
    }
}

/** Where a [NoteStore] gets new ids. */
public interface IdSource {
    /** Returns a new id for a device. */
    public fun newDeviceId(): DeviceId

    /** Returns a new id for a note. */
    public fun newNoteId(): NoteId

    /** Returns a new id for a user revision. */
    public fun newRevisionId(): RevisionId
}

/** The ids of real use: [DeviceId.generate], [NoteId.generate] and [RevisionId.generate]. */
public object RandomIds : IdSource {
    override fun newDeviceId(): DeviceId = DeviceId.generate()

    override fun newNoteId(): NoteId = NoteId.generate()

    override fun newRevisionId(): RevisionId = RevisionId.generate()
}
