package io.github.sameerkrmishra.jotter.app.android

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.work.WorkManager
import io.github.sameerkrmishra.jotter.app.sharedui.AppViewModel
import io.github.sameerkrmishra.jotter.app.sharedui.SyncControls
import io.github.sameerkrmishra.jotter.app.sync.BackendFactory
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.SecretStores
import io.github.sameerkrmishra.jotter.app.sync.StandardBackendFactory
import io.github.sameerkrmishra.jotter.app.sync.SyncScheduler
import io.github.sameerkrmishra.jotter.app.sync.SyncTiming
import io.github.sameerkrmishra.jotter.app.sync.TailnetCheck
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import io.github.sameerkrmishra.jotter.core.sync.DeviceInfo
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

/** The notes database, in the app's `databases` folder as Android apps keep theirs. */
internal const val DATABASE_NAME = "jotter.db"

/** The sealed backend secrets, under `noBackupFilesDir`, which no backup or transfer copies. */
internal const val SECRETS_FILE = "secrets/backend-secrets.json"

/** Why the Sync screen has no folder form on Android (ADR-0018). */
internal const val FOLDERS_UNAVAILABLE: String =
    "Folders cannot be used for sync on Android yet: Android gives apps no plain access to " +
        "shared storage or USB drives, and Jotter does not use Android's folder picker yet. " +
        "Sync with a WebDAV server or the hub on your PC instead."

/**
 * The composition root of the Android app (ADR-0005), once per process: the Activity, the share
 * target and the background sync all use the same database, backend manager and scheduler, so
 * that one sync at a time runs on this database. [JotterApplication] holds it.
 *
 * The database is opened on first use of [opened]. Its UI scope runs on the main thread, sync on
 * [work], with the database and network on [io]; the main thread never syncs.
 *
 * @param context Any context of the app.
 * @param databaseName The database's file name in the app's `databases` folder; tests use theirs.
 * @param secretsFile Where the sealed secrets are kept.
 * @param keyAlias The Keystore key that seals them.
 * @param factory Opens a backend, given where its secrets are and the [tailnet] check; tests may
 *   replace it.
 * @param io Where blocking database, file and Keystore calls run.
 * @param work Where the sync scheduler runs.
 * @param tailnet Whether a hub's token may go out now: by default, only while Tailscale runs.
 */
@Suppress("LongParameterList") // Defaults for the app; tests replace the files and the factory.
internal class AppGraph(
    context: Context,
    private val databaseName: String = DATABASE_NAME,
    private val secretsFile: File = File(context.noBackupFilesDir, SECRETS_FILE),
    private val keyAlias: String = SECRETS_KEY_ALIAS,
    private val factory: (SecretStores, TailnetCheck) -> BackendFactory = ::StandardBackendFactory,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    work: CoroutineDispatcher = Dispatchers.Default,
    private val tailnet: TailnetCheck =
        AndroidTailnet(context.getSystemService(ConnectivityManager::class.java)),
) : AutoCloseable {
    private val context = context.applicationContext
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val background = CoroutineScope(SupervisorJob() + work)

    private val opening = lazy(::open)

    /** The notes and their sync, or why they could not be opened. Opened on first use. */
    val opened: Opened by opening

    /** The secrets of backends: sealed with a Keystore key, in a file outside every backup. */
    val secrets: SecretStores by lazy {
        val store = SealedFileSecretStore(secretsFile, AesGcmCipher { keystoreKey(keyAlias) }, io)
        // Android has no keyring and no plain-text choice: both places are the sealed file. A
        // backend from a database restored from Linux that says "file" thus asks for its secret
        // again, as any backend restored on another device does (ADR-0016).
        SecretStores(store, store)
    }

    private fun open(): Opened {
        val file = context.getDatabasePath(databaseName)
        return try {
            file.parentFile?.mkdirs()
            val store = NoteStore.open(file.path, deviceName(), io)
            val engine = SyncEngine(store, DeviceInfo(deviceName(), "android"), io)
            val manager =
                BackendManager(
                    store,
                    engine,
                    factory(secrets, tailnet),
                    secrets,
                    io,
                    tailnet = tailnet,
                )
            val sync =
                AndroidSync(
                    SyncScheduler(manager::syncAll, SyncTiming(period = PERIOD_MINUTES.minutes)),
                    store.notes.observeRevisionMark(),
                    manager.observe().map { it.isNotEmpty() },
                    WorkManagerJobs(WorkManager.getInstance(context)),
                    background,
                )
            Opened.Ready(store, manager, sync, ui, io)
        } catch (e: StorageBusyException) {
            Opened.Failed("${file.path} is locked by another process. Try again. (${e.message})")
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // The driver's exceptions are not part of NoteStore's API; the user sees why.
            Opened.Failed("Cannot open ${file.path}: ${e.message}")
        }
    }

    /**
     * Stops sync and the screens' work, and closes the database. For tests: an app's process
     * simply ends. Not on the main thread, which the UI scope needs to finish.
     */
    override fun close() {
        runBlocking {
            ui.coroutineContext.job.cancelAndJoin()
            background.coroutineContext.job.cancelAndJoin()
        }
        // A graph never used opens nothing here.
        if (opening.isInitialized()) (opened as? Opened.Ready)?.store?.close()
    }

    private companion object {
        /** The phone's model, such as "Pixel 8", to tell this device apart on other devices. */
        fun deviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
    }
}

