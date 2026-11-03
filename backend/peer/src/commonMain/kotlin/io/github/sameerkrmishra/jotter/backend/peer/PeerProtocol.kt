package io.github.sameerkrmishra.jotter.backend.peer

/**
 * The HTTP protocol between [PeerBlobStore] and the hub in the `server` module, version 1
 * (`docs/adr/0015-pc-hub-peer-protocol.md`). Both sides use these names, so they cannot drift.
 *
 * Every request carries `Authorization: Bearer <token>`. Every answer from a hub carries
 * [VERSION_HEADER], and one without it is not from a hub. Paths travel in the query string, never
 * in the URL path, so that no HTTP layer can normalise `..` away before the store checks it.
 *
 * | Request | Success | Body |
 * |---|---|---|
 * | `GET` [PING] | 200 | empty JSON object |
 * | `GET` [LIST]`?prefix=` | 200 | JSON array of [dto.BlobEntryDto] |
 * | `GET` [BLOB]`?path=` | 200 | the file's bytes |
 * | `PUT` [BLOB]`?path=` | 201 | none; the request needs `Content-Length` |
 *
 * A failure has a status from [PeerError] and a [dto.ErrorDto] body.
 */
public object PeerProtocol {
    /** The protocol version. A hub that changes the protocol serves it under a new [BASE_PATH]. */
    public const val VERSION: Int = 1

    /** The header that every answer from a hub carries, with [VERSION] as its value. */
    public const val VERSION_HEADER: String = "Jotter-Peer-Protocol"

    /** The common start of every request path. */
    public const val BASE_PATH: String = "/jotter-peer/v1"

    /** Whether the hub's store can be used; see `BlobStore.ping`. */
    public const val PING: String = "$BASE_PATH/ping"

    /** `BlobStore.list`, with the prefix in the query parameter [PREFIX_PARAMETER]. */
    public const val LIST: String = "$BASE_PATH/list"

    /** `BlobStore.read` and `BlobStore.writeNew`, with the path in [PATH_PARAMETER]. */
    public const val BLOB: String = "$BASE_PATH/blob"

    /** The query parameter of [LIST]. */
    public const val PREFIX_PARAMETER: String = "prefix"

    /** The query parameter of [BLOB]. */
    public const val PATH_PARAMETER: String = "path"

    /**
     * The largest file, and so the largest body of [BLOB], in bytes: the 32 MiB that sync allows
     * for one file (`docs/adr/0014-sync-failure-handling.md`).
     */
    public const val MAX_FILE_BYTES: Int = 32 * 1024 * 1024

    /** The largest answer to [LIST], in bytes: room for a few hundred thousand files. */
    public const val MAX_LIST_BYTES: Int = 32 * 1024 * 1024

    /** The TCP port a hub listens on unless configured otherwise. */
    public const val DEFAULT_PORT: Int = 47734
}

/**
 * Why a hub refused a request, with the HTTP status it uses.
 *
 * @property status The HTTP status code.
 */
@Suppress("MagicNumber") // HTTP status codes, which read best as themselves.
public enum class PeerError(
    public val status: Int,
) {
    /** The path or prefix is not valid; see `requireValidBlobPath`. */
    INVALID_PATH(400),

    /** The request is not one the protocol knows, or is malformed. */
    BAD_REQUEST(400),

    /** The request has no valid token. */
    UNAUTHORIZED(401),

    /** `read` found no file. */
    NOT_FOUND(404),

    /** `writeNew` found the path taken. */
    ALREADY_EXISTS(409),

    /** A `PUT` without `Content-Length`. */
    LENGTH_REQUIRED(411),

    /** A body larger than [PeerProtocol.MAX_FILE_BYTES]. */
    TOO_LARGE(413),

    /** The hub failed in another way. */
    FAILED(500),

    /** The hub's own store cannot be reached; the body says why. */
    UNAVAILABLE(503),
}
