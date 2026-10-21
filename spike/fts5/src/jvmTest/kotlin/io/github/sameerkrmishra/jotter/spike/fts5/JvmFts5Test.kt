package io.github.sameerkrmishra.jotter.spike.fts5

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import io.github.sameerkrmishra.jotter.spike.fts5.db.SpikeDatabase
import app.cash.sqldelight.async.coroutines.synchronous
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmFts5Test {
    @Test
    fun xerialJdbcDriverSupportsFts5() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            SpikeDatabase.Schema.create(driver).await()
            val report = runFts5Checks(driver)
            println("FTS5SPIKE jvm/xerial: $report")
            assertEquals(1L, report.fts5CompileOption)
        }
    }

    @Test
    fun bundledDriverViaEygraberAdapterSupportsFts5() = runBlocking {
        val dir = Files.createTempDirectory("fts5spike")
        val driver = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.File(dir.resolve("spike.db").toString()),
            schema = SpikeDatabase.Schema,
        )
        driver.use {
            val report = runFts5Checks(it)
            println("FTS5SPIKE jvm/bundled+eygraber: $report")
            assertEquals(1L, report.fts5CompileOption)
        }
    }

    @Test
    fun bundledDriverViaOwnSyncAdapterSupportsFts5() = runBlocking {
        val dir = Files.createTempDirectory("fts5spike")
        ConnectionSqlDriver(BundledSQLiteDriver().open(dir.resolve("own.db").toString())).use {
            SpikeDatabase.Schema.synchronous().create(it)
            val report = runFts5Checks(it)
            println("FTS5SPIKE jvm/bundled+own-adapter: $report")
            assertEquals(1L, report.fts5CompileOption)
        }
    }
}
