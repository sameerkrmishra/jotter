package io.github.sameerkrmishra.jotter.backend.api

/**
 * A dumb file store: the only thing a sync backend has to be
 * ([ADR-0004](https://github.com/sameerkrmishra/jotter/blob/main/docs/adr/0004-immutable-change-segments.md)).
 * Implementations must not interpret file contents.
 *
 * **Paths.** Every path is relative to the store's root, with `/` between segments, and is checked
 * by [requireValidBlobPath] (a prefix by [requireValidBlobPrefix]) before anything else happens, so
 * a bad path fails with [InvalidBlobPathException] even when the store cannot be reached.
 *
 * **Failures.** Every method fails with a [BlobStoreException]. A store that cannot be reached
 * throws [BackendUnavailableException] from [list], [read] and [writeNew], and never answers as an
 * empty store would, so an unplugged USB stick cannot look like a store without files.
 *
 * A new backend is done when it passes the contract tests in `backend/api/contract`.
 */
public interface BlobStore {
    /**
     * Returns every file below the directory [prefix], at any depth, sorted by [BlobInfo.path] in
     * [String] order.
     *
     * [prefix] is a **path prefix**, not a string prefix: it matches whole segments, and so
     * `list("a/b")` returns `a/b/c` and `a/b/d/e` but not `a/bc`, nor a file at `a/b` itself. The
     * empty prefix means the whole store. A prefix that matches nothing, because there is no such
     * directory or because it names a file, gives an empty list. A directory holds nothing of its
     * own: only files are returned, and a directory without files does not show.
     *
     * Over WebDAV this is a `PROPFIND` of the collection, repeated for its sub-collections.
     *
     * @throws InvalidBlobPathException if [prefix] is not valid.
     * @throws BackendUnavailableException if the store cannot be reached.
     * @throws BlobStoreFailureException if the store fails in any other way.
     */
    public suspend fun list(prefix: String): List<BlobInfo>

    /**
     * Returns the content of the file at [path]. A file is only ever seen complete.
     *
     * @throws InvalidBlobPathException if [path] is not valid.
     * @throws BlobNotFoundException if there is no file at [path].
     * @throws BackendUnavailableException if the store cannot be reached.
     * @throws BlobStoreFailureException if the store fails in any other way.
     */
    public suspend fun read(path: String): ByteArray

    /**
     * Creates the file at [path] with the content [bytes], and any directories above it. Files are
     * immutable once written. When several writers race for one path, through this object or any
     * other, exactly one succeeds. A reader sees no file or the whole file, never a part of it, and
     * a failure or crash never leaves a part of a file at [path].
     *
     * WebDAV implements this with `PUT` plus `If-None-Match: *`.
     *
     * A failure does not say that nothing was written. The whole file can have appeared without
     * the store knowing that it lasts, for example if a power cut could still lose it. It then
     * stays in place, because a reader may have seen it. So the caller retries with the same [path]
     * and [bytes]. If the retry succeeds, nothing was there, and the file now is. If it throws
     * [BlobAlreadyExistsException], a file is there and the store has asked again for it to be made
     * durable, without an error. That is not proof: Linux can report a failed writeback once and
     * then report success. The caller compares the file with what it meant to write (ADR-0004). A
     * retry never reports success for a file it found, because it cannot tell whose file that is
     * and has no better news about its durability. A retry that fails can have published the file
     * just as the first attempt can, so the same rules apply to the next retry.
     *
     * @throws InvalidBlobPathException if [path] is not valid.
     * @throws BlobAlreadyExistsException if [path] exists, as a file or as a directory.
     * @throws BackendUnavailableException if the store cannot be reached.
     * @throws BlobStoreFailureException if the store fails in any other way, for example because
     *   a directory above [path] is a file.
     */
    public suspend fun writeNew(
        path: String,
        bytes: ByteArray,
    )

    /**
     * Tells whether the store can be used right now. Returns [Reachability.Reachable] only if the
     * store itself is there, so that an empty result from [list] can be trusted to mean empty.
     * Never throws for an unreachable store: that is what [Reachability.Unreachable] is for.
     */
    public suspend fun ping(): Reachability
}

/**
 * A file in a [BlobStore].
 *
 * @property path The file's full path from the store's root, whatever prefix found it.
 * @property size The file's length in bytes.
 */
public data class BlobInfo(
    val path: String,
    val size: Long,
)
