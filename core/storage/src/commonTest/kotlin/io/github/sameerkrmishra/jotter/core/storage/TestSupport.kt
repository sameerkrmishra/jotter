package io.github.sameerkrmishra.jotter.core.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.use
import io.github.sameerkrmishra.jotter.core.sqlite.openSqliteDriver
import io.github.sameerkrmishra.jotter.core.storage.db.JotterDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/** A fresh temporary directory for one test's database files, deleted again by [close]. */
internal class TempDatabaseDir : AutoCloseable {
    private val dir: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-storage-test-${Random.nextLong().toULong()}"

    init {
        FileSystem.SYSTEM.createDirectories(dir)
    }

    /** The path of the database file, which does not exist until a test opens it. */
    val path: String = (dir / "test.db").toString()

    /** Whether the database file with this [suffix] (`""`, `"-wal"`, `"-shm"`) exists. */
    fun exists(suffix: String = ""): Boolean = FileSystem.SYSTEM.exists("$path$suffix".toPath())

    override fun close() = FileSystem.SYSTEM.deleteRecursively(dir)
}

/** Runs [block] with a fresh [TempDatabaseDir] and deletes it afterwards. */
internal inline fun <T> withTempDir(block: (TempDatabaseDir) -> T): T = TempDatabaseDir().use(block)

/** Runs [block] with a new, empty database and its driver, closing it afterwards. */
internal inline fun <T> withDatabase(block: (JotterDatabase, SqlDriver) -> T): T =
    withTempDir { dir ->
        openSqliteDriver(dir.path, JotterDatabase.Schema).use { driver ->
            block(JotterDatabase(driver), driver)
        }
    }

/** Returns the first column of the first row of [sql] as a number. */
internal fun SqlDriver.queryLong(sql: String): Long? =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0))
        },
        parameters = 0,
    ).value

/** A wall clock that the test sets by hand. */
internal class FakeClock(
    var millis: Long = 1_000,
) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
}

/** Opens a [NoteStore] on the database file [path], named "Test device" if it is new. */
internal fun openStore(
    path: String,
    clock: Clock = FakeClock(),
    queryContext: CoroutineContext = EmptyCoroutineContext,
): NoteStore = NoteStore.open(path, "Test device", queryContext, clock)

/** Runs [block] with a [NoteStore] on a new database file, closing it afterwards. */
internal inline fun <T> withStore(
    clock: Clock = FakeClock(),
    queryContext: CoroutineContext = EmptyCoroutineContext,
    block: (NoteStore) -> T,
): T = withTempDir { dir -> openStore(dir.path, clock, queryContext).use(block) }

/**
 * Threads for tests that use the store from several threads at once. Blocking calls on it are
 * fine: the store is synchronous, and that is what these tests exercise.
 */
@Suppress("InjectDispatcher") // Tests choose their threads directly.
internal val testThreads: CoroutineDispatcher = Dispatchers.Default
