package io.github.sameerkrmishra.jotter.spike.fts5

import android.os.Build
import android.util.Log
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import io.github.sameerkrmishra.jotter.spike.fts5.db.SpikeDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Runs on a real Android runtime (emulator or device), not on the host. */
@RunWith(AndroidJUnit4::class)
class AndroidFts5Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) {
        Log.i("FTS5SPIKE", "API ${Build.VERSION.SDK_INT}: $msg")
    }

    @Test
    fun bundledDriverViaEygraberAdapterSupportsFts5() = runBlocking<Unit> {
        val path = context.getDatabasePath("bundled.db").apply { delete() }
        path.parentFile?.mkdirs()
        AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.File(path.absolutePath),
            schema = SpikeDatabase.Schema,
        ).use { driver ->
            val report = runFts5Checks(driver)
            log("bundled+eygraber: $report")
            assertEquals(1L, report.fts5CompileOption)
        }
    }

    @Test
    fun bundledDriverViaOwnSyncAdapterSupportsFts5() = runBlocking<Unit> {
        val path = context.getDatabasePath("own.db").apply { delete() }
        path.parentFile?.mkdirs()
        ConnectionSqlDriver(BundledSQLiteDriver().open(path.absolutePath)).use { driver ->
            SpikeDatabase.Schema.synchronous().create(driver)
            val report = runFts5Checks(driver)
            log("bundled+own-adapter: $report")
            assertEquals(1L, report.fts5CompileOption)
        }
    }

    @Test
    fun requerySqliteAndroidSupportsFts5() = runBlocking<Unit> {
        AndroidSqliteDriver(
            schema = SpikeDatabase.Schema.synchronous(),
            context = context,
            name = null,
            factory = RequerySQLiteOpenHelperFactory(),
        ).use { driver ->
            val report = runFts5Checks(driver)
            log("requery: $report")
            // requery omits sqlite_compileoption_used(); the checks above prove FTS5 works.
            assertEquals(-1L, report.fts5CompileOption)
        }
    }

    /** Records what the platform SQLite does. No assertion: the result differs per device. */
    @Test
    fun frameworkSqliteFts5Probe() = runBlocking<Unit> {
        val result = runCatching {
            AndroidSqliteDriver(SpikeDatabase.Schema.synchronous(), context, null).use { driver ->
                runFts5Checks(driver)
            }
        }
        log("framework: $result")
    }
}
