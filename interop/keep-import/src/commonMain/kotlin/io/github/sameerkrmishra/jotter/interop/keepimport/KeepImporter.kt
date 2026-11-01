package io.github.sameerkrmishra.jotter.interop.keepimport

import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.ImportResult
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException

/**
 * One JSON file of the `Keep` folder of a Takeout.
 *
 * @property name What the report calls it, normally the file name.
 * @property json The file's text.
 */
public data class KeepDocument(
    val name: String,
    val json: String,
)

/**
 * Imports Google Keep notes from a Takeout into [notes] (PLAN.md §7, ADR-0009). See README.md for
 * how a note maps.
 *
 * @param notes The service to store the notes in.
 */
public class KeepImporter(
    private val notes: NoteService,
) {
    /**
     * Imports [documents] one by one, each note in its own transaction, and reports what happened
     * to each. A bad document does not stop the rest, and importing the same documents again
     * changes nothing, so this is safe to repeat.
     *
     * When two documents have the same note id, the later one in [documents] fails. Pass them in
     * a stable order, such as sorted by name, so that a repeated import agrees on which one.
     *
     * @throws StorageBusyException if another process kept the database locked. The notes before
     *   that point stay imported, and a later run skips them.
     */
    public fun import(documents: Sequence<KeepDocument>): KeepImportReport {
        val run = Run()
        documents.forEach(run::import)
        return KeepImportReport(run.imported, run.skipped, run.warnings, run.failures)
    }

    /** The state of one [import] call. */
    private inner class Run {
        val imported = mutableListOf<KeepNoteRef>()
        val skipped = mutableListOf<KeepNoteRef>()
        val warnings = mutableListOf<KeepImportWarning>()
        val failures = mutableListOf<KeepImportFailure>()

        /** The name of the first document with each note id. */
        private val firstWithId = mutableMapOf<NoteId, String>()

        fun import(document: KeepDocument) {
            val note = decode(document) ?: return
            val createdUsec = note.createdTimestampUsec?.takeIf { it > 0 }
            if (createdUsec == null) {
                fail(document.name, KeepImportFailure.Kind.MISSING_CREATED_TIME, "")
            } else {
                importUnlessDuplicate(document.name, note, createdUsec)
            }
        }

        private fun importUnlessDuplicate(
            source: String,
            note: KeepNote,
            createdUsec: Long,
        ) {
            val id = keepNoteId(createdUsec)
            val first = firstWithId[id]
            if (first != null) {
                fail(source, KeepImportFailure.Kind.DUPLICATE_ID, first)
            } else {
                firstWithId[id] = source
                store(source, id, note, createdUsec)
            }
        }

        private fun decode(document: KeepDocument): KeepNote? =
            try {
                keepJson.decodeFromString<KeepNote>(document.json)
            } catch (e: IllegalArgumentException) {
                // SerializationException is a subclass.
                fail(document.name, KeepImportFailure.Kind.MALFORMED_JSON, e.message.orEmpty())
                null
            }

        private fun store(
            source: String,
            id: NoteId,
            note: KeepNote,
            createdUsec: Long,
        ) {
            val tagsByLabel = note.labels.map(KeepLabel::name).associateWith(TagName::parseOrNull)
            val tags = tagsByLabel.values.filterNotNull().toSet()
            val editedUsec = note.userEditedTimestampUsec?.takeIf { it > 0 }
            val imported = note.toImportedNote(id, tags, createdUsec, editedUsec ?: createdUsec)
            val ref = KeepNoteRef(source, id)
            when (notes.importNote(imported)) {
                ImportResult.SKIPPED -> {
                    skipped += ref
                }

                ImportResult.IMPORTED -> {
                    this.imported += ref
                    val invalidLabels = tagsByLabel.filterValues { it == null }.keys
                    warnAbout(source, note, invalidLabels, editedUsec == null)
                }
            }
        }

        private fun warnAbout(
            source: String,
            note: KeepNote,
            invalidLabels: Set<String>,
            editedTimeIsMissing: Boolean,
        ) {
            note.attachments.forEach { attachment ->
                warn(source, KeepImportWarning.Kind.ATTACHMENT_DROPPED, attachment.filePath)
            }
            invalidLabels.forEach { label ->
                warn(source, KeepImportWarning.Kind.INVALID_TAG, label)
            }
            if (editedTimeIsMissing) warn(source, KeepImportWarning.Kind.MISSING_EDITED_TIME, "")
        }

        private fun warn(
            source: String,
            kind: KeepImportWarning.Kind,
            detail: String,
        ) {
            warnings += KeepImportWarning(source, kind, detail)
        }

        private fun fail(
            source: String,
            kind: KeepImportFailure.Kind,
            detail: String,
        ) {
            failures += KeepImportFailure(source, kind, detail)
        }
    }
}
