package io.github.sameerkrmishra.jotter.core.merge

import kotlin.math.abs

/**
 * The most edits one shortest-path search explores before it settles for the furthest point it
 * reached and searches on from there.
 *
 * Two bodies of up to 512 lines each are always diffed exactly, whatever was done to them. Above
 * that a diff stays correct but may be longer than the shortest. This number decides the merged
 * text of large bodies, and an automatic merge's id hashes that text, so changing it is a change
 * to the sync format.
 */
internal const val MAX_EDITS: Int = 1024

private const val UNREACHABLE = -1

/**
 * One changed stretch: base lines [baseStart] until [baseEnd] became side lines [sideStart] until
 * [sideEnd]. Either range may be empty, and hunks of one diff have an unchanged line between them.
 */
internal data class Hunk(
    val baseStart: Int,
    val baseEnd: Int,
    val sideStart: Int,
    val sideEnd: Int,
)

/** Counts the steps of [diff], so tests can bound its work without looking at a clock. */
internal class DiffCost {
    var steps: Long = 0
}

/**
 * The hunks that turn [base] into [side], which are interned lines.
 *
 * Myers' greedy O(ND) algorithm, where a search of more than [MAX_EDITS] edits stops and restarts
 * from the furthest point reached. Every restart is at least [MAX_EDITS] lines further on, so the
 * work is O((N + M) * [MAX_EDITS]) in time and O([MAX_EDITS] ^ 2) in memory even for two texts
 * with nothing in common, where plain Myers would be quadratic. The result depends only on the
 * input.
 */
internal fun diff(
    base: IntArray,
    side: IntArray,
    cost: DiffCost = DiffCost(),
): List<Hunk> {
    var start = 0
    while (start < base.size && start < side.size && base[start] == side[start]) start += 1
    var baseEnd = base.size
    var sideEnd = side.size
    while (baseEnd > start && sideEnd > start && base[baseEnd - 1] == side[sideEnd - 1]) {
        baseEnd -= 1
        sideEnd -= 1
    }
    val walk = EditWalk(base, side, baseEnd, sideEnd, cost)
    walk.walkFrom(start)
    return hunks(walk.deleted, walk.inserted)
}

/** Groups marked lines into hunks. Unmarked lines of the two sides pair up in order. */
private fun hunks(
    deleted: BooleanArray,
    inserted: BooleanArray,
): List<Hunk> {
    val hunks = ArrayList<Hunk>()
    var baseLine = 0
    var sideLine = 0
    while (baseLine < deleted.size || sideLine < inserted.size) {
        val baseStart = baseLine
        val sideStart = sideLine
        while (baseLine < deleted.size && deleted[baseLine]) baseLine += 1
        while (sideLine < inserted.size && inserted[sideLine]) sideLine += 1
        if (baseLine > baseStart || sideLine > sideStart) {
            hunks += Hunk(baseStart, baseLine, sideStart, sideLine)
        } else {
            baseLine += 1
            sideLine += 1
        }
    }
    return hunks
}

/**
 * Finds a path through the edit graph up to ([baseEnd], [sideEnd]) and marks the lines it deletes
 * and inserts. A point is (x, y), lines consumed of base and of side, and its diagonal is x - y.
 * A layer holds, per diagonal in reach of its depth, the furthest x that many edits can get to.
 */
private class EditWalk(
    private val base: IntArray,
    private val side: IntArray,
    private val baseEnd: Int,
    private val sideEnd: Int,
    private val cost: DiffCost,
) {
    val deleted = BooleanArray(base.size)
    val inserted = BooleanArray(side.size)

    fun walkFrom(start: Int) {
        var x = start
        var y = start
        while (x + y < baseEnd + sideEnd) {
            val origin = x - y
            val layers = explore(x, origin)
            val index = furthest(layers.last(), origin)
            markEdits(layers, index, origin)
            x = layers.last()[index]
            y = x - diagonal(origin, index, layers.lastIndex)
        }
    }

    private fun explore(
        x: Int,
        origin: Int,
    ): List<IntArray> {
        val layers = arrayListOf(intArrayOf(slide(x, origin)))
        while (layers.size <= MAX_EDITS && !reachedEnd(layers.last(), origin)) {
            layers += nextLayer(layers.last(), origin)
        }
        return layers
    }

    private fun nextLayer(
        previous: IntArray,
        origin: Int,
    ): IntArray =
        IntArray(previous.size + 1) { index ->
            val diagonal = diagonal(origin, index, previous.size)
            val entry =
                maxOf(afterDeleting(previous, index), afterInserting(previous, index, diagonal))
            if (entry == UNREACHABLE) UNREACHABLE else slide(entry, diagonal)
        }

    /** The x after deleting one base line from the point before [index] in [previous]. */
    private fun afterDeleting(
        previous: IntArray,
        index: Int,
    ): Int {
        val x = previous.getOrElse(index - 1) { UNREACHABLE }
        return if (x in 0 until baseEnd) x + 1 else UNREACHABLE
    }

    /** The x on [diagonal] after inserting one side line from the point at [index]. */
    private fun afterInserting(
        previous: IntArray,
        index: Int,
        diagonal: Int,
    ): Int {
        val x = previous.getOrElse(index) { UNREACHABLE }
        return if (x >= 0 && x - diagonal - 1 < sideEnd) x else UNREACHABLE
    }

    /** Follows equal lines from x on [diagonal], which costs no edits. */
    private fun slide(
        from: Int,
        diagonal: Int,
    ): Int {
        var x = from
        cost.steps += 1
        while (x < baseEnd && x - diagonal < sideEnd && base[x] == side[x - diagonal]) {
            x += 1
            cost.steps += 1
        }
        return x
    }

    private fun reachedEnd(
        layer: IntArray,
        origin: Int,
    ): Boolean =
        layer.indices.any { index ->
            layer[index] == baseEnd &&
                baseEnd - diagonal(origin, index, layer.lastIndex) == sideEnd
        }

    /**
     * The point with the most lines consumed. A tie goes to the point nearest the search's own
     * diagonal, because a rewrite tends to replace about as many lines as it adds, and a point far
     * off that diagonal walks past the lines the two texts still share. Then to the first.
     */
    private fun furthest(
        layer: IntArray,
        origin: Int,
    ): Int {
        val depth = layer.lastIndex
        return layer.indices.maxWith(
            compareBy<Int> { index ->
                val x = layer[index]
                if (x == UNREACHABLE) Int.MIN_VALUE else 2 * x - diagonal(origin, index, depth)
            }.thenBy { index -> -abs(2 * index - depth) },
        )
    }

    private fun markEdits(
        layers: List<IntArray>,
        finalIndex: Int,
        origin: Int,
    ) {
        var index = finalIndex
        for (depth in layers.lastIndex downTo 1) {
            val previous = layers[depth - 1]
            val diagonal = diagonal(origin, index, depth)
            val afterDeleting = afterDeleting(previous, index)
            val afterInserting = afterInserting(previous, index, diagonal)
            if (afterDeleting >= afterInserting) {
                deleted[afterDeleting - 1] = true
                index -= 1
            } else {
                inserted[afterInserting - diagonal - 1] = true
            }
        }
    }

    private fun diagonal(
        origin: Int,
        index: Int,
        depth: Int,
    ): Int = origin + 2 * index - depth
}
