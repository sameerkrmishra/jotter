package io.github.sameerkrmishra.jotter.spike.fts5

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.sameerkrmishra.jotter.spike.fts5.db.SpikeDatabase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Only compiled with -Pspike.sync (generateAsync = false). */
class JvmSyncFts5Test {
    @Test
    fun syncXerial() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            SpikeDatabase.Schema.create(driver)
            val report = runFts5Checks(driver)
            println("FTS5SPIKE sync jvm/xerial: $report")
            assertEquals(1L, report.fts5CompileOption)
        }
    }

    @Test
    fun syncBundledViaOwnAdapter() = runBlocking {
        val dir = Files.createTempDirectory("fts5spike")
        ConnectionSqlDriver(BundledSQLiteDriver().open(dir.resolve("own.db").toString())).use {
            SpikeDatabase.Schema.create(it)
            val report = runFts5Checks(it)
            println("FTS5SPIKE sync jvm/bundled+own-adapter: $report")
            assertEquals(1L, report.fts5CompileOption)
        }
    }
}
