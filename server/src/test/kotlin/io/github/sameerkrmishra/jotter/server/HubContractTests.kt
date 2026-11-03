package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.contract.BlobStoreContract
import io.github.sameerkrmishra.jotter.backend.peer.PeerBlobStore
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import kotlin.test.AfterTest

/**
 * The contract suite end to end: [PeerBlobStore] over loopback to a real [PeerHub], which serves a
 * local folder. So client, server and store together must behave as a local store does.
 */
@Suppress("AbstractClassCanBeConcreteClass") // Concrete, the test runners would try to run it.
abstract class HubContract : BlobStoreContract() {
    /** The hubs and clients of the running test. */
    internal val hubs = TestHubs()
    private val started = HashMap<BlobStore, StartedHub>()

    override suspend fun newStore(): BlobStore {
        val hub = hubs.start()
        return hubs.client(hub).also { started[it] = hub }
    }

    /** The hub that [store], from [newStore], talks to. */
    internal fun hubOf(store: BlobStore): StartedHub = started.getValue(store)

    @AfterTest
    fun stopHubs() = runBlocking { hubs.close() }
}

/** The hub goes away: it stops, as when the PC sleeps or the app quits. */
class PeerContractTest : HubContract() {
    override suspend fun unreachableStore(): BlobStore {
        val hub = hubs.start()
        hub.hub.stop()
        return hubs.client(hub)
    }

    override suspend fun takeAway(store: BlobStore) {
        hubOf(store).hub.stop()
    }
}

/** The hub runs, but its store goes away: the folder is deleted, as when a drive is unplugged. */
class PeerStoreGoneContractTest : HubContract() {
    override suspend fun unreachableStore(): BlobStore = hubs.client(hubs.start(setUp = false))

    override suspend fun takeAway(store: BlobStore) {
        FileSystem.SYSTEM.deleteRecursively(hubOf(store).storeRoot)
    }
}
