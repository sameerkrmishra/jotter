package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlin.time.Instant

/** Separates the tags in the `tags` column of the note queries (`char(31)` in Note.sq). */
private const val TAG_SEPARATOR = '\u001F'

/**
 * Builds a [Note] from the columns that every note query in Note.sq selects, in that order, so
 * it can be passed to the generated queries as their mapper. [tags] is the note's tags joined by
 * U+001F, or null if it has none.
 */
@Suppress("LongParameterList") // One parameter per selected column.
internal fun noteFromRow(
    id: String,
    revisionId: String,
    title: String,
    body: String,
    isPinned: Boolean,
    state: String,
    createdAtMillis: Long,
    updatedPhysical: Long,
    updatedCounter: Long,
    tags: String?,
): Note =
    Note(
        id = NoteId.parse(id),
        revisionId = RevisionId.parse(revisionId),
        title = title,
        body = body,
        tags = parseTags(tags),
        isPinned = isPinned,
        state = NoteState.valueOf(state),
        createdAt = Instant.fromEpochMilliseconds(createdAtMillis),
        updatedAt = hybridTimestamp(updatedPhysical, updatedCounter),
    )

private fun parseTags(joined: String?): Set<TagName> =
    if (joined == null) {
        emptySet()
    } else {
        joined.split(TAG_SEPARATOR).mapTo(mutableSetOf(), TagName::fromNormalised)
    }
