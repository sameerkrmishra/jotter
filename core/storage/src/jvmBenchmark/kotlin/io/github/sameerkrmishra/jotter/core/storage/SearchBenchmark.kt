// The shape of the synthetic notes (line counts, percentages) is spelled out where it is used.
@file:Suppress("MagicNumber")

package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.TagName
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.measureTime

// The M1 exit criterion (PLAN.md §10): search over 10,000 synthetic notes in under 50 ms.
// Run with ./gradlew :core:storage:searchBenchmark. Prints a Markdown table for README.md.

private const val NOTES = 10_000
private const val SEED = 20261029
private const val WARM_UP_RUNS = 20
private const val MEASURED_RUNS = 100

/** One benchmark query: what a person types, and the filter it runs with. */
private class BenchmarkQuery(
    val name: String,
    val text: String,
    val filter: NoteFilter = NoteFilter(),
)

private val queries =
    listOf(
        BenchmarkQuery("common word", "the"),
        BenchmarkQuery("rare word", Vocabulary.words[Vocabulary.RARE_RANK]),
        BenchmarkQuery("3-letter prefix", "pro"),
        // Not a PLAN.md query: the first keystroke of search as you type, which no prefix index
        // covers.
        BenchmarkQuery("1-letter prefix", "t"),
        BenchmarkQuery("two words", "meeting project"),
        BenchmarkQuery("word + tag filter", "project", NoteFilter(tag = TagName.parse("work"))),
    )

/** Generates the notes in a temporary database, runs the queries and prints the results. */
public fun main() {
    val dir = Files.createTempDirectory("jotter-search-benchmark").toFile()
    try {
        val path = File(dir, "benchmark.db").path
        NoteStore.open(path, "Benchmark", EmptyCoroutineContext).use(::run)
    } finally {
        dir.deleteRecursively()
    }
}

private fun run(store: NoteStore) {
    val generation = measureTime { generateNotes(store) }
    println("Generated $NOTES notes in $generation (seed $SEED).")
    println(machine())
    println()
    println("| Query | Text | Hits | Median | p95 | Median, all hits | p95, all hits |")
    println("|---|---|---|---|---|---|---|")
    for (query in queries) {
        fun search(limit: Int) = store.notes.search(query.text, query.filter, limit)
        val limited = measure { search(NoteService.DEFAULT_SEARCH_LIMIT) }
        val all = measure { search(Int.MAX_VALUE) }
        val tag = query.filter.tag
        val text = if (tag == null) "`${query.text}`" else "`${query.text}` in `$tag`"
        val hits = search(Int.MAX_VALUE).size
        println(
            "| ${query.name} | $text | $hits | ${limited.median} | ${limited.p95} " +
                "| ${all.median} | ${all.p95} |",
        )
    }
}

/** Writes [NOTES] notes in one transaction, which is much faster than one per note. */
private fun generateNotes(store: NoteStore) {
    val random = Random(SEED)
    val tags = Vocabulary.tags.map(TagName::parse)
    store.database.transaction {
        repeat(NOTES) {
            val title = if (random.nextBoolean()) Vocabulary.sentence(random, 1..5) else ""
            val body = List(random.nextInt(10, 101)) { Vocabulary.line(random) }.joinToString("\n")
            val noteTags = tags.shuffled(random).take(random.nextInt(0, 4)).toSet()
            store.notes.create(title, body, noteTags, pinned = random.nextInt(20) == 0)
        }
    }
}

/** Median and 95th percentile of [MEASURED_RUNS] runs of [block], after [WARM_UP_RUNS]. */
private fun measure(block: () -> Unit): Timings {
    repeat(WARM_UP_RUNS) { block() }
    val runs = List(MEASURED_RUNS) { measureTime(block) }.sorted()
    return Timings(median = runs[runs.size / 2], p95 = runs[(runs.size * 95 + 99) / 100 - 1])
}

