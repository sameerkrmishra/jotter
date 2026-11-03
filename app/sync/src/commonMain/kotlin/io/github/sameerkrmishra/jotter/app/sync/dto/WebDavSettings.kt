package io.github.sameerkrmishra.jotter.app.sync.dto

import kotlinx.serialization.Serializable

/**
 * The stored settings of a WebDAV backend: never its password. Plain data, so outside the coverage
 * gate.
 *
 * @property secrets Where its password is: `keyring` or `file`.
 */
@Serializable
internal data class WebDavSettings(
    val url: String,
    val username: String,
    val secrets: String = "keyring",
)