/** The notes, opened or not. */
internal sealed interface Opened {
    /** Sync, if the notes are open. */
    val sync: AndroidSync?

    /**
     * The notes are open.
     *
     * @property store The database.
     * @property manager Its backends.
     * @property sync When it syncs.
     * @property io Where its blocking calls run.
     */
    class Ready(
        val store: NoteStore,
        val manager: BackendManager,
        override val sync: AndroidSync,
        private val ui: CoroutineScope,
        val io: CoroutineDispatcher,
    ) : Opened {
        /** The screens' state, made on first use, for as long as the process lives. */
        val app: AppViewModel by lazy {
            AppViewModel(
                store.notes,
                ConflictResolver(store),
                ui,
                io,
                SyncControls(
                    manager,
                    sync.scheduler,
                    foldersUnavailable = FOLDERS_UNAVAILABLE,
                ),
            )
        }
    }

    /** They could not be opened; [reason] says why, for the user. */
    class Failed(
        val reason: String,
    ) : Opened {
        override val sync: AndroidSync? get() = null
    }
}

/** The background jobs that [AndroidSync] asks for: WorkManager's, in the app. */
internal interface SyncJobs {
    /** Keeps the periodic sync while [any] backend exists, and cancels it when none does. */
    fun periodic(any: Boolean)

    /** One sync soon, once there is a network. */
    fun soon()
}

/** [SyncJobs] through WorkManager ([SyncWork]). */
internal class WorkManagerJobs(
    private val workManager: WorkManager,
) : SyncJobs {
    override fun periodic(any: Boolean) = SyncWork.periodic(workManager, any)

    override fun soon() = SyncWork.soon(workManager)
}

/**
 * When the Android app syncs (PLAN.md §6.3, ADR-0018), around the scheduler of `app:sync`, which
 * runs one round at a time and keeps the backoff: on the process's start and each time the app
 * comes to the screen, 5 s after edits while the process runs, when the user asks, and through
 * [jobs] every 15 minutes in the background and once after the app leaves the screen.
 *
 * @property scheduler The scheduler, started by [start].
 * @param revisionMark Rises when a revision is stored, for the scheduler.
 * @param anyBackend Whether any backend is configured, now and whenever that changes.
 * @param jobs The background jobs.
 * @param scope Where the scheduler and the watch of [anyBackend] run, never the main thread.
 */
internal class AndroidSync(
    val scheduler: SyncScheduler,
    private val revisionMark: Flow<Long>,
    private val anyBackend: Flow<Boolean>,
    private val jobs: SyncJobs,
    private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    // Unknown until the backends are first read: a sync after leaving is asked for meanwhile.
    private val backends = AtomicBoolean(true)

    /**
     * Starts the scheduler, once per process, whose first round is the sync on start, and keeps
     * the periodic job in step with whether any backend is configured. Returns whether it started
     * now.
     */
    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        scheduler.start(scope, revisionMark)
        scope.launch {
            anyBackend
                .distinctUntilChanged()
                // A failed read changes nothing: the job stays as it was until the next start.
                .catch { }
                .collect { any ->
                    backends.set(any)
                    jobs.periodic(any)
                }
        }
        return true
    }

    /** The app came to the screen: a sync, as on start. */
    fun foreground() {
        if (!start()) scope.launch { scheduler.syncAndWait() }
    }

    /** A sync for a background job, which returns when it has ended. */
    suspend fun syncAndWait() {
        start()
        scheduler.syncAndWait()
    }

    /**
     * The app left the screen, or a text was shared: a sync by a background job, once online. Not
     * when there is no backend, so that leaving the app does not wake the phone for nothing.
     */
    fun soon() {
        if (backends.get()) jobs.soon()
    }
}
