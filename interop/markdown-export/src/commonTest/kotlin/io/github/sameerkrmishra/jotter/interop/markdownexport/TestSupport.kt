package io.github.sameerkrmishra.jotter.interop.markdownexport

import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlin.time.Instant

/** 2026-01-02T03:04:05Z. */
const val CREATED_MILLIS: Long = 1_767_323_045_000

/** 2026-01-03T03:04:05.250Z. */
const val UPDATED_MILLIS: Long = 1_767_409_445_250

/** The id of the [index]th synthetic note. Higher indexes give higher ids. */
fun noteIdOf(index: Int): NoteId =
    NoteId.parse("00000000-0000-7000-8000-" + index.toString(radix = 16).padStart(12, '0'))

/** A synthetic note. */
fun noteOf(
    title: String,
    index: Int = 1,
    body: String = "",
    tags: Set<TagName> = emptySet(),
    state: NoteState = NoteState.ACTIVE,
): Note =
    Note(
        id = noteIdOf(index),
        revisionId = RevisionId.parse("00000000000070008000000000000001"),
        title = title,
        body = body,
        tags = tags,
        isPinned = false,
        state = state,
        createdAt = Instant.fromEpochMilliseconds(CREATED_MILLIS),
        updatedAt = HybridTimestamp(UPDATED_MILLIS, 3),
    )
