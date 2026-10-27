package io.github.sameerkrmishra.jotter.core.sqlite

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Two processes write to one database file at the same time, as the desktop app and the CLI can.
 * Proves that WAL, `busy_timeout` and `BEGIN IMMEDIATE` make the second writer wait instead of
 * failing with `SQLITE_BUSY`.
 */
class TwoProcessTest {
    @Test
    fun twoProcessesWriteToTheSameFileWithoutSqliteBusy() =
        withTempDir { dir ->
            // Opened (and the schema created) before the child starts, so that the timed write
            // below is the first statement that has to wait for the child.
            val db = NotesDatabase(openSqliteDriver(dir.path, NotesSchema))
            val child = startSecondProcess(dir.path)
            try {
                val toChild = child.outputStream.bufferedWriter()
                val fromChild = child.inputStream.bufferedReader()
                assertEquals(SecondProcessWriter.LOCKED, fromChild.readLine())

                // The child holds the write lock: this waits for it instead of failing.
                val firstWait = timed { db.transaction { db.insert("parent first") } }
                assertTrue(firstWait >= HOLD / 2, "parent's first write returned after $firstWait")

                toChild.write(SecondProcessWriter.GO + "\n")
                toChild.flush()
                val longestWait =
                    (0 until ROWS).maxOf { index ->
                        timed {
                            db.transaction {
                                db.insert("parent $index")
                                blockFor(1.milliseconds)
                            }
                        }
                    }
                println("TwoProcessTest: first write waited $firstWait, then at most $longestWait")

                assertTrue(child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "child did not exit")
                assertEquals(SecondProcessWriter.DONE, fromChild.readLine())
                assertEquals(0, child.exitValue(), "child failed")
            } finally {
                child.destroyForcibly()
                db.sqlDriver.close()
            }

            openSqliteDriver(dir.path, NotesSchema).use { driver ->
                val titles = NotesDatabase(driver).titles().executeAsList()
                assertEquals(2 + 2 * ROWS, titles.size)
                assertEquals(1 + ROWS, titles.count { it.startsWith("child") })
                assertEquals(1 + ROWS, titles.count { it.startsWith("parent") })
            }
            assertTrue(!dir.exists("-wal") && !dir.exists("-shm"))
        }

    private fun timed(block: () -> Unit): Duration {
        val start = TimeSource.Monotonic.markNow()
        block()
        return start.elapsedNow()
    }

    /** Starts [SecondProcessWriter] in a new JVM with this test's classpath. */
    private fun startSecondProcess(path: String): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        return ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            SecondProcessWriter::class.java.name,
            path,
            ROWS.toString(),
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }

    internal companion object {
        /** How long the child holds its first transaction open. */
        val HOLD = 500.milliseconds
        const val ROWS = 50
        const val TIMEOUT_SECONDS = 60L
    }
}
