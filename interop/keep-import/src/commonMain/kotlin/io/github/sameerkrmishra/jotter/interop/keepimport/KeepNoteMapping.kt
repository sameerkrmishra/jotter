package io.github.sameerkrmishra.jotter.interop.keepimport

import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.ImportedNote
import io.github.sameerkrmishra.jotter.interop.keepimport.dto.KeepAnnotation
import io.github.sameerkrmishra.jotter.interop.keepimport.dto.KeepListItem
import io.github.sameerkrmishra.jotter.interop.keepimport.dto.KeepNote
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

/** The [KeepAnnotation.source] of a web link. */
private const val WEB_LINK = "WEBLINK"

/** Characters that would break a Markdown link, in its title or its URL. */
private const val LINK_BREAKERS = "[]()\\\r\n"

/** The ASCII characters above the control characters and space that no URL can contain. */
private const val NOT_IN_URLS = "\"<>\\^`{|}\u007F"

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
 * The note's text as Markdown: `textContent` as it is, then `listContent` as a task list, then one
 * line per web link that the text does not already contain. Keep writes either a text or a list,
 * but nothing is lost if a note has both.
 */
internal fun KeepNote.markdownBody(): String {
    val list = listContent.joinToString("\n", transform = KeepListItem::toTaskLine)
    val text = joinParagraphs(textContent, list)
    val links =
        annotations
            .filter { it.source == WEB_LINK && it.url.isNotEmpty() && !text.containsUrl(it.url) }
            .distinctBy(KeepAnnotation::url)
            .joinToString("\n", transform = KeepAnnotation::toMarkdownLine)
    return joinParagraphs(text, links)
}

/**
 * Whether this text has [url] as a whole URL, not as the start of a longer one: `https://a.co/foo`
 * is not in `https://a.co/foobar`. Punctuation can continue a URL, so a URL before a full stop
 * does not count and the link is repeated, which is better than losing it.
 */
private fun String.containsUrl(url: String): Boolean {
    var start = indexOf(url)
    while (start >= 0) {
        val next = getOrNull(start + url.length)
        if (next == null || next <= ' ' || next in NOT_IN_URLS) return true
        start = indexOf(url, start + 1)
    }
    return false
}

private fun joinParagraphs(vararg paragraphs: String): String =
    paragraphs.filter(String::isNotEmpty).joinToString("\n\n")

/** A link with its title, if it has one that adds something and is safe in a Markdown link. */
private fun KeepAnnotation.toMarkdownLine(): String {
    val hasUsableTitle =
        title.isNotBlank() && title != url && (title + url).none { it in LINK_BREAKERS }
    return if (hasUsableTitle) "[$title]($url)" else url
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
