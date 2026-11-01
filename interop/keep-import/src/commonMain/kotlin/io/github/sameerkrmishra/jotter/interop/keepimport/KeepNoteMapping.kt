package io.github.sameerkrmishra.jotter.interop.keepimport

import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.ImportedNote
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.time.Instant

/** The RFC 9562 namespace for URLs, which [NAME_PREFIX] names are. */
private const val UUID_NAMESPACE = "6ba7b8119dad11d180b400c04fd430c8"

/** Says which source a name comes from, so another importer's ids cannot collide with Keep's. */
private const val NAME_PREFIX = "urn:jotter:google-keep:"

private const val UUID_SIZE = 16
private const val VERSION_BYTE = 6
private const val VERSION_5 = 0x50
private const val LOW_NIBBLE = 0x0F
private const val VARIANT_BYTE = 8
private const val RFC_VARIANT = 0x80
private const val LOW_SIX_BITS = 0x3F
private const val MICROS_PER_MILLI = 1_000L

/** A second line and later ones of a task list item start here to stay inside the item. */
private const val CONTINUATION = "\n  "

/**
 * Derives the note id of the Keep note created at [createdUsec]: a name-based UUIDv5, so all
 * devices and every later Takeout find the same note. `createdTimestampUsec` is the one field
 * that does not change between Takeouts. Changing this function would import every note again.
 */
internal fun keepNoteId(createdUsec: Long): NoteId {
    val namespace = UUID_NAMESPACE.decodeHex()
    val name = (NAME_PREFIX + createdUsec).encodeUtf8()
    val hash =
        Buffer()
            .write(namespace)
            .write(name)
            .readByteString()
            .sha1()
    val bytes = hash.substring(0, UUID_SIZE).toByteArray()
    bytes[VERSION_BYTE] = (bytes[VERSION_BYTE].toInt() and LOW_NIBBLE or VERSION_5).toByte()
    bytes[VARIANT_BYTE] = (bytes[VARIANT_BYTE].toInt() and LOW_SIX_BITS or RFC_VARIANT).toByte()
    return NoteId.parse(bytes.toByteString().hex())
}

/**
 * The note's text as Markdown: `textContent` as it is, then `listContent` as a task list. Keep
 * writes one or the other, but nothing is lost if a note has both.
 */
internal fun KeepNote.markdownBody(): String {
    val list = listContent.joinToString("\n", transform = KeepListItem::toTaskLine)
    return listOf(textContent, list).filter(String::isNotEmpty).joinToString("\n\n")
}

/** A task list line. Further lines of the text are indented, so they stay in the item. */
private fun KeepListItem.toTaskLine(): String {
    val box = if (isChecked) "- [x] " else "- [ ] "
    return box + text.lines().joinToString(CONTINUATION)
}

/** The state of a Keep note. A note can be both trashed and archived; the trash wins. */
internal fun KeepNote.noteState(): NoteState =
    when {
        isTrashed -> NoteState.TRASHED
        isArchived -> NoteState.ARCHIVED
        else -> NoteState.ACTIVE
    }

/** The note to import, with [tags] and the given times in microseconds since the epoch. */
internal fun KeepNote.toImportedNote(
    id: NoteId,
    tags: Set<TagName>,
    createdUsec: Long,
    editedUsec: Long,
): ImportedNote =
    ImportedNote(
        id = id,
        title = title,
        body = markdownBody(),
        tags = tags,
        pinned = isPinned,
        state = noteState(),
        createdAt = instantOfMicros(createdUsec),
        editedAt = instantOfMicros(editedUsec),
    )

private fun instantOfMicros(usec: Long): Instant =
    Instant.fromEpochMilliseconds(usec / MICROS_PER_MILLI)
