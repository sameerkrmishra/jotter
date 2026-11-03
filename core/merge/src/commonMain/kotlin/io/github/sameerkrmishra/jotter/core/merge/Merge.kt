package io.github.sameerkrmishra.jotter.core.merge

/** The outcome of [merge]. */
public sealed interface MergeResult {
    /**
     * Every change fitted together.
     *
     * @property text The merged text.
     */
    public data class Clean(
        val text: String,
    ) : MergeResult

    /**
     * At least one change could not be merged.
     *
     * @property chunks The text in order, with at least one [MergeChunk.Conflicting] among them.
     */
    public data class Conflict(
        val chunks: List<MergeChunk>,
    ) : MergeResult
}

/** A consecutive part of a [MergeResult.Conflict]. */
public sealed interface MergeChunk {
    /**
     * A part that merged.
     *
     * @property text The merged lines, with their line breaks.
     */
    public data class Resolved(
        val text: String,
    ) : MergeChunk

    /**
     * A part where the two sides disagree.
     *
     * @property ours The lines of the `ours` side here, with their line breaks. May be empty.
     * @property theirs The lines of the `theirs` side here, with their line breaks. May be empty.
     */
    public data class Conflicting(
        val ours: String,
        val theirs: String,
    ) : MergeChunk
}

/**
 * Three-way line merge (diff3) of [ours] and [theirs], two edits of [base]; for `body` and
 * `title`, with an empty [base] when there is no common ancestor (ADR-0009).
 *
 * The result depends only on the three strings, on every platform. Swapping the sides gives the
 * same [MergeResult.Clean], and a [MergeResult.Conflict] with `ours` and `theirs` swapped in each
 * chunk, so the order of the heads cannot change an automatic merge's content.
 *
 * Lines compare exactly, line break included, and are never rewritten. Two changes conflict if
 * they share a base line, one inserts inside the other's lines, or both insert different lines at
 * one position. Changes that merely touch merge, unless joining them would alter a line: then the
 * whole merge is redone with touching changes as conflicts. An insertion that repeats the touching
 * end of the other side's change is written once. ADR-0011 gives the reasons.
 */
public fun merge(
    base: String,
    ours: String,
    theirs: String,
): MergeResult {
    val ids = HashMap<String, Int>()
    val baseLines = splitLines(base)
    val baseIds = intern(baseLines, ids)

    fun side(text: String): Side =
        splitLines(text).let { lines -> Side(lines, diff(baseIds, intern(lines, ids))) }

    val oursSide = side(ours)
    val theirsSide = side(theirs)
    val merged = Sweep(baseLines, oursSide, theirsSide, touchingConflicts = false).run()
    val chunks =
        if (merged.keepsLines) {
            merged.chunks
        } else {
            Sweep(baseLines, oursSide, theirsSide, touchingConflicts = true).run().chunks
        }
    return if (chunks.any { it is MergeChunk.Conflicting }) {
        MergeResult.Conflict(chunks)
    } else {
        MergeResult.Clean(
            chunks.filterIsInstance<MergeChunk.Resolved>().joinToString("") { it.text },
        )
    }
}

/** Gives equal lines equal numbers. Only equality is used, so the numbers cannot affect a merge. */
private fun intern(
    lines: List<String>,
    ids: MutableMap<String, Int>,
): IntArray = IntArray(lines.size) { index -> ids.getOrPut(lines[index]) { ids.size } }

private class Side(
    val lines: List<String>,
    val hunks: List<Hunk>,
)

/** A position in a [Side]'s hunks. [shift] turns a base line after those taken into a side line. */
private class Cursor(
    val side: Side,
) {
    private var next = 0
    var shift = 0
        private set

    val hunk: Hunk?
        get() = side.hunks.getOrNull(next)

    /** The hunk taken last. */
    val taken: Hunk?
        get() = side.hunks.getOrNull(next - 1)

    fun newLines(hunk: Hunk): List<String> = side.lines.subList(hunk.sideStart, hunk.sideEnd)

    fun take(hunk: Hunk) {
        next += 1
        shift += (hunk.sideEnd - hunk.sideStart) - (hunk.baseEnd - hunk.baseStart)
    }
}

/** Chunks in order, and whether the merged lines are still the same lines once joined. */
private class Merged(
    val chunks: List<MergeChunk>,
    val keepsLines: Boolean,
)

