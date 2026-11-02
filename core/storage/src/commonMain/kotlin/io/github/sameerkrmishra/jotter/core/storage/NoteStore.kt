package io.github.sameerkrmishra.jotter.core.storage

import app.cash.sqldelight.db.SqlDriver
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.RevisionId
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
 * @property notes Creates, changes, lists, observes and searches the notes.
 * @property sync What `core:sync` reads and writes. Nothing else can use it (see [SyncStoreApi]).
 */
@OptIn(SyncStoreApi::class)
public class NoteStore private constructor(
    internal val driver: SqlDriver,
    internal val database: JotterDatabase,
    public val notes: NoteService,
    @property:SyncStoreApi public val sync: SyncStore,
) : AutoCloseable {
    /** This installation's identity. Sync can replace it (ADR-0014), so it is read every time. */
    public val deviceId: DeviceId
        get() =
            DeviceId.parse(
                database.deviceQueries
                    .selectDevice()
                    .executeAsOne()
                    .id,
            )

    /**
     * Closes the database. Waits for a write running on another thread to finish. The store and
     * its [notes] cannot be used afterwards, and observation flows must be cancelled before.
     */
    override fun close() {
        driver.close()
    }

    /** Opens a [NoteStore]. */
    public companion object {
        /**
         * Opens the database file at [path], or creates it with a new [deviceId] named
         * [deviceName] if it does not exist. Blocking: call it off the UI thread.
         *
         * If the file cannot be opened for any other reason than a lock, this throws the SQLite
         * driver's exception (androidx `SQLiteException`, which is not part of this module's API)
         * and leaves nothing open.
         *
         * @param path The database file, on a local file system (see core/sqlite).
         * @param deviceName A name for this device, such as "Laptop". Used only when the database
         *   is created.
         * @param queryContext Where observation flows run their queries, e.g. an IO dispatcher.
         * @param clock The wall clock that the hybrid logical clock follows.
         * @param ids Where new ids come from. Random, except in a simulation that must replay.
         * @throws StorageBusyException if another process kept the database locked.
         * @throws IllegalStateException if the file was written by a newer version of Jotter, or
         *   is a SQLite database that is not Jotter's, which is left untouched.
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
                reportingBusy { createDeviceIfAbsent(database, deviceName, ids) }
                val notes = NoteService(database, clock, queryContext, ids)
                val sync = SyncStore(database, clock, queryContext, ids)
                opened = true
                return NoteStore(driver, database, notes, sync)
            } finally {
                if (!opened) driver.close()
            }
        }

        // INSERT OR IGNORE: another process may create the row between the two reads.
        private fun createDeviceIfAbsent(
            database: JotterDatabase,
            deviceName: String,
            ids: IdSource,
        ) {
            val queries = database.deviceQueries
            if (queries.selectDevice().executeAsOneOrNull() == null) {
                queries.insertDeviceIfAbsent(
                    ids.newDeviceId().toString(),
                    deviceName.toStorableText(),
                )
            }
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
