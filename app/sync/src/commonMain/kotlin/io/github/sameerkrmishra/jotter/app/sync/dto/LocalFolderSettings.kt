package io.github.sameerkrmishra.jotter.app.sync.dto

import kotlinx.serialization.Serializable

/** The stored settings of a local folder backend. Plain data, so outside the coverage gate. */
@Serializable
internal data class LocalFolderSettings(
    val path: String,
)
