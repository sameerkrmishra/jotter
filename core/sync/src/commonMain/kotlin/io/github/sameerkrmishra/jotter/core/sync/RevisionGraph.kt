package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind

/**
 * The revision DAG of one note, as far as this device knows it. Pure, and a function of the set of
 * revisions alone, which is what makes two devices with the same revisions agree (ADR-0003).
 *
 * Only valid revisions are in it. A revision is judged by itself and its ancestors alone, so that
 * every device with the same revisions judges alike, in any order of arrival (ADR-0014):
 *
 * - A user revision must be stamped above each parent known so far.
 * - A merge counts only once both parents are here, and must have the stamp and loser they decide.
 *   Until then it is treated as not arrived.
 * - A revision on an invalid one is invalid.
 * - A revision that is in [refused], or has a parent in it, is invalid.
 *
 * @param refused Ids that cannot count: parents stored under another note, and ids that two
 *   different revisions were seen under.
 */
@Suppress("TooManyFunctions") // The questions asked of a DAG, kept together on purpose.
internal class RevisionGraph(
    revisions: Collection<Revision>,
    private val refused: Set<RevisionId> = emptySet(),
) {
    private val judged = judge(revisions, refused)
    private val byId = judged.valid.associateBy { it.id }
    private val parentsSeen = judged.valid.flatMapTo(HashSet()) { it.parents }
    private val userRevisionsOf = HashMap<RevisionId, Set<RevisionId>>()
    private val settled = judged.valid.mapNotNullTo(HashSet()) { it.resolves }

    /** The revisions that do not count. They and their text stay out of everything here. */
    val invalid: List<Revision> = judged.invalid

    /** The invalid revisions with text that no other invalid revision goes on from. */
    val invalidEnds: List<Revision>
        get() {
            val built = invalid.flatMapTo(HashSet()) { it.parents }
            return invalid.filter {
                it.id !in built && it.id !in refused && it.content is RevisionContent.Snapshot
            }
        }

    /** The revisions nothing is built on, sorted by id: the order of the fold. */
    val heads: List<Revision> = judged.valid.filter { it.id !in parentsSeen }.sortedBy { it.id }

    /** Whether every parent is here. */
    val isComplete: Boolean = parentsSeen.all { it in byId }

    /** Returns the stored revision [id], or null. */
    operator fun get(id: RevisionId): Revision? = byId[id]

    /**
     * Returns [id] and everything it descends from, through tombstones too, as far as it is known.
     * This is for what happened before a revision: who wrote what, which conflict it stands on. For
     * what its text came from, see [textAncestors].
     */
    fun ancestors(id: RevisionId): Set<RevisionId> = reachableFrom(listOf(id))

    /**
     * Returns [id] and what its text came from: the ancestors reached without passing a tombstone,
     * which is not one of them. A deletion merged with an edit holds the edit's text alone, so what
     * lies behind the deletion says nothing about it.
     */
    fun textAncestors(id: RevisionId): Set<RevisionId> =
        reachableFrom(listOf(id), throughTombstones = false)
            .filterTo(HashSet()) { byId[it]?.content is RevisionContent.Snapshot }

    /**
     * Returns the best common ancestors of [a] and [b]: the common [textAncestors], of which none
     * is an ancestor of another. A tombstone has no text, and a walk does not go through it.
     */
    fun bestCommonAncestors(
        a: RevisionId,
        b: RevisionId,
    ): List<Revision> {
        val common = (textAncestors(a) intersect textAncestors(b)).mapNotNull(byId::get)
        val dominated = reachableFrom(common.flatMap { it.parents }, throughTombstones = false)
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

    /**
     * Whether [a] and [b] can be merged the same way on every device: all that a merge reads is
     * here. A missing revision below a common ancestor does not matter, as that ancestor hides it.
     * One above, on the way from either to what they share, could be the best ancestor itself.
     */
    fun canMerge(
        a: Revision,
        b: Revision,
    ): Boolean {
        val bothHaveText =
            a.content is RevisionContent.Snapshot && b.content is RevisionContent.Snapshot
        if (!bothHaveText) return true
        val common = textAncestors(a.id) intersect textAncestors(b.id)
        return reachesNoGap(a.id, common) && reachesNoGap(b.id, common)
    }

    /** Returns the roots among the text ancestors of [a] and [b] (ADR-0009). */
    fun roots(
        a: RevisionId,
        b: RevisionId,
    ): List<Revision> =
        (textAncestors(a) + textAncestors(b))
            .mapNotNull(byId::get)
            .filter { it.parents.isEmpty() }

    /**
     * The conflicted merges nothing has closed, by id. A resolution closes the merge it names. A
     * tombstone that descends from it closes it only if no head with text descends from it too,
     * other than through a tombstone: else an edit made beside the deletion goes on from the
     * conflict, and the text that lost would be shown nowhere.
     */
    val openConflicts: List<Revision> = findOpenConflicts()

    private val openIds = openConflicts.mapTo(HashSet()) { it.id }

    /**
     * The [heads] that still count: all but the covered ones. An automatic merge adds nothing of
     * its own, so a merge head whose user revisions another head also has is covered and needs no
     * further merge. Of heads with the same user revisions one with text stays, then the lowest
     * id. A head that descends from an open conflict stays too, as a resolution builds on it.
     * ADR-0013 explains why: without this rule, devices that sync out of step make new merges for
     * ever. A head that a resolution names does not count either: the user has dealt with it
     * (ADR-0014).
     */
    val liveHeads: List<Revision> = heads.filterNot { it.id in settled || isCovered(it) }

    /**
     * The live heads with text that the note does not show. A merge is waiting for an open
     * conflict or a missing ancestor, and until then these are surfaced, so that an edit is never
     * hidden in silence (ADR-0014).
     */
    val hiddenHeads: List<Revision>
        get() {
            val shown = shownHead()?.id
            return liveHeads.filter { it.content is RevisionContent.Snapshot && it.id != shown }
        }

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

    /** Returns the heads whose text goes back to [id], the newest first. */
    fun headsDescendingFrom(id: RevisionId): List<Revision> =
        heads.filter { id in textAncestors(it.id) }.sortedByDescending { it.stamp }

    private fun descendsFromOpenConflict(head: Revision): Boolean =
        openIds.isNotEmpty() && textAncestors(head.id).any { it in openIds }

    private fun isCovered(head: Revision): Boolean {
        if (descendsFromOpenConflict(head)) return false
        return heads.any { other ->
            other.id != head.id &&
                (isCoveredBy(head, other) || isTwinOf(head, other))
        }
    }

    /**
     * Whether [other] has the same user revisions as the merge [head] and goes first: the one with
     * text, as an edit beats a deletion, and otherwise the lower id.
     */
    private fun isTwinOf(
        head: Revision,
        other: Revision,
    ): Boolean {
        val headHasText = head.content is RevisionContent.Snapshot
        val otherHasText = other.content is RevisionContent.Snapshot
        val goesFirst = if (headHasText == otherHasText) other.id < head.id else otherHasText
        return head.kind != RevisionKind.User &&
            goesFirst &&
            userRevisions(head) == userRevisions(other)
    }

    /**
     * The user revisions behind [revision], through tombstones too. A merge has all of its
     * parents', which is what ends the merging (ADR-0013), so this walk must not stop at one.
     */
    private fun userRevisions(revision: Revision): Set<RevisionId> =
        userRevisionsOf.getOrPut(revision.id) {
            ancestors(revision.id).filterTo(HashSet()) { byId[it]?.kind == RevisionKind.User }
        }

    private fun findOpenConflicts(): List<Revision> {
        val conflicted = byId.values.filter { it.isConflictedMerge() && it.id !in settled }
        if (conflicted.isEmpty()) return emptyList()
        val tombstones = byId.values.filter { it.content is RevisionContent.Tombstone }
        val deleted = reachableFrom(tombstones.map { it.id })
        val alive = heads.filter { it.content is RevisionContent.Snapshot }
        val withText = reachableFrom(alive.map { it.id }, throughTombstones = false)
        return conflicted.filter { it.id !in deleted || it.id in withText }.sortedBy { it.id }
    }

    /** Whether every way down from [start] reaches [common] or a tombstone, not a missing one. */
    private fun reachesNoGap(
        start: RevisionId,
        common: Set<RevisionId>,
    ): Boolean {
        val seen = HashSet<RevisionId>()
        val stack = ArrayDeque(listOf(start))
        var isWhole = true
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (id !in common && seen.add(id)) {
                val revision = byId[id]
                if (revision == null) {
                    isWhole = false
                } else if (revision.content is RevisionContent.Snapshot) {
                    stack.addAll(revision.parents)
                }
            }
        }
        return isWhole
    }

    /**
     * Depth-first without recursion: a note's history can be far deeper than the stack. Without
     * [throughTombstones] a walk stops at a tombstone: an edit merged with a deletion descends
     * from what was deleted, but holds nothing of it.
     */
    private fun reachableFrom(
        starts: List<RevisionId>,
        throughTombstones: Boolean = true,
    ): Set<RevisionId> {
        val seen = HashSet<RevisionId>()
        val stack = ArrayDeque(starts)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (seen.add(id)) {
                byId[id]
                    ?.takeIf { throughTombstones || it.content is RevisionContent.Snapshot }
                    ?.let { stack.addAll(it.parents) }
            }
        }
        return seen
    }
}

