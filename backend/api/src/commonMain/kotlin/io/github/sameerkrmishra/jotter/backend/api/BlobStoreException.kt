package io.github.sameerkrmishra.jotter.backend.api

/** The failure of a [BlobStore] call. */
public sealed class BlobStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * [path] is not a valid path or prefix; see [requireValidBlobPath].
 *
 * @property path The rejected text.
 */
public class InvalidBlobPathException(
    public val path: String,
    reason: String,
) : BlobStoreException("Invalid blob path \"$path\": $reason")

/**
 * [BlobStore.writeNew] found its path taken, possibly by a writer that raced it.
 *
 * @property path The path that exists.
 */
public class BlobAlreadyExistsException(
    public val path: String,
) : BlobStoreException("Blob already exists: $path")

/**
 * [BlobStore.read] found no file.
 *
 * @property path The path that holds no file.
 */
public class BlobNotFoundException(
    public val path: String,
) : BlobStoreException("No such blob: $path")

/**
 * The store cannot be reached. The same situation in which [BlobStore.ping] answers
 * [Reachability.Unreachable].
 *
 * @property reachability What [BlobStore.ping] would say.
 */
public class BackendUnavailableException(
    public val reachability: Reachability.Unreachable,
    cause: Throwable? = null,
) : BlobStoreException("Backend unavailable: ${reachability.detail}", cause)

/** The store failed in a way the other exceptions do not describe, such as a full disk. */
public class BlobStoreFailureException(
    message: String,
    cause: Throwable? = null,
) : BlobStoreException(message, cause)
