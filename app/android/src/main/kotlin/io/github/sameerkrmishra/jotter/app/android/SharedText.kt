package io.github.sameerkrmishra.jotter.app.android

import io.github.sameerkrmishra.jotter.core.storage.MAX_NOTE_CHARS

/** The longest title a share's subject becomes; a longer subject is cut there. */
internal const val MAX_SHARED_TITLE = 200

/** What another app shared with Jotter, read from its intent by [ShareActivity]. */
internal data class Share(
    /** Whether the intent is `ACTION_SEND`, the one action Jotter takes. */
    val isSend: Boolean,
    /** The intent's MIME type, such as `text/plain`, or null. */
    val type: String?,
    /** `EXTRA_TEXT`, or null. */
    val text: CharSequence?,
    /** `EXTRA_SUBJECT`, such as a web page's title, or null. */
    val subject: CharSequence?,
    /** Whether it carries a file or an image in `EXTRA_STREAM`. */
    val hasStream: Boolean,
)

/** What becomes of a [Share]. */
internal sealed interface ShareOutcome {
    /**
     * A new note with this [title] and [body]. [leftOut] says, in plain words, what of the share
     * was not taken, such as an attached image, or is null.
     */
    data class NewNote(
        val title: String,
        val body: String,
        val leftOut: String?,
    ) : ShareOutcome

    /** Nothing is saved, and [reason] says why in plain words. */
    data class Refused(
        val reason: String,
    ) : ShareOutcome
}

/** What a share of a file or an image is told. */
internal const val FILES_REFUSED: String =
    "Jotter keeps text only, so it cannot save shared files or images yet. Share the text " +
        "instead, or copy it into a note."

/** What an attachment beside shared text is told. */
internal const val ATTACHMENT_LEFT_OUT: String =
    "The attached file was left out: Jotter keeps text only."

/** What a share without text is told. */
internal const val NOTHING_TO_SAVE: String =
    "There was no text in what was shared, so nothing was saved."

/** What another action, such as sharing several items at once, is told. */
internal const val ONE_TEXT_ONLY: String =
    "Jotter can save one shared text at a time. Share the items one by one."

/**
 * Decides what [share] becomes: shared text becomes a new note, with the subject as its title
 * unless the subject only repeats the text; a file or an image is refused, as notes are text only
 * (PLAN.md §1). Text over the size a note can hold, [MAX_NOTE_CHARS], is refused whole rather
 * than cut, so that nothing is lost without the user knowing.
 */
internal fun outcomeOf(share: Share): ShareOutcome {
    val text = share.text?.toString().orEmpty()
    val title = titleOf(share.subject?.toString(), text)
    val chars = title.length.toLong() + text.length
    return when {
        !share.isSend -> ShareOutcome.Refused(ONE_TEXT_ONLY)
        text.isBlank() && share.hasStream -> ShareOutcome.Refused(FILES_REFUSED)
        text.isBlank() -> ShareOutcome.Refused(NOTHING_TO_SAVE)
        chars > MAX_NOTE_CHARS ->
            ShareOutcome.Refused(
                "The shared text is too long for one note: it has $chars characters, and a " +
                    "note holds at most $MAX_NOTE_CHARS. Nothing was saved.",
            )
        else -> ShareOutcome.NewNote(title, text, ATTACHMENT_LEFT_OUT.takeIf { share.hasStream })
    }
}

/**
 * The note's title from a share's [subject]: on one line, trimmed and at most [MAX_SHARED_TITLE]
 * characters, or empty if there is none or it only repeats [text], as some apps send the same
 * text twice.
 */
private fun titleOf(
    subject: String?,
    text: String,
): String {
    val line =
        subject
            .orEmpty()
            .lines()
            .joinToString(" ") { it.trim() }
            .trim()
    return when {
        line.isEmpty() || line == text.trim() -> ""
        line.length <= MAX_SHARED_TITLE -> line
        // Never half of a character outside the Basic Multilingual Plane, such as an emoji.
        else -> line.take(MAX_SHARED_TITLE - 1).dropLastWhile { it.isHighSurrogate() } + "…"
    }
}
