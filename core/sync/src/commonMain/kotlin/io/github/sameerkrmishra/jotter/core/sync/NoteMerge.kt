package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.merge.MergeResult
import io.github.sameerkrmishra.jotter.core.merge.merge
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.Stamped
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.MAX_NOTE_CHARS
import io.github.sameerkrmishra.jotter.core.storage.isStorableText

/**
 * Merges the two heads [a] and [b] into an automatic merge (PLAN.md §5). Every field of the result
 * comes from them and their ancestors in [graph], so every device computes the same revision.
 * [RevisionGraph.canMerge] must allow it.
 */
internal fun mergeRevisions(
    graph: RevisionGraph,
    a: Revision,
    b: Revision,
): Revision {
    val first = a.content
    val second = b.content
    val winner = maxOf(a, b, compareBy { it.stamp })
    val merged =
        when {
            first is RevisionContent.Snapshot && second is RevisionContent.Snapshot -> {
                mergeSnapshots(graph, a, first, b, second)
            }

            first is RevisionContent.Snapshot -> {
                SideMerge(first.backInTrash(b), losingParent = null)
            }

            second is RevisionContent.Snapshot -> {
                SideMerge(second.backInTrash(a), losingParent = null)
            }

            else -> {
                SideMerge(RevisionContent.Tombstone, losingParent = null)
            }
        }
    return buildMerge(a, b, winner, merged)
}

private class SideMerge(
    val content: RevisionContent,
    val losingParent: RevisionId?,
)

private fun buildMerge(
    a: Revision,
    b: Revision,
    winner: Revision,
    merged: SideMerge,
): Revision {
    // The hash covers everything but the id, and a Revision cannot exist without one.
    val unhashed =
        Revision(
            id = PLACEHOLDER_ID,
            noteId = a.noteId,
            parents = setOf(a.id, b.id),
            timestamp = winner.timestamp,
            deviceId = winner.deviceId,
            kind = RevisionKind.AutomaticMerge(merged.losingParent),
            content = merged.content,
        )
    return unhashed.copy(id = mergeRevisionId(unhashed))
}

/**
 * The edit beats the deletion, and the note comes back in the trash. Its state carries the stamp
 * of [tombstone]: the deletion put it there, and a merge has no stamp of its own to give.
 */
private fun RevisionContent.Snapshot.backInTrash(tombstone: Revision): RevisionContent.Snapshot =
    copy(state = Stamped(NoteState.TRASHED, tombstone.stamp))

private fun mergeSnapshots(
    graph: RevisionGraph,
    a: Revision,
    first: RevisionContent.Snapshot,
    b: Revision,
    second: RevisionContent.Snapshot,
): SideMerge {
    val base = graph.mergeBase(a.id, b.id)?.content as? RevisionContent.Snapshot
    val title = mergeText(graph.baseText(a, b) { it.title }, first.title, second.title)
    val body = mergeText(graph.baseText(a, b) { it.body }, first.body, second.body)
    val higher = if (a.stamp > b.stamp) first else second
    // A clean merge of two notes under the cap can pass it, and the cap is what makes it sync.
    val isConflicted = title == null || body == null || title.length + body.length > MAX_NOTE_CHARS
    val content =
        RevisionContent.Snapshot(
            title = title.takeUnless { isConflicted } ?: higher.title,
            body = body.takeUnless { isConflicted } ?: higher.body,
            tags = mergeTags(graph, base?.tags.orEmpty(), a, first, b, second),
            pinned = maxOf(first.pinned, second.pinned, compareBy { it.stamp }),
            state = maxOf(first.state, second.state, compareBy { it.stamp }),
        )
    val loser = if (a.stamp > b.stamp) b else a
    return SideMerge(content, loser.id.takeIf { isConflicted })
}

/**
 * The text to merge [a] and [b] against: the merge base's, or with no common ancestor the rule of
 * ADR-0009, field by field: the value all roots agree on, or else nothing.
 */
internal fun RevisionGraph.baseText(
    a: Revision,
    b: Revision,
    field: (RevisionContent.Snapshot) -> String,
): String {
    val base = mergeBase(a.id, b.id)?.content as? RevisionContent.Snapshot
    if (base != null) return field(base)
    val values =
        roots(a.id, b.id)
            .mapNotNull { it.content as? RevisionContent.Snapshot }
            .map(field)
            .toSet()
    return values.singleOrNull().orEmpty()
}

/**
 * The merged text, or null for a conflict. A clean merge that SQLite could not store unchanged
 * counts as a conflict: removing a first line can move a U+FEFF to the start, where it does not
 * survive, and the text must not change once it is hashed.
 */
private fun mergeText(
    base: String,
    first: String,
    second: String,
): String? =
    (merge(base, first, second) as? MergeResult.Clean)?.text?.takeIf {
        it.isStorableText()
    }

/**
 * Tags on both sides stay, and a tag on one side stays if that side added it: it is not in [base],
 * or a user revision only that side has added it. So an add beats a concurrent remove, also for a
 * tag in [base] that one side removed and added back, which comparing with [base] cannot see.
 */
@Suppress("LongParameterList") // Two sides of a merge, each a revision with its snapshot.
private fun mergeTags(
    graph: RevisionGraph,
    base: Set<TagName>,
    a: Revision,
    first: RevisionContent.Snapshot,
    b: Revision,
    second: RevisionContent.Snapshot,
): Set<TagName> {
    val onlyFirst = first.tags - second.tags
    val onlySecond = second.tags - first.tags
    val addedFirst = tagsAddedSince(graph, a, b)
    val addedSecond = tagsAddedSince(graph, b, a)
    return (first.tags intersect second.tags) +
        onlyFirst.filter { it !in base || it in addedFirst } +
        onlySecond.filter { it !in base || it in addedSecond }
}

/** The tags that user revisions in the history of [side], but not of [other], added. */
private fun tagsAddedSince(
    graph: RevisionGraph,
    side: Revision,
    other: Revision,
): Set<TagName> {
    val added = HashSet<TagName>()
    (graph.textAncestors(side.id) - graph.textAncestors(other.id)).forEach { id ->
        val revision = graph[id]
        val tags = (revision?.content as? RevisionContent.Snapshot)?.tags.orEmpty()
        if (revision?.kind == RevisionKind.User) {
            val before =
                revision.parents
                    .firstOrNull()
                    ?.let(graph::get)
                    ?.content
            added += tags - (before as? RevisionContent.Snapshot)?.tags.orEmpty()
        }
    }
    return added
}

/** Stands in until the hash is known. Never stored: [buildMerge] replaces it. */
private val PLACEHOLDER_ID = RevisionId.fromMergeHash(ByteArray(RevisionId.MERGE_HASH_SIZE))
