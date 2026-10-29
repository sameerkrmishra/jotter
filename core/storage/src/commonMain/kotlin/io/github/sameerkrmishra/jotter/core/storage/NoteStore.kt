package io.github.sameerkrmishra.jotter.core.storage

import app.cash.sqldelight.db.SqlDriver
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.sqlite.openSqliteDriver
import io.github.sameerkrmishra.jotter.core.storage.db.JotterDatabase
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * One device's local database of notes: a SQLite file that the desktop app and the CLI may have
 * open at the same time. The composition root opens it once, hands [notes] to whoever needs it,
 * and closes it on exit.
 *
 * @property deviceId This installation's identity, created with the database and kept for good.
 * @property notes Creates, changes, lists, observes and searches the notes.
 */
public class NoteStore private constructor(
    internal val driver: SqlDriver,
    internal val database: JotterDatabase,
    public val deviceId: DeviceId,
    public val notes: NoteService,
) : AutoCloseable {
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
         * If the file cannot be opened, or is a SQLite database that is not Jotter's, this throws
         * the SQLite driver's exception (androidx `SQLiteException`, which is not part of this
         * module's API) and leaves nothing open.
         *
         * @param path The database file, on a local file system (see core/sqlite).
         * @param deviceName A name for this device, such as "Laptop". Used only when the database
         *   is created.
         * @param queryContext Where observation flows run their queries, e.g. an IO dispatcher.
         * @param clock The wall clock that the hybrid logical clock follows.
         * @throws IllegalStateException if the file was written by a newer version of Jotter.
         */
        public fun open(
            path: String,
            deviceName: String,
            queryContext: CoroutineContext,
            clock: Clock = Clock.System,
        ): NoteStore {
            val driver = openSqliteDriver(path, JotterDatabase.Schema)
            var opened = false
            try {
                val database = JotterDatabase(driver)
                val deviceId = readOrCreateDeviceId(database, deviceName)
                val notes = NoteService(database, deviceId, clock, queryContext)
                opened = true
                return NoteStore(driver, database, deviceId, notes)
            } finally {
                if (!opened) driver.close()
            }
        }

        // INSERT OR IGNORE: another process may create the row between the two reads.
        private fun readOrCreateDeviceId(
            database: JotterDatabase,
            deviceName: String,
        ): DeviceId {
            val queries = database.deviceQueries
            val stored =
                queries.selectDevice().executeAsOneOrNull()
                    ?: run {
                        queries.insertDeviceIfAbsent(
                            DeviceId.generate().toString(),
                            deviceName.toStorableText(),
                        )
                        queries.selectDevice().executeAsOne()
                    }
            return DeviceId.parse(stored.id)
        }
    }
}
