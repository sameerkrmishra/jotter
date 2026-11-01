package io.github.sameerkrmishra.jotter.interop.keepimport.dto

import kotlinx.serialization.Serializable

/**
 * The fields of a Takeout Keep document that the import uses. Everything has a default, so a
 * document is rejected only if it is not JSON, is not an object or has a field of the wrong
 * type. The timestamps are nullable because what is missing matters to the importer.
 */
@Serializable
internal data class KeepNote(
    val title: String = "",
    val textContent: String = "",
    val listContent: List<KeepListItem> = emptyList(),
    val labels: List<KeepLabel> = emptyList(),
    val attachments: List<KeepAttachment> = emptyList(),
    val annotations: List<KeepAnnotation> = emptyList(),
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val createdTimestampUsec: Long? = null,
    val userEditedTimestampUsec: Long? = null,
)

/** One line of a Keep list note. */
@Serializable
internal data class KeepListItem(
    val text: String = "",
    val isChecked: Boolean = false,
)

/** A Keep label. */
@Serializable
internal data class KeepLabel(
    val name: String = "",
)

/** Something Keep attached to a note. [source] says what; `WEBLINK` is a web link. */
@Serializable
internal data class KeepAnnotation(
    val source: String = "",
    val title: String = "",
    val url: String = "",
)

/** A file attached to a Keep note. Only its name is read, to report that it was dropped. */
@Serializable
internal data class KeepAttachment(
    val filePath: String = "",
)
