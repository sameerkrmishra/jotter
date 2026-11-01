package io.github.sameerkrmishra.jotter.interop.keepimport

import io.github.sameerkrmishra.jotter.core.model.NoteId

/**
 * What a [KeepImporter.import] run did. Every document it reached ends up in exactly one of
 * [imported], [skipped] and [failures].
 *
 * @property imported The notes that were new and were stored.
 * @property skipped The notes that have a history here, possibly ending in deletion forever, and
 *   were left as they are. This includes newer Keep edits of a note imported before (ADR-0009).
 * @property warnings What was left out of the imported notes. Skipped and failed notes have none.
 * @property failures The documents that were not imported, and why.
 * @property stopped Whether the database stayed locked, so the run ended at the last of [failures]
 *   and the documents after it were not looked at. Running the import again finishes the job.
 */
public data class KeepImportReport(
    val imported: List<KeepNoteRef>,
    val skipped: List<KeepNoteRef>,
    val warnings: List<KeepImportWarning>,
    val failures: List<KeepImportFailure>,
    val stopped: Boolean,
)

/**
 * A document and the note it became.
 *
 * @property source The [KeepDocument.name].
 * @property id The note's id.
 */
public data class KeepNoteRef(
    val source: String,
    val id: NoteId,
)

/**
 * Something in a document that the imported note does not have.
 *
 * @property source The [KeepDocument.name].
 * @property kind What happened.
 * @property detail The attachment's file path or the label; empty for a missing time.
 */
public data class KeepImportWarning(
    val source: String,
    val kind: Kind,
    val detail: String,
) {
    /** The reasons for a [KeepImportWarning]. */
    public enum class Kind {
        /** The note had an attachment. Jotter v1 has no attachments, so only its text came in. */
        ATTACHMENT_DROPPED,

        /** A label is not a valid tag. The note came in without it. */
        INVALID_TAG,

        /** The edited time is missing or not positive, so the created time stands in for it. */
        MISSING_EDITED_TIME,
    }
}

/**
 * A document that was not imported.
 *
 * @property source The [KeepDocument.name].
 * @property kind What happened.
 * @property detail The parser's or database's message, or for a duplicate the name of the first
 *   document with the id; empty for a missing time.
 */
public data class KeepImportFailure(
    val source: String,
    val kind: Kind,
    val detail: String,
) {
    /** The reasons for a [KeepImportFailure]. */
    public enum class Kind {
        /** The document is not JSON, not an object, or has a field of the wrong type. */
        MALFORMED_JSON,

        /**
         * The created time is missing or not positive. The note id comes from it, and no other
         * field would stay the same between Takeouts.
         */
        MISSING_CREATED_TIME,

        /** An earlier document of this import has the same id, so the same created time. */
        DUPLICATE_ID,

        /** Another process kept the database locked. This ends the run. */
        STORAGE_BUSY,

        /** The database could not store the note, for example because it has too many tags. */
        STORAGE_ERROR,
    }
}
