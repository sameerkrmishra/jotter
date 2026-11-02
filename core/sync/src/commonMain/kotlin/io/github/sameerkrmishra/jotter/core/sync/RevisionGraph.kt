package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind

/**
 * The revision DAG of one note, as far as this device knows it. Pure, and a function of the set of
 * revisions alone, which is what makes two devices with the same revisions agree (ADR-0003).
 */
@Suppress("TooManyFunctions") // The questions asked of a DAG, kept together on purpose.
internal class RevisionGraph(
    revisions: Collection<Revision>,
) {
    private val byId = revisions.associateBy { it.id }
    private val parentsSeen = revisions.flatMapTo(HashSet()) { it.parents }
    private val userRevisionsOf = HashMap<RevisionId, Set<RevisionId>>()

    /** The revisions nothing is built on, sorted by id: the order of the fold. */
    val heads: List<Revision> = revisions.filter { it.id !in parentsSeen }.sortedBy { it.id }

    /**
     * Whether every parent is here. Until then no merge may run: a merge reads the ancestors, so
     * without all of them two devices would compute different text from the same two heads.
     */
    val isComplete: Boolean = parentsSeen.all { it in byId }

    /** Returns the stored revision [id], or null. */
    operator fun get(id: RevisionId): Revision? = byId[id]

    /** Returns [id] and everything it descends from, as far as it is known. */
    fun ancestors(id: RevisionId): Set<RevisionId> = reachableFrom(listOf(id))

    /**
     * Returns the best common ancestors of [a] and [b] that have text: the common ancestors,
     * without tombstones, of which none is an ancestor of another. A tombstone is left out because
     * it has no text to merge against, and it does not hide the ancestors behind it.
     */
    fun bestCommonAncestors(
        a: RevisionId,
        b: RevisionId,
    ): List<Revision> {
        val common =
            (ancestors(a) intersect ancestors(b))
                .mapNotNull(byId::get)
                .filter { it.content is RevisionContent.Snapshot }
        val dominated = reachableFrom(common.flatMap { it.parents })
        return common.filter { it.id !in dominated }
    }

    /**
     * Returns the ancestor to merge [a] and [b] against: of the [bestCommonAncestors], the one
     * with the highest stamp, or null if they have none (ADR-0013).
     */
    fun mergeBase(
        a: RevisionId,
        b: RevisionId,
    ): Revision? = bestCommonAncestors(a, b).maxByOrNull { it.stamp }

    /** Returns the roots among the ancestors of [a] and [b] (ADR-0009). */
    fun roots(
        a: RevisionId,
        b: RevisionId,
    ): List<Revision> =
        (ancestors(a) + ancestors(b)).mapNotNull(byId::get).filter { it.parents.isEmpty() }

    /**
     * The conflicted merges nothing has closed, by id. A resolution closes the merge it names.
     * So does a tombstone that descends from it: nothing is left to show or to resolve, and a
     * deleted note must not hold back the heads next to it.
     */
    val openConflicts: List<Revision> = findOpenConflicts()

    private val openIds = openConflicts.mapTo(HashSet()) { it.id }

    /**
     * The [heads] that still count: all but the covered ones. An automatic merge adds nothing of
     * its own, so a merge head whose user revisions another head also has is covered and needs no
     * further merge. Of heads with the same user revisions the lowest id stays. A head that
     * descends from an open conflict stays too, as a resolution builds on it. ADR-0013 explains
     * why: without this rule, devices that sync out of step make new merges for ever.
     */
    val liveHeads: List<Revision> = heads.filterNot(::isCovered)

    /** Whether [revision] came from the user revisions of [other] alone. */
    fun isCoveredBy(
        revision: Revision,
        other: Revision,
    ): Boolean {
        if (revision.kind == RevisionKind.User) return false
        val own = userRevisions(revision)
        val others = userRevisions(other)
        return own.size < others.size && others.containsAll(own)
    }

    /**
     * Returns the head a note shows: the highest-stamped of the [liveHeads] that are not
     * tombstones, and of those the ones that descend from an open conflict, if any do (PLAN.md §5).
     * A tombstone only when no head has text. The note's edits build on this head.
     */
    fun shownHead(): Revision? {
        val alive = liveHeads.filter { it.content is RevisionContent.Snapshot }
        val inConflict = alive.filter(::descendsFromOpenConflict)
        return (inConflict.ifEmpty { alive.ifEmpty { liveHeads } }).maxByOrNull { it.stamp }
    }

    private fun descendsFromOpenConflict(head: Revision): Boolean =
        openIds.isNotEmpty() && ancestors(head.id).any { it in openIds }

    private fun isCovered(head: Revision): Boolean {
        if (descendsFromOpenConflict(head)) return false
        return heads.any { other ->
            other.id != head.id &&
                (isCoveredBy(head, other) || isTwinOf(head, other))
        }
    }

    /** Whether [other] has the same user revisions as the merge [head], and the lower id. */
    private fun isTwinOf(
        head: Revision,
        other: Revision,
    ): Boolean =
        head.kind != RevisionKind.User &&
            other.id < head.id &&
            userRevisions(head) == userRevisions(other)

    private fun userRevisions(revision: Revision): Set<RevisionId> =
        userRevisionsOf.getOrPut(revision.id) {
            ancestors(revision.id).filterTo(HashSet()) { byId[it]?.kind == RevisionKind.User }
        }

    /** Returns the heads that descend from [id], the newest first. */
    fun headsDescendingFrom(id: RevisionId): List<Revision> =
        heads.filter { id in ancestors(it.id) }.sortedByDescending { it.stamp }

    private fun findOpenConflicts(): List<Revision> {
        val closed = byId.values.mapNotNullTo(HashSet()) { it.resolves }
        val conflicted = byId.values.filter { it.isConflictedMerge() && it.id !in closed }
        if (conflicted.isEmpty()) return emptyList()
        val tombstones = byId.values.filter { it.content is RevisionContent.Tombstone }
        val deleted = reachableFrom(tombstones.map { it.id })
        return conflicted.filter { it.id !in deleted }.sortedBy { it.id }
    }

    /** Depth-first without recursion: a note's history can be far deeper than the stack. */
    private fun reachableFrom(starts: List<RevisionId>): Set<RevisionId> {
        val seen = HashSet<RevisionId>()
        val stack = ArrayDeque(starts)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (seen.add(id)) byId[id]?.let { stack.addAll(it.parents) }
        }
        return seen
    }
}

/** Whether this is a conflicted automatic merge. */
internal fun Revision.isConflictedMerge(): Boolean =
    (kind as? RevisionKind.AutomaticMerge)?.isConflicted == true
