package io.github.sameerkrmishra.jotter.app.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** How often Android syncs in the background: WorkManager's shortest period (PLAN.md §6.3). */
internal const val PERIOD_MINUTES = 15L

/** The unique name of the periodic sync. */
internal const val PERIODIC_SYNC = "jotter-sync-periodic"

/** The unique name of the sync after the app leaves the screen. */
internal const val SYNC_SOON = "jotter-sync-soon"

/**
 * When WorkManager runs [SyncWorker] (ADR-0018): every 15 minutes while any backend is configured,
 * and once after the user leaves the app or shares a text, so that edits leave the phone even if
 * Android stops the process before the scheduler's own sync after edits. Both wait for a network
 * connection. What runs, and its backoff, is the process's one [SyncScheduler][
 * io.github.sameerkrmishra.jotter.app.sync.SyncScheduler].
 */
internal object SyncWork {
    private val network =
        Constraints
            .Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    /**
     * Syncs every [PERIOD_MINUTES] while [any] is true; otherwise there is nothing to sync, and
     * no periodic job wakes the phone.
     */
    fun periodic(
        workManager: WorkManager,
        any: Boolean,
    ) {
        if (any) {
            val request =
                PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(network)
                    .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_SYNC,
                // A newer version's request replaces an older one; an equal one keeps its time.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } else {
            workManager.cancelUniqueWork(PERIODIC_SYNC)
        }
    }

    /**
     * Syncs once, as soon as there is a network. A request still waiting is replaced, so leaving
     * the app often asks for one sync, not many.
     */
    fun soon(workManager: WorkManager) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build()
        workManager.enqueueUniqueWork(SYNC_SOON, ExistingWorkPolicy.REPLACE, request)
    }
}

/**
 * One sync in the background: a scheduled round of the process's scheduler, which this waits for.
 * So it never runs beside another sync of this database, it honours the scheduler's backoff and its
 * waiting after a refused password, and it runs on WorkManager's threads, never the main one.
 * A failed backend is the scheduler's to retry; this job succeeds either way, so that WorkManager's
 * own backoff does not stack on top.
 */
public class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as JotterApplication).graph
        graph.opened.sync?.syncAndWait()
        return Result.success()
    }
}
