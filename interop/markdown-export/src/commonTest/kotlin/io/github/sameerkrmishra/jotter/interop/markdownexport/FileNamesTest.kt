package io.github.sameerkrmishra.jotter.interop.markdownexport

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.utf8Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileNamesTest {
    private fun nameOf(title: String): String =
        assignFileNames(listOf(noteOf(title)))
            .single()
            .second

    private fun notesOf(titles: List<String>): List<Note> =
        titles.mapIndexed { index, title -> noteOf(title, index) }

    private fun namesOf(titles: List<String>): List<String> =
        assignFileNames(notesOf(titles)).map { it.second }

    @Test
    fun aPlainTitleBecomesTheName() {
        assertEquals("Groceries for Sunday.md", nameOf("Groceries for Sunday"))
    }

    @Test
    fun anEmptyOrBlankTitleBecomesUntitled() {
        for (title in listOf("", "   ", "\n\t", "...", "--", "\u200B", "/", "\u0000")) {
            assertEquals("untitled.md", nameOf(title), "title: \"$title\"")
        }
    }

    @Test
    fun pathSeparatorsAndForbiddenCharactersAreReplaced() {
        assertEquals("AC-DC.md", nameOf("AC/DC"))
        assertEquals("a-b.md", nameOf("a\\b"))
        assertEquals("a- b-c-d-e-f-g-h.md", nameOf("a: b*c?d\"e<f>g|h"))
    }

    @Test
    fun dotsCannotEscapeTheDirectory() {
        assertEquals("untitled.md", nameOf(".."))
        assertEquals("untitled.md", nameOf("."))
        assertEquals("etc-passwd.md", nameOf("../../etc/passwd"))
        assertEquals("etc-passwd.md", nameOf("/etc/passwd"))
        assertEquals("a..b.md", nameOf("a..b"))
    }

    @Test
    fun whitespaceCollapsesAndControlCharactersAreReplaced() {
        assertEquals("first second.md", nameOf("  first\r\n\t second\u2028 "))
        assertEquals("a-b-c-d.md", nameOf("a\u0000b\u001Fc\u007Fd"))
    }

    @Test
    fun invisibleCharactersAreDropped() {
        assertEquals("ab.md", nameOf("a\u200Bb"))
        assertEquals("ab.md", nameOf("a\u202Eb"))
        assertEquals("👩💻.md", nameOf("👩\u200D💻"))
    }

    @Test
    fun loneSurrogatesAreReplaced() {
        assertEquals("a-b.md", nameOf("a\uD83Db"))
        assertEquals("a-b.md", nameOf("a\uDC69b"))
        assertEquals("a.md", nameOf("a\uD83D"))
    }

    @Test
    fun reservedNamesGetAPrefix() {
        for (title in listOf("CON", "con", "Nul", "aux", "PRN", "COM1", "lpt9", "COM¹", "CLOCK$")) {
            assertEquals("_$title.md", nameOf(title))
        }
        assertEquals("_con.txt.md", nameOf("con.txt"))
        assertEquals("_CON .txt.md", nameOf("CON .txt"))
        assertEquals("_CON.md", nameOf("CON:"))
    }

    @Test
    fun namesThatOnlyContainAReservedWordAreKept() {
        for (title in listOf("console", "COM10", "my con", "nulls")) {
            assertEquals("$title.md", nameOf(title))
        }
    }

    @Test
    fun aLongNameIsCutByBytesAtACodePoint() {
        assertEquals("x".repeat(139) + ".md", nameOf("x".repeat(500)))

        // Three bytes each, so counting chars would go far over the limit.
        val cjk = nameOf("語".repeat(100))
        assertEquals("語".repeat(46) + ".md", cjk)
        assertTrue(cjk.utf8Size() <= MAX_FILE_NAME_BYTES)

        assertEquals("😀".repeat(34) + ".md", nameOf("😀".repeat(100)))
    }

    @Test
    fun cuttingALongNameLeavesNoDotOrSpaceBeforeTheExtension() {
        val name = nameOf("x".repeat(138) + " .y" + "z".repeat(50))
        assertEquals("x".repeat(138) + ".md", name)
    }

    @Test
    fun aLongReservedNameStaysWithinTheLimit() {
        val name = nameOf("con." + "x".repeat(500))
        assertEquals(MAX_FILE_NAME_BYTES.toLong(), name.utf8Size())
        assertTrue(name.startsWith("_con."))
    }

    @Test
    fun theLowestIdKeepsTheNameAndOthersGetTheirId() {
        assertEquals(
            listOf("Soup.md", "Soup ${noteIdOf(1)}.md", "Soup ${noteIdOf(2)}.md"),
            namesOf(listOf("Soup", "Soup", "Soup")),
        )
    }

    @Test
    fun anActiveNoteKeepsTheNameOverAnOlderOneInAnotherState() {
        val trashed = noteOf("Soup", 1, state = NoteState.TRASHED)
        val archived = noteOf("Soup", 2, state = NoteState.ARCHIVED)
        val active = noteOf("Soup", 3)
        assertEquals(
            listOf(
                active to "Soup.md",
                archived to "Soup ${noteIdOf(2)}.md",
                trashed to "Soup ${noteIdOf(1)}.md",
            ),
            assignFileNames(listOf(trashed, archived, active)),
        )
    }

    @Test
    fun namesCollideIgnoringCase() {
        assertEquals(
            listOf("soup.md", "SOUP ${noteIdOf(1)}.md", "Soup ${noteIdOf(2)}.md"),
            namesOf(listOf("soup", "SOUP", "Soup")),
        )
        assertEquals(
            listOf("straße.md", "STRASSE ${noteIdOf(1)}.md"),
            namesOf(listOf("straße", "STRASSE")),
        )
    }

    @Test
    fun titlesThatSanitiseToTheSameNameCollide() {
        assertEquals(
            listOf("a-b.md", "a-b ${noteIdOf(1)}.md", "a-b ${noteIdOf(2)}.md"),
            namesOf(listOf("a/b", "a:b", "a-b")),
        )
    }

    @Test
    fun longTitlesThatDifferOnlyPastTheCutCollide() {
        val names = namesOf(listOf("x".repeat(300) + "a", "x".repeat(300) + "b"))
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.utf8Size() <= MAX_FILE_NAME_BYTES })
        assertTrue(names[1].endsWith(" ${noteIdOf(1)}.md"))
    }

    @Test
    fun aTitleThatLooksLikeADisambiguatedNameKeepsIt() {
        // Note 1 collides with note 0 and wants "Soup <id of 1>", which is note 2's title.
        assertEquals(
            listOf("Soup.md", "Soup ${noteIdOf(1)} 1.md", "Soup ${noteIdOf(1)}.md"),
            namesOf(listOf("Soup", "Soup", "Soup ${noteIdOf(1)}")),
        )
    }

    @Test
    fun namesDoNotDependOnTheOrderOfTheNotes() {
        val notes = notesOf(listOf("b", "a", "A", "b", ""))
        assertEquals(assignFileNames(notes), assignFileNames(notes.reversed()))
    }

    @Test
    fun arbitraryTitlesGiveSafeUniqueNames() =
        runTest {
            checkAll(Arb.list(texts(allowLoneSurrogates = true), 1..20)) { titles ->
                val names = namesOf(titles)
                names.forEach(::assertSafe)
                val folded = names.map { it.uppercase().lowercase() }
                assertEquals(folded.size, folded.toSet().size, "names: $names")
            }
        }

    @Test
    fun arbitraryTitlesGiveTheSameNamesInAnyOrder() =
        runTest {
            val noteLists = Arb.list(texts(allowLoneSurrogates = true), 1..10).map(::notesOf)
            checkAll(noteLists.map { it to it.shuffled() }) { (notes, reordered) ->
                assertEquals(assignFileNames(notes), assignFileNames(reordered))
            }
        }

    // Spelled out again here, so a mistake in the production rules cannot hide itself.
    private fun assertSafe(name: String) {
        assertTrue(name.utf8Size() <= MAX_FILE_NAME_BYTES, "too long: $name")
        assertTrue(name.endsWith(".md"), "extension: $name")
        assertEquals(1, name.toPath().segments.size, "not one path segment: $name")
        assertTrue(name.none { it in "/\\:*?\"<>|" }, "forbidden character: $name")
        assertTrue(name.none { it.code < 0x20 || it.code in 0x7F..0x9F }, "control: $name")
        assertTrue(name.indices.none { name.isLoneSurrogate(it) }, "lone surrogate: $name")
        val stem = name.removeSuffix(".md")
        assertTrue(stem.isNotEmpty(), "empty: $name")
        assertTrue(stem.first() !in " .-" && stem.last() !in " .-", "bad ends: $name")
        val device = stem.substringBefore('.').trimEnd().uppercase()
        val devices =
            listOf("CON", "PRN", "AUX", "NUL") + (0..9).flatMap { listOf("COM$it", "LPT$it") }
        assertTrue(device !in devices, "reserved: $name")
    }
}
