package io.github.sameerkrmishra.jotter.backend.peer.dto

import kotlinx.serialization.Serializable

/**
 * One file in the answer to `PeerProtocol.LIST`.
 *
 * @property path The file's path from the store's root.
 * @property size Its length in bytes.
 */
@Serializable
public data class BlobEntryDto(
    val path: String,
    val size: Long,
)

/**
 * The body of every failed request.
 *
 * @property code The name of a `PeerError`.
 * @property detail A description for logs and the backend status screen. Never holds a secret.
 * @property reason For `PeerError.UNAVAILABLE`, the name of the `UnreachableReason`.
 */
@Serializable
public data class ErrorDto(
    val code: String,
    val detail: String,
    val reason: String? = null,
)
