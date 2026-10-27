package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.db.use
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.time.Duration

/** A fresh temporary directory for one test's database files, deleted again by [close]. */
internal class TempDatabaseDir : AutoCloseable {
    private val dir: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-sqlite-test-${Random.nextLong().toULong()}"

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
internal fun <T> withTempDir(block: (TempDatabaseDir) -> T): T = TempDatabaseDir().use(block)

/** Runs [block] with a driver for a new database with [NotesSchema], closing it afterwards. */
internal fun <T> withNotesDatabase(block: (NotesDatabase) -> T): T =
    withTempDir { dir ->
        openSqliteDriver(dir.path, NotesSchema).use { driver -> block(NotesDatabase(driver)) }
    }

/**
 * Threads for tests that use the driver from several threads at once. Blocking calls on it are
 * fine: the driver is synchronous, and that is what these tests exercise.
 */
@Suppress("InjectDispatcher") // Tests choose their threads directly.
internal val testThreads: CoroutineDispatcher = Dispatchers.Default

/** Blocks the calling thread, for example to hold a transaction open while another thread waits. */
internal fun blockFor(duration: Duration) = runBlocking { delay(duration) }

/**
 * Opens a plain connection to [path] without the driver, so without `busy_timeout`: a lock held by
 * another connection makes it fail at once with `SQLITE_BUSY`.
 */
internal fun openRawConnection(path: String): SQLiteConnection = BundledSQLiteDriver().open(path)

/** Returns the first column of the first row of [sql] as text. */
internal fun SqlDriver.queryText(sql: String): String? =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getString(0))
        },
        parameters = 0,
    ).value

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

/** The path of the file this driver has open. */
internal fun SqlDriver.databasePath(): String =
    checkNotNull(queryText("SELECT file FROM pragma_database_list WHERE name = 'main'"))

/** One table, `note(id, title)`. */
internal object NotesSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(null, "CREATE TABLE note(id INTEGER PRIMARY KEY, title TEXT NOT NULL)", 0)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

/**
 * A hand-written equivalent of the code SQLDelight generates for [NotesSchema]: a [TransacterImpl]
 * whose writes notify the listeners of the `note` table, and a listenable query.
 */
internal class NotesDatabase(
    val sqlDriver: SqlDriver,
) : TransacterImpl(sqlDriver) {
    fun insert(title: String) {
        driver.execute(INSERT, "INSERT INTO note(title) VALUES (?)", 1) { bindString(0, title) }
        notifyQueries(INSERT) { emit -> emit("note") }
    }

    fun titles(): Query<String> =
        Query(TITLES, arrayOf("note"), driver, "SELECT title FROM note ORDER BY id") { cursor ->
            checkNotNull(cursor.getString(0))
        }

    fun count(): Long = titles().executeAsList().size.toLong()

    private companion object {
        const val INSERT = 1
        const val TITLES = 2
    }
}
