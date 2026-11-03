package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.storage.HiddenReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidReason
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver

/**
 * `jotter conflicts`: what sync left for the user on each note, from core:sync's
 * [ConflictResolver]. With an id, it prints every text of that note, so nothing hides behind the
 * version the note shows. Answering is the desktop app's: the CLI only reads.
 */
internal class ConflictsCommand(
    private val location: DatabaseLocation,
) : CoreCliktCommand(name = "conflicts") {
    private val id by argument(
        name = "ID",
        help = "A note id, or a unique start of one.",
    ).optional()

    override fun help(context: Context): String =
        "List what sync needs you to look at, as tab-separated lines: id, kind, summary. " +
            "With an id, print every version of that note."

    override fun run() {
        withStore(location.path) { store ->
            val items = itemsOf(store)
            val wanted = id
            if (wanted == null) {
                items.forEach { echo(listOf(it.noteId, it.kind, it.summary).joinToString("\t")) }
            } else {
                // A whole id needs no note in the list: one whose text no longer counts has none.
                val noteId = NoteId.parseOrNull(wanted) ?: store.notes.resolve(wanted).id
                echo(
                    items.filter { it.noteId == noteId }.flatMap { it.versions }.joinToString(
                        "\n\n",
                    ),
                )
            }
        }
    }

    private fun itemsOf(store: NoteStore): List<Item> {
        val resolver = ConflictResolver(store)
        val conflicts =
            resolver.open().mapNotNull { resolver.details(it.mergeId) }.map { details ->
                val conflict = details.conflict
                Item(
                    conflict.noteId,
                    "conflict",
                    store.summaryOf(conflict.noteId, details.shownTitle, details.shownBody),
                    listOf(
                        version("conflict", "shown", details.shownTitle, details.shownBody),
                        version("conflict", "other", conflict.losingTitle, conflict.losingBody),
                    ),
                )
            }
        val waiting =
            resolver.hidden().map { head ->
                val note = store.notes.get(head.noteId)
                val kind =
                    if (head.reason ==
                        HiddenReason.OPEN_CONFLICT
                    ) {
                        "waiting-for-conflict"
                    } else {
                        "waiting"
                    }
                Item(
                    head.noteId,
                    kind,
                    store.summaryOf(head.noteId, head.title, head.body),
                    listOfNotNull(
                        note?.let { version(kind, "shown", it.title, it.body) },
                        version(kind, "waiting", head.title, head.body),
                    ),
                )
            }
        val setAside =
            resolver.invalid().map { text ->
                Item(
                    text.noteId,
                    "set-aside",
                    store.summaryOf(text.noteId, text.title, text.body),
                    listOf(version("set-aside", reasonName(text.reason), text.title, text.body)),
                )
            }
        return conflicts + waiting + setAside
    }

    private class Item(
        val noteId: NoteId,
        val kind: String,
        val summary: String,
        val versions: List<String>,
    )
}

/** The summary of the note [id] if it is in the list, or else of the text [title] and [body]. */
private fun NoteStore.summaryOf(
    id: NoteId,
    title: String,
    body: String,
): String = notes.get(id)?.let { summary(it.title, it.body) } ?: summary(title, body)

private fun reasonName(reason: InvalidReason): String =
    when (reason) {
        InvalidReason.BAD_HISTORY -> "bad-history"
        InvalidReason.MALFORMED -> "damaged"
        InvalidReason.ID_COLLISION -> "collision"
    }

/** One text of a note: `kind:`, `version:` and `title:` headers, a blank line, the body. */
private fun version(
    kind: String,
    version: String,
    title: String,
    body: String,
): String = "kind: $kind\nversion: $version\ntitle: ${oneLine(title)}\n\n$body"