/** Whether this is a conflicted automatic merge. */
internal fun Revision.isConflictedMerge(): Boolean =
    (kind as? RevisionKind.AutomaticMerge)?.isConflicted == true

private class Judged(
    val valid: List<Revision>,
    val invalid: List<Revision>,
)

/**
 * Sorts [revisions] parents first, and judges each from its parents' verdicts alone. A revision in
 * a cycle is never reached, and is invalid: a cycle needs a child stamped below its parent.
 */
private fun judge(
    revisions: Collection<Revision>,
    refused: Set<RevisionId>,
): Judged {
    val byId = revisions.associateBy { it.id }
    val children = HashMap<RevisionId, MutableList<Revision>>()
    val waitingFor = HashMap<RevisionId, Int>()
    revisions.forEach { revision ->
        val present = revision.parents.filter { it in byId }
        waitingFor[revision.id] = present.size
        present.forEach { children.getOrPut(it) { mutableListOf() } += revision }
    }
    val verdicts = HashMap<RevisionId, Verdict>()
    val ready = ArrayDeque(revisions.filter { waitingFor[it.id] == 0 })
    while (ready.isNotEmpty()) {
        val revision = ready.removeLast()
        verdicts[revision.id] = verdictOn(revision, byId, verdicts, refused)
        children[revision.id].orEmpty().forEach { child ->
            val left = waitingFor.getValue(child.id) - 1
            waitingFor[child.id] = left
            if (left == 0) ready.addLast(child)
        }
    }
    return Judged(
        valid = revisions.filter { verdicts[it.id] == Verdict.VALID },
        invalid = revisions.filter { it.id !in verdicts || verdicts[it.id] == Verdict.INVALID },
    )
}

