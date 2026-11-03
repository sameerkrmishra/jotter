package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.codepoints
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchTest {
    private val clock = FakeClock(millis = 1_000)
    private val shopping = TagName.parse("Shopping")

    @Test
    fun findsNotesWithEveryWordInTitleOrBodyInAnyOrder() =
        withStore(clock) { store ->
            val notes = store.notes
            val both = notes.create("Milk", "buy bread")
            notes.create("", "buy milk")
            notes.create("Bread", "")

            assertEquals(listOf(both), notes.search("bread milk").map(SearchHit::note))
            assertEquals(listOf(both), notes.search("  MILK\u3000Bread ").map(SearchHit::note))
        }

    @Test
    fun theTitleWeighsMoreThanTheBody() =
        withStore(clock) { store ->
            val notes = store.notes
            val inBody = notes.create("Shopping", "milk, eggs and more milk")
            val inTitle = notes.create("Milk", "shopping list")

            assertEquals(listOf(inTitle, inBody), notes.search("milk").map(SearchHit::note))
        }

    @Test
    fun equallyRankedHitsComeInListOrder() =
        withStore(clock) { store ->
            val notes = store.notes
            val older = notes.create("", "milk")
            clock.millis = 2_000
            val newer = notes.create("", "milk")
            val pinned = notes.setPinned(older.id, true)

            assertEquals(listOf(pinned, newer), notes.search("milk").map(SearchHit::note))
        }

    @Test
    fun accentsAndCaseAreIgnored() =
        withStore(clock) { store ->
            val notes = store.notes
            val note = notes.create("", "Caf\u00E9 au lait, cr\u00E8me br\u00FBl\u00E9e")

            listOf("cafe", "CAF\u00C9", "creme brulee", "Br\u00FBl\u00E9e").forEach { query ->
                assertEquals(listOf(note), notes.search(query).map(SearchHit::note), query)
            }
        }

    @Test
    fun theLastWordMatchesAsAPrefixAndTheOthersExactly() =
        withStore(clock) { store ->
            val notes = store.notes
            val note = notes.create("", "hello world")

            assertEquals(listOf(note), notes.search("wor").map(SearchHit::note))
            assertEquals(listOf(note), notes.search("hello wor").map(SearchHit::note))
            assertEquals(emptyList(), notes.search("hel world"))
        }

    @Test
    fun searchSyntaxInTheQueryIsMatchedLiterally() =
        withStore(clock) { store ->
            val notes = store.notes
            val withOr = notes.create("", "milk or bread")
            notes.create("", "milk")
            notes.create("", "bread")
            val withNot = notes.create("", "milk not bread")

            // As FTS5 syntax these would match other notes, or be errors.
            assertEquals(listOf(withOr), notes.search("milk OR bread").map(SearchHit::note))
            assertEquals(listOf(withNot), notes.search("milk NOT bread").map(SearchHit::note))
            assertEquals(listOf(withOr), notes.search("\"milk or\" bread").map(SearchHit::note))
            assertEquals(emptyList(), notes.search("body:milk"))
            assertEquals(emptyList(), notes.search("NEAR(milk bread)"))
        }

    @Test
    fun aQueryWithoutWordsFindsNothing() =
        withStore(clock) { store ->
            store.notes.create("", "- [ ] milk")

            listOf("", " ", "\u3000\t\n", "\"", "-", "*", "( )", "- [ ]").forEach { query ->
                assertEquals(emptyList(), store.notes.search(query), "\"$query\"")
            }
        }

    @Test
    fun noQueryThrowsAndEveryHighlightIsValid() =
        runTest {
            withStore(clock) { store ->
                store.notes.create(
                    "Caf\u00E9 \"NEAR\" (notes)",
                    "a b: c - d * e ^f \uD83D\uDE00 \u0000 x",
                )
                // A note with the marker characters in it, paired, stray and inside a word.
                val (start, end) = MATCH_START to MATCH_END
                store.notes.create("${start}caf$end", "${start}milk$end $end b${start}c $start")
                val syntax =
                    Arb.element(
                        "\"",
                        "*",
                        "NEAR",
                        "AND",
                        "OR",
                        "NOT",
                        "-",
                        ":",
                        "(",
                        ")",
                        "^",
                        "+",
                        "{",
                        "}",
                        " ",
                        "\u0000",
                    )
                val pieces =
                    Arb.choice(
                        syntax,
                        Arb.string(0..3, Arb.codepoints()),
                        Arb.element("caf", "milk", "a", "b"),
                    )
                checkAll(Arb.list(pieces, 0..8).map { it.joinToString("") }) { query ->
                    store.notes.search(query).forEach { hit -> assertValid(hit.snippet) }
                }
            }
        }

    @Test
    fun theSnippetMarksTheMatchesInTheBody() =
        withStore(clock) { store ->
            val notes = store.notes
            notes.create("Milk", "We need milk, Milk and more MILKSHAKES")

            val snippet = notes.search("milk").single().snippet

            assertEquals("We need milk, Milk and more MILKSHAKES", snippet.text)
            assertEquals(
                listOf("milk", "Milk", "MILKSHAKES"),
                snippet.matches.map(snippet.text::substring),
            )
        }

    @Test
    fun theSnippetIsTheTitleIfOnlyTheTitleMatches() =
        withStore(clock) { store ->
            store.notes.create("Groceries for the weekend", "- [ ] milk")

            val snippet =
                store.notes
                    .search("weekend")
                    .single()
                    .snippet

            assertEquals("Groceries for the weekend", snippet.text)
            assertEquals(listOf("weekend"), snippet.matches.map(snippet.text::substring))
        }

    @Test
    fun aLongTitleIsCutAroundTheMatchToo() =
        withStore(clock) { store ->
            val words = List(100) { "word$it" }
            store.notes.create(words.joinToString(" "), "body")

            val snippet =
                store.notes
                    .search("word50")
                    .single()
                    .snippet

            assertTrue(snippet.text.length < words.joinToString(" ").length, snippet.text)
            assertEquals(listOf("word50"), snippet.matches.map(snippet.text::substring))
        }

    @Test
    fun punctuationInsideAWordMakesAPhrase() =
        withStore(clock) { store ->
            val notes = store.notes
            val adjacent = notes.create("", "send an e-mail")
            notes.create("", "mail e")

            assertEquals(listOf(adjacent), notes.search("e-mail").map(SearchHit::note))
        }

    @Test
    fun aLongBodyIsCutAroundTheMatch() =
        withStore(clock) { store ->
            val words = List(200) { "word$it" }
            store.notes.create("", words.joinToString(" "))

            val snippet =
                store.notes
                    .search("word150")
                    .single()
                    .snippet

            assertTrue(
                snippet.text.startsWith("\u2026") && snippet.text.endsWith("\u2026"),
                snippet.text,
            )
            assertEquals(listOf("word150"), snippet.matches.map(snippet.text::substring))
        }

    @Test
    fun theFilterSelectsStateAndTag() =
        withStore(clock) { store ->
            val notes = store.notes
            val tagged = notes.create("", "milk", setOf(shopping))
            val untagged = notes.create("", "milk")
            val archived = notes.create("", "milk", setOf(shopping))
            notes.setState(archived.id, NoteState.ARCHIVED)

            fun hits(filter: NoteFilter) = notes.search("milk", filter).map { it.note.id }.toSet()
            assertEquals(setOf(tagged.id, untagged.id), hits(NoteFilter()))
            assertEquals(setOf(tagged.id), hits(NoteFilter(tag = shopping)))
            assertEquals(setOf(archived.id), hits(NoteFilter(NoteState.ARCHIVED)))
            assertEquals(emptySet(), hits(NoteFilter(NoteState.TRASHED)))
        }

    @Test
    fun theLimitKeepsTheBestHits() =
        withStore(clock) { store ->
            val notes = store.notes
            notes.create("", "milk")
            val best = notes.create("milk", "milk")

            assertFailsWith<IllegalArgumentException> { notes.search("milk", limit = 0) }
            assertEquals(listOf(best), notes.search("milk", limit = 1).map(SearchHit::note))
        }

    @Test
    fun theIndexFollowsEditsAndDeletions() =
        withStore(clock) { store ->
            val notes = store.notes
            val note = notes.create("", "milk")
            val edited = notes.edit(note.id, note.revisionId, "", "bread")
            notes.setPinned(note.id, true)

            assertEquals(emptyList(), notes.search("milk"))
            assertEquals(listOf(edited.id), notes.search("bread").map { it.note.id })

            notes.setState(note.id, NoteState.TRASHED)
            notes.deleteForever(note.id)
            assertEquals(emptyList(), notes.search("bread", NoteFilter(NoteState.TRASHED)))
            store.checkSearchIndex()
        }

    @Test
    fun theIndexStaysConsistentWithTheNotes() =
        runTest {
            withStore(clock) { store ->
                val notes = store.notes
                checkAll(Arb.list(texts, 1..4)) { bodies ->
                    val note = notes.create("", bodies.first())
                    var current: Note = note
                    for (body in bodies.drop(1)) {
                        current = notes.edit(note.id, current.revisionId, current.title, body)
                        current = notes.setPinned(note.id, !current.isPinned)
                    }
                    if (bodies.size % 2 == 0) {
                        notes.setState(note.id, NoteState.TRASHED)
                        notes.deleteForever(note.id)
                    }
                }
                store.checkSearchIndex()
            }
        }

    @Test
    fun highlightMarkersAreRemovedAndOnlyWellFormedOnesKept() {
        val (start, end) = MATCH_START to MATCH_END
        val cases =
            mapOf(
                "" to Highlighted("", emptyList()),
                "no match" to Highlighted("no match", emptyList()),
                "${start}a$end b ${start}cd$end" to Highlighted("a b cd", listOf(0..0, 4..5)),
                // A note's own marker characters: unpaired or empty ones give no range.
                "${end}a$start${end}b$start" to Highlighted("ab", emptyList()),
                "${start}a${start}b$end" to Highlighted("ab", listOf(1..1)),
            )

        cases.forEach { (marked, expected) ->
            assertEquals(
                expected,
                parseHighlighted(marked),
                marked,
            )
        }
    }

    private fun assertValid(highlighted: Highlighted) {
        var end = 0
        highlighted.matches.forEach { range ->
            assertTrue(range.first >= end && range.last >= range.first, "$range in $highlighted")
            assertTrue(range.last < highlighted.text.length, "$range in $highlighted")
            end = range.last + 1
        }
    }
}

/** Fails if the FTS5 index does not match the `note` table. */
internal fun NoteStore.checkSearchIndex() {
    driver.execute(null, "INSERT INTO note_fts(note_fts, rank) VALUES ('integrity-check', 1)", 0)
}
