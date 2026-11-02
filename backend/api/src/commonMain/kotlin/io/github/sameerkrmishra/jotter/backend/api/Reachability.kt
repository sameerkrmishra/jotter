package io.github.sameerkrmishra.jotter.backend.api

/** The answer of [BlobStore.ping]. */
public sealed interface Reachability {
    /** The store is there, and an empty listing means that it has no files. */
    public data object Reachable : Reachability

    /**
     * The store cannot be used now. Sync skips it and tries again later.
     *
     * @property reason Why, in terms that code can act on.
     * @property detail A description for logs and the backend status screen.
     */
    public data class Unreachable(
        val reason: UnreachableReason,
        val detail: String,
    ) : Reachability
}

/** Why a [BlobStore] cannot be reached. */
public enum class UnreachableReason {
    /** Its location does not exist: a folder that is gone, or a USB stick that is unplugged. */
    MISSING,

    /**
     * Something is at its location, but it was never set up as a store. This can be a new location
     * or, which is why it is not simply treated as empty, the bare mount point of an unplugged
     * drive.
     */
    NOT_INITIALIZED,

    /** Anything else, such as an I/O error or a server that does not answer. */
    FAILED,
}
