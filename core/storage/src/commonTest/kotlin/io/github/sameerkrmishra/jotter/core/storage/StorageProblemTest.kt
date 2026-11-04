package io.github.sameerkrmishra.jotter.core.storage

import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.sameerkrmishra.jotter.core.sqlite.DatabaseUpgradeException
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Each problem that opening or writing the notes can meet, from the exception it throws. The ones
 * that a test can make for real are made for real, and leave the file as it was.
 */
class StorageProblemTest {
    private fun sqlite(code: Int) = SQLiteException("Error code: $code, message: from SQLite")

    private fun openFailure(path: String): Throwable = assertFails { openStore(path).close() }

    @Test
    fun aDamagedFileIsDamagedAndUnchanged() =
        withTempDir { dir ->
            val garbage = "This is not a database, and Jotter must not turn it into one. ".repeat(8)
            FileSystem.SYSTEM.write(dir.path.toPath()) { writeUtf8(garbage) }
            assertEquals(StorageProblem.DAMAGED, storageProblemOf(openFailure(dir.path)))
            assertEquals(garbage, FileSystem.SYSTEM.read(dir.path.toPath()) { readUtf8() })
            assertEquals(false, dir.exists("-wal"))
        }

    @Test
    fun aNewerDatabaseIsNewer() =
        withTempDir { dir ->
            openStore(dir.path).close()
            val raw = BundledSQLiteDriver().open(dir.path)
            raw.execSQL("PRAGMA user_version = 999")
            raw.close()
            val before = dir.bytes()
            assertEquals(StorageProblem.NEWER_VERSION, storageProblemOf(openFailure(dir.path)))
            assertEquals(before, dir.bytes())
        }

    @Test
    fun anotherApplicationsDatabaseIsNotJotters() =
        withTempDir { dir ->
            BundledSQLiteDriver()
                .open(dir.path)
                .apply { execSQL("CREATE TABLE theirs(id INTEGER PRIMARY KEY)") }
                .close()
            assertEquals(StorageProblem.NOT_JOTTER, storageProblemOf(openFailure(dir.path)))
        }

    @Test
    fun aDirectoryCannotBeOpened() =
        withTempDir { dir ->
            FileSystem.SYSTEM.createDirectories(dir.path.toPath())
            assertEquals(StorageProblem.NO_ACCESS, storageProblemOf(openFailure(dir.path)))
        }

    @Test
    fun sqliteResultCodesNameTheProblem() {
        assertEquals(StorageProblem.DISK_FULL, storageProblemOf(sqlite(13)))
        assertEquals(StorageProblem.DAMAGED, storageProblemOf(sqlite(11)))
        assertEquals(StorageProblem.DAMAGED, storageProblemOf(sqlite(26)))
        assertEquals(StorageProblem.READ_ONLY, storageProblemOf(sqlite(8)))
        assertEquals(StorageProblem.READ_ONLY, storageProblemOf(sqlite(1032)))
        assertEquals(StorageProblem.NO_ACCESS, storageProblemOf(sqlite(14)))
        assertEquals(StorageProblem.NO_ACCESS, storageProblemOf(sqlite(3)))
        assertEquals(StorageProblem.DISK_ERROR, storageProblemOf(sqlite(778)))
        assertEquals(StorageProblem.LOCKED, storageProblemOf(sqlite(5)))
        assertEquals(StorageProblem.OTHER, storageProblemOf(sqlite(19)))
    }

    @Test
    fun theCauseThatCanBeHelpedWins() {
        assertEquals(
            StorageProblem.DISK_FULL,
            storageProblemOf(DatabaseUpgradeException(sqlite(13))),
        )
        assertEquals(
            StorageProblem.UPGRADE_FAILED,
            storageProblemOf(DatabaseUpgradeException(sqlite(1))),
        )
        assertEquals(
            StorageProblem.DISK_FULL,
            storageProblemOf(IllegalStateException("wrapped", sqlite(13))),
        )
    }

    @Test
    fun aLockIsLocked() {
        assertEquals(StorageProblem.LOCKED, storageProblemOf(StorageBusyException(sqlite(5))))
        assertEquals(
            StorageProblem.LOCKED,
            storageProblemOf(StorageBusyException(IllegalStateException())),
        )
    }

    @Test
    fun anythingElseIsOther() {
        assertEquals(StorageProblem.OTHER, storageProblemOf(IllegalStateException("bug")))
        assertEquals(StorageProblem.NO_ACCESS, storageProblemOf(SQLiteException("no code")))
    }
}
