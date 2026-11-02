package io.github.sameerkrmishra.jotter.backend.api.contract

import io.github.sameerkrmishra.jotter.backend.api.BlobStore

/**
 * Runs the suite against [InMemoryBlobStore], and so proves that the suite can pass at all.
 * [against] gives the same tests to [ContractCatchesBrokenStoresTest], without a further test class
 * that the test runners would pick up.
 */
class InMemoryBlobStoreTest : BlobStoreContract() {
    private var store: () -> BlobStore = ::InMemoryBlobStore
    private var unreachable: () -> BlobStore =
        { InMemoryBlobStore().apply { reachable = false } }

    override suspend fun newStore(): BlobStore = store()

    override suspend fun unreachableStore(): BlobStore = unreachable()

    companion object {
        /** The suite with [store] for both factories. */
        fun against(store: () -> BlobStore) =
            InMemoryBlobStoreTest().also {
                it.store = store
                it.unreachable = store
            }
    }
}
