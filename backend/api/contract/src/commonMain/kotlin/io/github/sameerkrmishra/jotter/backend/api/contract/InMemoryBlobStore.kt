package io.github.sameerkrmishra.jotter.backend.api.contract

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.api.requireValidBlobPath
import io.github.sameerkrmishra.jotter.backend.api.requireValidBlobPrefix
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * A [BlobStore] in memory, for tests of the code above the backends. It passes the contract suite.
 */
public class InMemoryBlobStore : BlobStore {
    private val lock = reentrantLock()
    private val files = HashMap<String, ByteArray>()

    /** Set this to false to act like a backend that went away. No file is lost. */
    public var reachable: Boolean
        get() = lock.withLock { isUp }
        set(value) = lock.withLock { isUp = value }

    private var isUp = true

    override suspend fun list(prefix: String): List<BlobInfo> {
        requireValidBlobPrefix(prefix)
        val start = if (prefix.isEmpty()) "" else "$prefix/"
        return lock.withLock {
            requireUp()
            files
                .filterKeys { it.startsWith(start) }
                .map { (path, bytes) -> BlobInfo(path, bytes.size.toLong()) }
                .sortedBy { it.path }
        }
    }

    override suspend fun read(path: String): ByteArray {
        requireValidBlobPath(path)
        return lock.withLock {
            requireUp()
            files[path]?.copyOf() ?: throw BlobNotFoundException(path)
        }
    }

    override suspend fun writeNew(
        path: String,
        bytes: ByteArray,
    ) {
        requireValidBlobPath(path)
        lock.withLock {
            requireUp()
            if (path in files || files.keys.any { it.startsWith("$path/") }) {
                throw BlobAlreadyExistsException(path)
            }
            val fileAbove = files.keys.firstOrNull { path.startsWith("$it/") }
            if (fileAbove != null) {
                throw BlobStoreFailureException("$fileAbove is a file, not a directory")
            }
            files[path] = bytes.copyOf()
        }
    }

    override suspend fun ping(): Reachability =
        lock.withLock {
            if (isUp) Reachability.Reachable else DOWN
        }

    private fun requireUp() {
        if (!isUp) throw BackendUnavailableException(DOWN)
    }

    private companion object {
        val DOWN = Reachability.Unreachable(UnreachableReason.FAILED, "the store is switched off")
    }
}
