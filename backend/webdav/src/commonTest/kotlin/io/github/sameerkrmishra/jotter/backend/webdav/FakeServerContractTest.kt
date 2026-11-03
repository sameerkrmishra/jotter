package io.github.sameerkrmishra.jotter.backend.webdav

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.contract.BlobStoreContract
import kotlin.time.Duration.Companion.milliseconds

/** The contract suite against the in-memory server, on every target. */
class FakeServerContractTest : BlobStoreContract() {
    private val servers = mutableMapOf<BlobStore, FakeDavServer>()

    override suspend fun newStore(): BlobStore {
        val server = FakeDavServer()
        server.mkdirs("/dav/me@example.org/jotter sync")
        val store = fakeStore(server, "/dav/me@example.org/jotter%20sync/")
        store.initialize()
        servers[store] = server
        return store
    }

    override suspend fun unreachableStore(): BlobStore =
        fakeStore(FakeDavServer().apply { down = true }, "/dav/")

    override suspend fun takeAway(store: BlobStore) {
        servers.getValue(store).down = true
    }
}

/** A store at [path] on [server], with short retry delays. */
internal fun fakeStore(
    server: FakeDavServer,
    path: String = "/store/",
    settings: DavSettings = DavSettings(retryDelay = 1.milliseconds),
): WebDavBlobStore =
    WebDavBlobStore(
        DavLocation.parse(FAKE_ORIGIN + path),
        FAKE_CREDENTIALS,
        server.engine(),
        settings,
    )
