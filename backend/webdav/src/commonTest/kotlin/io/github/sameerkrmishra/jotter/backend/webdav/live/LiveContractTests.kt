package io.github.sameerkrmishra.jotter.backend.webdav.live

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.contract.BlobStoreContract
import io.github.sameerkrmishra.jotter.backend.webdav.WebDavBlobStore
import kotlin.test.AfterTest

/**
 * The contract suite against a real server. Each store gets a collection of its own, and
 * [takeAway] deletes it, as another device or the user could.
 */
@Suppress("AbstractClassCanBeConcreteClass") // Concrete, the test runners would try to run it.
abstract class LiveContract(
    variable: String,
) : BlobStoreContract() {
    private val server = LiveServer(variable)
    private val stores = mutableListOf<WebDavBlobStore>()
    private val collections = mutableMapOf<BlobStore, String>()

    override suspend fun newStore(): BlobStore {
        val collection = server.newCollection()
        val store = WebDavBlobStore(collection, LIVE_CREDENTIALS)
        stores += store
        store.initialize()
        collections[store] = collection
        return store
    }

    // Port 9 (discard) is closed on the test machines, so the connection is refused at once.
    override suspend fun unreachableStore(): BlobStore =
        WebDavBlobStore("http://127.0.0.1:9/jotter/", LIVE_CREDENTIALS).also { stores += it }

    override suspend fun takeAway(store: BlobStore) {
        server.delete(collections.getValue(store))
    }

    @AfterTest
    fun closeClients() {
        stores.forEach { it.close() }
        server.close()
    }
}

/** lighttpd 1.4 with mod_webdav. */
class LighttpdContractTest : LiveContract("JOTTER_WEBDAV_LIGHTTPD_URL")

/**
 * Apache httpd 2.4 with mod_dav_fs. Its `If-None-Match: *` is not atomic, so
 * `concurrentWritersOfOnePathHaveExactlyOneWinner` fails on it and is excluded by
 * scripts/live-test.sh: see docs/adr/0017-webdav-backend.md.
 */
class ApacheContractTest : LiveContract("JOTTER_WEBDAV_APACHE_URL")
