package io.github.sameerkrmishra.jotter.backend.api.contract

import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import kotlinx.coroutines.test.TestResult
import kotlin.test.Test
import kotlin.test.assertFails

/**
 * ADR-0004 rests on the suite catching a store that breaks the rules. Each case runs one contract
 * test against a store with one such fault, and expects it to fail.
 */
class ContractCatchesBrokenStoresTest {
    /** A store that replaces a file instead of refusing the write, the fault ADR-0004 names. */
    private class Overwriting : BlobStore {
        private val files = HashMap<String, ByteArray>()

        override suspend fun list(prefix: String) =
            files.map { (path, bytes) -> BlobInfo(path, bytes.size.toLong()) }

        override suspend fun read(path: String) = files[path] ?: throw BlobNotFoundException(path)

        override suspend fun writeNew(
            path: String,
            bytes: ByteArray,
        ) {
            files[path] = bytes
        }

        override suspend fun ping() = Reachability.Reachable
    }

    /** A store that matches a prefix as a string. */
    private class StringPrefixes(
        private val inner: BlobStore = InMemoryBlobStore(),
    ) : BlobStore by inner {
        override suspend fun list(prefix: String) =
            inner.list("").filter { it.path.startsWith(prefix) }
    }

    /** A store that answers like an empty one when it is gone. */
    private class EmptyWhenGone(
        private val inner: InMemoryBlobStore = InMemoryBlobStore(),
    ) : BlobStore by inner {
        init {
            inner.reachable = false
        }

        override suspend fun list(prefix: String) = emptyList<BlobInfo>()
    }

    private fun failsOn(block: () -> TestResult) = assertFails { block() }

    @Test
    fun catchesAStoreThatOverwrites() {
        val suite = InMemoryBlobStoreTest.against { Overwriting() }
        failsOn(suite::writingToATakenPathFailsAndKeepsTheFirstContent)
    }

    @Test
    fun catchesAStoreThatLosesARaceOnOverwriting() {
        val suite = InMemoryBlobStoreTest.against { Overwriting() }
        failsOn(suite::concurrentWritersOfOnePathHaveExactlyOneWinner)
    }

    @Test
    fun catchesAStoreWithStringPrefixes() {
        val suite = InMemoryBlobStoreTest.against { StringPrefixes() }
        failsOn(suite::listMatchesWholeSegmentsNotStringPrefixes)
    }

    @Test
    fun catchesAStoreThatLooksEmptyWhenGone() {
        val suite = InMemoryBlobStoreTest.against { EmptyWhenGone() }
        failsOn(suite::anUnreachableStoreNeverLooksEmpty)
    }

    @Test
    fun catchesAStoreThatTrustsItsPaths() {
        val suite = InMemoryBlobStoreTest.against { Overwriting() }
        failsOn(suite::badPathsAreRefusedByEveryMethod)
    }
}
