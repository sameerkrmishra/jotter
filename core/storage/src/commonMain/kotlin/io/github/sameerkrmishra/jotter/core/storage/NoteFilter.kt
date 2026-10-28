package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName

/**
 * Which notes a list or search shows: the notes in one [state], optionally only those with [tag].
 *
 * @property state The view: the main list ([NoteState.ACTIVE]), the archive or the trash.
 * @property tag If set, only notes that have this tag.
 */
public data class NoteFilter(
    val state: NoteState = NoteState.ACTIVE,
    val tag: TagName? = null,
)
