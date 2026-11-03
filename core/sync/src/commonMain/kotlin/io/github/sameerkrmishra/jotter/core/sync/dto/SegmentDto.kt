package io.github.sameerkrmishra.jotter.core.sync.dto

import kotlinx.serialization.Serializable

/** The start of any segment, to tell a future format from a corrupt file. */
@Serializable
internal data class SegmentHeaderDto(
    val format: Int,
)

/** A change segment (ADR-0004). */
@Serializable
internal data class SegmentDto(
    val format: Int,
    val device: String,
    val number: Long,
    val revisions: List<RevisionDto>,
)

/** A revision. [snapshot] is absent for a tombstone. */
@Serializable
internal data class RevisionDto(
    val id: String,
    val note: String,
    val parents: List<String>,
    val physicalMillis: Long,
    val counter: Int,
    val device: String,
    val merge: Boolean,
    val losingParent: String? = null,
    val resolves: String? = null,
    val snapshot: SnapshotDto? = null,
)

/** A revision's content. */
@Serializable
internal data class SnapshotDto(
    val title: String,
    val body: String,
    val tags: List<String>,
    val pinned: Boolean,
    val pinnedStamp: StampDto,
    val state: String,
    val stateStamp: StampDto,
)

/** A last-writer-wins stamp. */
@Serializable
internal data class StampDto(
    val physicalMillis: Long,
    val counter: Int,
    val device: String,
    val revision: String,
)

/** A format file, `jotter/formats/<format>-<codec>.json`, which says the same as its name. */
@Serializable
internal data class FormatDto(
    val format: Int,
    val codec: String,
)

/** `device.json`, for people and for later status screens. Sync never reads it. */
@Serializable
internal data class DeviceDto(
    val name: String,
    val platform: String,
    val created: Long,
)