private class Timings(
    median: Duration,
    p95: Duration,
) {
    val median: String = format(median)
    val p95: String = format(p95)

    private fun format(duration: Duration): String =
        "%.1f ms".format(java.util.Locale.ROOT, duration.inWholeMicroseconds / 1_000.0)
}

private fun machine(): String {
    val cpu =
        File("/proc/cpuinfo")
            .takeIf { it.exists() }
            ?.useLines { lines -> lines.firstOrNull { it.startsWith("model name") } }
            ?.substringAfter(':')
            ?.trim()
            ?: System.getProperty("os.arch")
    val runtime = Runtime.getRuntime()
    return "Machine: $cpu, ${runtime.availableProcessors()} threads, " +
        "${System.getProperty("os.name")} ${System.getProperty("os.version")}, " +
        "JVM ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, " +
        "max heap ${runtime.maxMemory() / 1_048_576} MB."
}

/**
 * Synthetic text with a realistic word distribution: word frequencies follow Zipf's law, as in
 * natural language. The most frequent ranks are common English words, then words typical of
 * notes, then a long tail of made-up words that each occur in only a few notes.
 */
private object Vocabulary {
    private val functionWords =
        (
            "the of and to a in is it you that he was for on are with as I his they be at one " +
                "have this from or had by not but what some we can out other were all there when " +
                "up use your how said an each she which do their if will way about many then them"
        ).split(" ")

    private val noteWords =
        (
            "buy milk eggs bread call mom meeting project notes todo check email send " +
                "report review plan week monday friday weekend book flight hotel train " +
                "ticket idea list groceries recipe garden fix bike car insurance bank tax " +
                "receipt doctor dentist appointment birthday gift party dinner lunch " +
                "coffee tea read article write draft program problem process progress " +
                "product proposal profile promise protect budget invoice client team " +
                "deadline release bug feature test deploy server password backup photo " +
                "music movie series podcast workout run walk yoga clean laundry kitchen " +
                "paint shelf lamp sofa rent bill phone internet update install"
        ).split(" ")

    private const val SYLLABLES =
        "ba be bi bo bu ka ke ki ko ku la le li lo lu ma me mi mo mu " +
            "na ne ni no nu ra re ri ro ru sa se si so su ta te ti to tu va ve vi vo vu"

    private const val MADE_UP_WORDS = 30_000
    private const val ZIPF_EXPONENT = 1.07

    /** Words by rank, the most frequent first. */
    val words: List<String> =
        run {
            val syllables = SYLLABLES.split(" ")
            val random = Random(SEED)
            val madeUp =
                generateSequence { madeUpWord(syllables, random) }.distinct().take(MADE_UP_WORDS)
            (functionWords + noteWords + madeUp).distinct()
        }

    private fun madeUpWord(
        syllables: List<String>,
        random: Random,
    ): String = List(random.nextInt(2, 5)) { syllables.random(random) }.joinToString("")

    /** A rank whose word occurs in a handful of the notes. */
    const val RARE_RANK = 8_000

    val tags: List<String> =
        "work home Shopping ideas travel health finance reading Projekt caf\u00E9".split(" ")

    private val cumulative: DoubleArray =
        run {
            var sum = 0.0
            DoubleArray(words.size) { rank ->
                sum += 1.0 / (rank + 1.0).pow(ZIPF_EXPONENT)
                sum
            }
        }

    fun word(random: Random): String {
        val target = random.nextDouble() * cumulative.last()
        val index = cumulative.binarySearch(target).let { if (it >= 0) it else -it - 1 }
        return words[index.coerceAtMost(words.lastIndex)]
    }

    fun sentence(
        random: Random,
        length: IntRange,
    ): String =
        List(random.nextInt(length.first, length.last + 1)) { word(random) }.joinToString(" ")

    /** A Markdown line: a task, a heading or plain text. */
    fun line(random: Random): String {
        val text = sentence(random, 2..12)
        return when (random.nextInt(10)) {
            0, 1 -> "- [ ] $text"
            2 -> "- [x] $text"
            3 -> "# $text"
            else -> text
        }
    }
}
