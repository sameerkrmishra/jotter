package io.github.sameerkrmishra.jotter.app.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.sameerkrmishra.jotter.app.sync.BackendFactory
import io.github.sameerkrmishra.jotter.app.sync.SecretStores
import io.github.sameerkrmishra.jotter.app.sync.StandardBackendFactory
import io.github.sameerkrmishra.jotter.app.sync.TailnetCheck
import java.io.File
import java.security.KeyStore
import java.util.UUID

/**
 * An [AppGraph] of the app's process on files of its own: a database, a secrets file and a Keystore
 * key that no other test and not the app itself uses. It becomes the [JotterApplication]'s graph,
 * so the Activities and [SyncWorker] use it. [close] removes all of it.
 */
internal class TestGraph(
    factory: (SecretStores, TailnetCheck) -> BackendFactory = ::StandardBackendFactory,
) : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "test-${UUID.randomUUID()}"
    val folder: File = File(context.cacheDir, name).apply { mkdirs() }
    val keyAlias = name
    val graph =
        AppGraph(
            context,
            databaseName = "$name.db",
            secretsFile = File(folder, "secrets/backend-secrets.json"),
            keyAlias = keyAlias,
            factory = factory,
        )

    val ready: Opened.Ready get() = graph.opened as Opened.Ready

    init {
        (context as JotterApplication).replaceGraph(graph)
    }

    override fun close() {
        graph.close()
        listOf("", "-wal", "-shm", "-journal").forEach {
            File(context.getDatabasePath("$name.db").path + it).delete()
        }
        folder.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }
}