/** Collects merged lines into chunks. */
private class ChunkWriter {
    private val chunks = ArrayList<MergeChunk>()
    private val lines = ArrayList<String>()
    private var keepsLines = true

    fun resolved(run: List<String>) {
        lines += run
    }

    fun conflicting(
        ours: List<String>,
        theirs: List<String>,
    ) {
        flush()
        chunks += MergeChunk.Conflicting(ours.joinToString(""), theirs.joinToString(""))
    }

    fun finish(): Merged {
        flush()
        return Merged(chunks, keepsLines)
    }

    private fun flush() {
        if (lines.isNotEmpty()) {
            val text = lines.joinToString("")
            keepsLines = keepsLines && splitLines(text) == lines
            chunks += MergeChunk.Resolved(text)
            lines.clear()
        }
    }
}

/**
 * Walks the hunks of both sides in base order and merges them. Neither side comes first in
 * anything that shows in the result: hunks are ordered by their base range alone, and hunks with
 * equal ranges always end up in one region.
 */
private class Sweep(
    private val base: List<String>,
    ours: Side,
    theirs: Side,
    private val touchingConflicts: Boolean,
) {
    private val ours = Cursor(ours)
    private val theirs = Cursor(theirs)
    private val writer = ChunkWriter()

    fun run(): Merged {
        var unchangedFrom = 0
        var hunk = nextHunk()
        while (hunk != null) {
            if (repeatsOtherSide(hunk)) {
                take(hunk)
            } else {
                writer.resolved(base.subList(unchangedFrom, hunk.baseStart))
                unchangedFrom = writeRegion(hunk)
            }
            hunk = nextHunk()
        }
        writer.resolved(base.subList(unchangedFrom, base.size))
        return writer.finish()
    }

    private fun nextHunk(): Hunk? =
        listOfNotNull(ours.hunk, theirs.hunk).minWithOrNull(
            compareBy<Hunk> { it.baseStart }.thenBy { it.baseEnd },
        )

    /**
     * Whether [hunk] only inserts lines that the other side's change, touching it, already has at
     * that end. Then both sides added those lines, and they are written once, with that change.
     * With [touchingConflicts] the two simply form one region.
     */
    private fun repeatsOtherSide(hunk: Hunk): Boolean {
        if (touchingConflicts || hunk.baseStart != hunk.baseEnd) return false
        val (own, other) = if (hunk === ours.hunk) ours to theirs else theirs to ours
        val inserted = own.newLines(hunk)
        val before =
            other.hunk?.takeIf {
                it.baseStart == hunk.baseStart &&
                    it.baseEnd > it.baseStart
            }
        val after = other.taken?.takeIf { it.baseEnd == hunk.baseStart }
        return (before != null && other.newLines(before).take(inserted.size) == inserted) ||
            (after != null && other.newLines(after).takeLast(inserted.size) == inserted)
    }

    /** Writes the region of hunks that starts with [first], and returns the base line after it. */
    private fun writeRegion(first: Hunk): Int {
        val start = first.baseStart
        val oursStart = start + ours.shift
        val theirsStart = start + theirs.shift
        var end = first.baseEnd
        var hunk: Hunk? = first
        while (hunk != null && (hunk === first || joins(start, end, hunk))) {
            end = maxOf(end, hunk.baseEnd)
            take(hunk)
            hunk = nextHunk()
        }
        writeRun(
            base.subList(start, end),
            ours.side.lines.subList(oursStart, end + ours.shift),
            theirs.side.lines.subList(theirsStart, end + theirs.shift),
        )
        return end
    }

    /** Whether [hunk], which does not start before the region, overlaps [start] until [end]. */
    private fun joins(
        start: Int,
        end: Int,
        hunk: Hunk,
    ): Boolean {
        val bothInsertHere = start == end && hunk.baseEnd == end
        return hunk.baseStart < end ||
            (hunk.baseStart == end && (touchingConflicts || bothInsertHere))
    }

    private fun take(hunk: Hunk) {
        if (hunk === ours.hunk) ours.take(hunk) else theirs.take(hunk)
    }

    private fun writeRun(
        baseRun: List<String>,
        oursRun: List<String>,
        theirsRun: List<String>,
    ) {
        when {
            theirsRun == baseRun || oursRun == theirsRun -> writer.resolved(oursRun)
            oursRun == baseRun -> writer.resolved(theirsRun)
            else -> writer.conflicting(oursRun, theirsRun)
        }
    }
}
