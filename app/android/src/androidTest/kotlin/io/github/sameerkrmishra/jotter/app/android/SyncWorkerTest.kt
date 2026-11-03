package io.github.sameerkrmishra.jotter.app.android

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.app.sync.BackendFactory
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.Outcome
import io.github.sameerkrmishra.jotter.app.sync.SecretStores
import io.github.sameerkrmishra.jotter.app.sync.StandardBackendFactory
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.DeviceInfo
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Background sync through WorkManager's [SyncWorker], with another device on a folder in the app's
 * cache. The app offers no folders on Android (ADR-0018), but its backend manager can add one,
 * which keeps this test free of a network.
 */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {
    private val mainThreadCalls = AtomicInteger()
    private val calls = AtomicInteger()
    private val test =
        TestGraph { secrets, tailnet -> watching(StandardBackendFactory(secrets, tailnet)) }
    private val stick = File(test.folder, "stick").apply { mkdirs() }

    // Not beside the folder: a backend may not be in a database's folder (app:sync).
    private val peerFolder = File("${test.folder.path}-peer").apply { mkdirs() }
    private val peerFile = File(peerFolder, "peer.db")
    private val peer = NoteStore.open(peerFile.path, "Peer", Dispatchers.IO)
    private val peerManager =
        BackendManager(
            peer,
            SyncEngine(peer, DeviceInfo("Peer", "test"), Dispatchers.IO),
            StandardBackendFactory(SecretStores.NONE),
            SecretStores.NONE,
            Dispatchers.IO,
        )

    @After
    fun cleanUp() {
        peer.close()
        peerFolder.deleteRecursively()
        test.close()
    }

    /** Opens stores whose every call counts, and counts those made on the main thread. */
    private fun watching(factory: BackendFactory) =
        BackendFactory { backend ->
            val store = factory.open(backend)
            object : BlobStore {
                private fun count() {
                    calls.incrementAndGet()
                    val main = Looper.myLooper() == Looper.getMainLooper()
                    if (main) mainThreadCalls.incrementAndGet()
                }

                override suspend fun list(prefix: String): List<BlobInfo> =
                    store.list(prefix).also { count() }

                override suspend fun read(path: String): ByteArray =
                    store.read(path).also { count() }

                override suspend fun writeNew(
                    path: String,
                    bytes: ByteArray,
                ) = store.writeNew(path, bytes).also { count() }

                override suspend fun ping(): Reachability = store.ping().also { count() }
            }
        }

    private fun runWorker(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<SyncWorker>(test.context).build().doWork() }

    @Test
    fun theWorkerSyncsWithTheBackendsAndWaitsForIt() =
        runBlocking {
            val app = test.ready
            val added = peerManager.addLocalFolder(stick.path, setUpNewStore = true)
            assertIs<AddResult.Added>(added)
            peer.notes.create("From the PC", "hello")
            peerManager.syncAll()
            assertIs<AddResult.Added>(app.sync.manager.addLocalFolder(stick.path))

            assertEquals(ListenableWorker.Result.success(), runWorker())

            // The worker returned after its round, so the note is here now.
            assertEquals(
                "From the PC",
                app.store.notes
                    .list()
                    .single()
                    .title,
            )
            val backend =
                app.sync.manager
                    .list()
                    .single()
            assertEquals(Outcome.SYNCED, backend.lastAttempt?.outcome)
            assertTrue(calls.get() > 0)
            assertEquals(0, mainThreadCalls.get(), "no backend call on the main thread")

            // What this phone writes goes out with the next background sync.
            app.store.notes.create("From the phone", "hi")
            runWorker()
            peerManager.syncAll()
            assertEquals(
                setOf("From the PC", "From the phone"),
                peer.notes
                    .list()
                    .map { it.title }
                    .toSet(),
            )
        }

    @Test
    fun aPeriodicJobRunsOnlyWhileThereIsABackend() =
        runBlocking {
            val app = test.ready
            val workManager = WorkManager.getInstance(test.context)
            app.sync.start()
            peerManager.addLocalFolder(stick.path, setUpNewStore = true)
            val added = assertIs<AddResult.Added>(app.sync.manager.addLocalFolder(stick.path))
            awaitPeriodic(workManager) { infos ->
                infos.any {
                    !it.state.isFinished &&
                        it.constraints.requiredNetworkType == NetworkType.CONNECTED
                }
            }
            app.sync.manager.remove(added.backend.id)
            awaitPeriodic(
                workManager,
            ) { infos -> infos.all { it.state == WorkInfo.State.CANCELLED } }
        }

    @Test
    fun aDatabaseThatCannotOpenEndsTheJobQuietly() {
        // Not a database: the graph says why, and the worker has nothing to sync.
        val name = "${test.folder.name}-broken.db"
        val broken = test.context.getDatabasePath(name)
        checkNotNull(broken.parentFile).mkdirs()
        broken.writeText("not SQLite, but long enough to be read as a header by SQLite itself")
        val graph =
            AppGraph(
                test.context,
                databaseName = name,
                secretsFile = File(test.folder, "s.json"),
                keyAlias = test.keyAlias,
            )
        (test.context as JotterApplication).replaceGraph(graph)
        try {
            val failed = assertIs<Opened.Failed>(graph.opened)
            assertTrue(failed.reason.contains(name), failed.reason)
            assertEquals(ListenableWorker.Result.success(), runWorker())
        } finally {
            graph.close()
            broken.delete()
        }
    }

    private suspend fun awaitPeriodic(
        workManager: WorkManager,
        done: (List<WorkInfo>) -> Boolean,
    ) = withTimeout(TIMEOUT_MS) {
        while (true) {
            val infos = workManager.getWorkInfosForUniqueWork(PERIODIC_SYNC).get()
            if (infos.isNotEmpty() && done(infos)) break
            delay(POLL_MS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 100L
    }
}
