package io.github.sameerkrmishra.jotter.app.sync.dto

import kotlinx.serialization.Serializable

/**
 * The stored settings of a PC hub backend: never its pairing token. Plain data, so outside the
 * coverage gate.
 *
 * @property secrets Where its token is: `keyring` or `file`.
 */
@Serializable
internal data class PeerSettings(
    val host: String,
    val port: Int,
    val secrets: String = "keyring",
)
