package io.github.sameerkrmishra.jotter.server.dto

import kotlinx.serialization.Serializable

/**
 * The file of paired devices.
 *
 * @property version The format, 1.
 * @property devices Every paired device.
 */
@Serializable
internal data class TokenFileDto(
    val version: Int,
    val devices: List<DeviceDto>,
)

/**
 * One paired device.
 *
 * @property id The token's public part.
 * @property name What the user called the device.
 * @property secretSha256 The SHA-256 of the token's secret part, as lowercase hex.
 */
@Serializable
internal data class DeviceDto(
    val id: String,
    val name: String,
    val secretSha256: String,
)
