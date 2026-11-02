package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind

/**
 * What the history says about one line at one revision. Worked out from the DAG alone, with none
 * of the rules of the code under test but the shape of the revisions, so that a wrong merge shows.
 */
internal sealed interface LineState {
    /** The text holds the line. */
    data object Here : LineState

    /** This branch never held it. */
    data object Never : LineState

    /** A person took it out of the text: an edit or a resolution. It stays out. */
    data object Removed : LineState

    /** A person deleted the note that held it. An edit beside that keeps the line. */
    data object Deleted : LineState

    /** Only the side of [conflict] that lost has it, so that conflict must show it. */
    data class Waiting(
        val conflict: RevisionId,
    ) : LineState

    /** It is gone, and no path through the history explains that. */
    data object Lost : LineState
}

/** Follows [line] through [graph]. */
internal class LineHistory(
    private val graph: RevisionGraph,
    private val line: String,
) {
    private val memo = HashMap<RevisionId, LineState>()

    /** Returns what the history says about the line at [revision]. */
    fun stateAt(revision: Revision): LineState = memo.getOrPut(revision.id) { work(revision) }

    private fun holds(revision: Revision): Boolean =
        (revision.content as? RevisionContent.Snapshot)?.body?.split('\n')?.contains(line) == true

    private fun work(revision: Revision): LineState {
        val parents = revision.parents.map(graph::get)
        return when {
            holds(revision) -> LineState.Here
            revision.kind == RevisionKind.User ->
                afterUser(
                    revision,
                    parents.firstOrNull(),
                    parents,
                )
            else -> afterMerge(revision, parents.map { checkNotNull(it) })
        }
    }

    private fun afterUser(
        revision: Revision,
        parent: Revision?,
        parents: List<Revision?>,
    ): LineState {
        val before = parent?.let(::stateAt)
        return when {
            parents.isEmpty() -> LineState.Never

            // A parent that never arrived explains anything: nothing is known of that history.
            before == null -> LineState.Removed

            revision.content is RevisionContent.Tombstone -> {
                if (before == LineState.Here || before is LineState.Waiting) {
                    LineState.Deleted
                } else {
                    before
                }
            }

            before == LineState.Here -> LineState.Removed
            before is LineState.Waiting && before.conflict == revision.resolves -> LineState.Removed
            else -> before
        }
    }

    private fun afterMerge(
        revision: Revision,
        parents: List<Revision>,
    ): LineState {
        val (a, b) = parents
        val first = stateAt(a)
        val second = stateAt(b)
        val merge = revision.kind as RevisionKind.AutomaticMerge
        val isDeletion = revision.content is RevisionContent.Tombstone
        val tombstone = parents.firstOrNull { it.content is RevisionContent.Tombstone }
        return when {
            isDeletion || tombstone != null -> combineWithDeletion(revision, parents)
            merge.losingParent != null ->
                afterConflict(
                    revision,
                    merge.losingParent!!,
                    first,
                    second,
                )
            first == LineState.Here -> besideLine(second)
            second == LineState.Here -> besideLine(first)
            else -> strongest(first, second)
        }
    }

    /** The side with text stands alone: the deletion takes nothing from it. */
    private fun combineWithDeletion(
        revision: Revision,
        parents: List<Revision>,
    ): LineState {
        val states = parents.map(::stateAt)
        val text = parents.filter { it.content is RevisionContent.Snapshot }.map(::stateAt)
        return when {
            revision.content is RevisionContent.Tombstone -> strongest(states[0], states[1])
            text.singleOrNull() == LineState.Never -> strongest(states[0], states[1])
            else -> text.single()
        }
    }

    private fun afterConflict(
        revision: Revision,
        loser: RevisionId,
        first: LineState,
        second: LineState,
    ): LineState {
        val losing = revision.parents.indexOf(loser).let { if (it == 0) first else second }
        return if (losing ==
            LineState.Here
        ) {
            LineState.Waiting(revision.id)
        } else {
            strongest(first, second)
        }
    }

    /** A clean merge left out a line one side had: the other side must have removed it. */
    private fun besideLine(other: LineState): LineState =
        when (other) {
            LineState.Removed -> LineState.Removed
            is LineState.Waiting -> other
            else -> LineState.Lost
        }

    private fun strongest(
        first: LineState,
        second: LineState,
    ): LineState {
        val order =
            listOf(LineState.Lost, LineState.Removed, LineState.Deleted, LineState.Never)
        val waiting = listOf(first, second).firstOrNull { it is LineState.Waiting }
        return when {
            first == LineState.Lost || second == LineState.Lost -> LineState.Lost
            waiting != null -> waiting
            else -> order.first { it == first || it == second }
        }
    }
}