private enum class Verdict {
    VALID,
    INVALID,

    /** A merge without both parents: as if it had not arrived. */
    NOT_YET,
}

private fun verdictOn(
    revision: Revision,
    byId: Map<RevisionId, Revision>,
    verdicts: Map<RevisionId, Verdict>,
    refused: Set<RevisionId>,
): Verdict {
    val parents = revision.parents.mapNotNull(byId::get)
    val parentVerdicts = parents.mapNotNull { verdicts[it.id] }
    val isBad =
        Verdict.INVALID in parentVerdicts ||
            revision.id in refused ||
            revision.parents.any { it in refused }
    val here = parents.filter { verdicts[it.id] == Verdict.VALID }
    return when {
        isBad -> Verdict.INVALID
        revision.kind == RevisionKind.User -> userVerdict(revision, here)
        here.size < revision.parents.size -> Verdict.NOT_YET
        else -> mergeVerdict(revision, here)
    }
}

private fun userVerdict(
    revision: Revision,
    parents: List<Revision>,
): Verdict {
    // No clock can go above the highest timestamp, so an edit on such a head could not be made.
    val isPossible =
        revision.timestamp != HybridTimestamp.MAX &&
            parents.all { revision.timestamp > it.timestamp }
    return if (isPossible) Verdict.VALID else Verdict.INVALID
}

private fun mergeVerdict(
    revision: Revision,
    parents: List<Revision>,
): Verdict {
    val losingParent = (revision.kind as RevisionKind.AutomaticMerge).losingParent
    val higher = parents.maxBy { it.stamp }
    val lower = parents.minBy { it.stamp }
    val followsParents =
        revision.timestamp == higher.timestamp &&
            revision.deviceId == higher.deviceId &&
            (losingParent == null || (losingParent == lower.id && parents.all(::hasText)))
    return if (followsParents) Verdict.VALID else Verdict.INVALID
}

private fun hasText(revision: Revision): Boolean = revision.content is RevisionContent.Snapshot
