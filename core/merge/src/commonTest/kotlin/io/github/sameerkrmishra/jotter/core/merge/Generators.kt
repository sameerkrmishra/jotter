package io.github.sameerkrmishra.jotter.core.merge

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import kotlin.random.Random

private const val EDITS_PER_TEXT = 6
private const val NEW_LINE_VARIETY = 4
private const val MAX_LINES = 24
private const val HASH_FACTOR = 1_000_003L
private const val LCG_FACTOR = 6_364_136_223_846_793_005L
private const val LCG_INCREMENT = 1_442_695_040_888_963_407L
private const val LCG_SHIFT = 33

/** Few distinct lines, every kind of line break, and a non-BMP character. */
private val lines: Arb<String> =
    Arb.element(
        "a\n",
        "b\n",
        "c\n",
        "a\r\n",
        "b\r",
        "\n",
        "\r\n",
        "\r",
        "😀\n",
        "- [ ] d\n",
    )

/** Short text from few lines, so that repeated lines and equal texts are common. */
private val lineTexts: Arb<String> =
    Arb.bind(Arb.list(lines, 0..12), Arb.element("", "a", "😀", "e")) { body, tail ->
        body.joinToString("") + tail
    }

/** Arbitrary strings, cut anywhere: line breaks, surrogate pairs and unpaired surrogates. */
private val rawTexts: Arb<String> =
    Arb
        .list(Arb.element("x", "\n", "\r", "\uD83D", "\uDE00", "😀", " "), 0..16)
        .map { it.joinToString("") }

/** Any text the merge has to cope with. */
val texts: Arb<String> = Arb.choice(lineTexts, rawTexts)

/** Three texts where both sides are random line edits of the base, so many merges are clean. */
val relatedTexts: Arb<Triple<String, String, String>> =
    Arb.bind(texts, Arb.long(), Arb.long()) { base, oursSeed, theirsSeed ->
        Triple(base, randomlyEdited(base, oursSeed), randomlyEdited(base, theirsSeed))
    }

private fun randomlyEdited(
    text: String,
    seed: Long,
): String {
    val random = Random(seed)
    val edited = splitLines(text).toMutableList()
    repeat(random.nextInt(EDITS_PER_TEXT)) {
        val at = random.nextInt(edited.size + 1)
        when {
            random.nextBoolean() -> edited.add(at, "new ${random.nextInt(NEW_LINE_VARIETY)}\n")
            at < edited.size -> edited.removeAt(at)
        }
    }
    return edited.joinToString("")
}

/** Who edits a base line, or inserts into a gap between lines. */
private enum class Owner { NOBODY, OURS, THEIRS }

/** Two edits of [base] that do not overlap, and [merged], the text they must merge to. */
class DisjointEdits(
    val base: String,
    val ours: String,
    val theirs: String,
    val merged: String,
)

private class DisjointEditsBuilder {
    private val ours = StringBuilder()
    private val theirs = StringBuilder()
    private val merged = StringBuilder()
    private val base = StringBuilder()

    fun unchanged(line: String) {
        base.append(line)
        ours.append(line)
        theirs.append(line)
        merged.append(line)
    }

    /** [line] was in the base and [owner] replaced it with [edited], or deleted it if empty. */
    fun edited(
        owner: Owner,
        line: String,
        edited: String,
    ) {
        base.append(line)
        (if (owner == Owner.OURS) ours else theirs).append(edited)
        (if (owner == Owner.OURS) theirs else ours).append(line)
        merged.append(edited)
    }

    fun inserted(
        owner: Owner,
        line: String,
    ) {
        if (owner != Owner.NOBODY) {
            (if (owner == Owner.OURS) ours else theirs).append(line)
            merged.append(line)
        }
    }

    fun build() = DisjointEdits("$base", "$ours", "$theirs", "$merged")
}

/**
 * All lines are different, so a diff can only find the intended edits. Each base line is left
 * alone or edited by one side, and each gap between lines gets an insertion from at most one side.
 * A gap with lines changed by one side on both of its sides is inside that side's change, so only
 * that side may insert there. Everything else may touch.
 */
val disjointEdits: Arb<DisjointEdits> =
    Arb.bind(Arb.int(0..MAX_LINES), Arb.long()) { size, seed ->
        val random = Random(seed)
        val owners = List(size) { Owner.entries.random(random) }
        val edits = DisjointEditsBuilder()

        fun insertInto(gap: Int) {
            val surroundedBy = owners.getOrNull(gap - 1).takeIf { it == owners.getOrNull(gap) }
            val owner =
                if (surroundedBy == null || surroundedBy == Owner.NOBODY) {
                    Owner.entries.random(random)
                } else {
                    surroundedBy
                }
            edits.inserted(owner, "inserted into gap $gap\n")
        }

        for (index in 0 until size) {
            insertInto(index)
            val line = "line $index\n"
            when (val owner = owners[index]) {
                Owner.NOBODY -> edits.unchanged(line)
                else -> edits.edited(owner, line, if (random.nextBoolean()) "edit $index\n" else "")
            }
        }
        insertInto(size)
        edits.build()
    }

/** A checksum that nearly any change in a large text changes. */
fun checksum(text: String): Long = text.fold(0L) { sum, char -> sum * HASH_FACTOR + char.code }

/** Interned lines from a fixed generator, the same on every platform. */
fun pseudorandomLines(
    size: Int,
    variety: Int,
    seed: Long,
): IntArray {
    var state = seed
    return IntArray(size) {
        state = state * LCG_FACTOR + LCG_INCREMENT
        ((state ushr LCG_SHIFT) % variety).toInt()
    }
}

/** Interned lines as text, one line per number. */
fun IntArray.asText(): String = joinToString("") { "line $it\n" }
