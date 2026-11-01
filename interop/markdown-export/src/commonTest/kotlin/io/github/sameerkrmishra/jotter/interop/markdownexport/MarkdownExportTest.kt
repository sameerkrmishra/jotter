package io.github.sameerkrmishra.jotter.interop.markdownexport

import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownExportTest {
    private val soup = TagName.parse("soup")
    private val work = TagName.parse("Work")

    @Test
    fun rendersTheExactFile() {
        val note =
            noteOf(
                title = "Lentil soup",
                body = "- red lentils\n- cumin\n",
                tags = setOf(soup, work),
                state = NoteState.ARCHIVED,
            ).copy(isPinned = true)
        val file = exportMarkdown(listOf(note)).single()
        assertEquals("Lentil soup.md", file.fileName)
        assertEquals(
            """
            ---
            id: 00000000-0000-7000-8000-000000000001
            title: "Lentil soup"
            tags:
              - "Work"
              - "soup"
            pinned: true
            state: archived
            created: 2026-01-02T03:04:05Z
            updated: 2026-01-03T03:04:05.250Z
            ---
            - red lentils
            - cumin

            """.trimIndent(),
            file.content,
        )
    }

    @Test
    fun anUnpinnedNoteWithoutTagsOrBody() {
        val content = exportMarkdown(listOf(noteOf("Empty"))).single().content
        assertEquals(
            """
            ---
            id: 00000000-0000-7000-8000-000000000001
            title: "Empty"
            tags: []
            pinned: false
            state: active
            created: 2026-01-02T03:04:05Z
            updated: 2026-01-03T03:04:05.250Z
            ---

            """.trimIndent(),
            content,
        )
    }

    @Test
    fun quotesEverythingThatYamlWouldMisread() {
        assertEquals(""""a: b # c"""", yamlQuoted("a: b # c"))
        assertEquals(""""say \"hi\""""", yamlQuoted("say \"hi\""))
        assertEquals(""""back\\slash"""", yamlQuoted("back\\slash"))
        assertEquals(""""it's"""", yamlQuoted("it's"))
        assertEquals(""""- item"""", yamlQuoted("- item"))
        assertEquals(""""null"""", yamlQuoted("null"))
        assertEquals("\"\"", yamlQuoted(""))
    }

    @Test
    fun escapesWhatCannotAppearLiterally() {
        assertEquals(""""a\u000Ab"""", yamlQuoted("a\nb"))
        assertEquals(""""\u0000\u001F\u007F\u0085"""", yamlQuoted("\u0000\u001F\u007F\u0085"))
        assertEquals(""""\u2028\u2029\uFFFE\uFFFF"""", yamlQuoted("\u2028\u2029\uFFFE\uFFFF"))
        assertEquals(""""\uFEFFa"""", yamlQuoted("\uFEFFa"))
    }

    @Test
    fun keepsEmojiAndReplacesLoneSurrogates() {
        assertEquals("\"😀\"", yamlQuoted("😀"))
        assertEquals("\"a\uFFFDb\"", yamlQuoted("a\uD83Db"))
    }

    @Test
    fun aBodyThatLooksLikeFrontMatterIsUnchanged() {
        val body = "---\ntitle: not this one\n---\n\n---\nend"
        val content = exportMarkdown(listOf(noteOf("Fences", body = body))).single().content
        assertEquals(body, content.substringAfter("---\n").substringAfter("---\n"))
        assertEquals(body, parseFile(content).body)
        assertEquals("Fences", parseFile(content).fields["title"])
    }

    @Test
    fun bodyWhitespaceIsUnchanged() {
        for (body in listOf("  x\r\n\ty  \n\n", "\n", "no newline", "\uFEFFbom")) {
            val content = exportMarkdown(listOf(noteOf("a", body = body))).single().content
            assertEquals(body, parseFile(content).body)
        }
    }

    @Test
    fun everyStateIsExportedByDefault() {
        val notes =
            NoteState.entries.mapIndexed {
                index,
                state,
                ->
                noteOf(state.name, index, state = state)
            }
        assertEquals(
            listOf("ACTIVE.md", "ARCHIVED.md", "TRASHED.md"),
            exportMarkdown(notes).map { it.fileName },
        )
    }

    @Test
    fun filtersByState() {
        val notes =
            NoteState.entries.mapIndexed {
                index,
                state,
                ->
                noteOf(state.name, index, state = state)
            }
        val filter = ExportFilter(states = setOf(NoteState.ACTIVE, NoteState.ARCHIVED))
        assertEquals(
            listOf("ACTIVE.md", "ARCHIVED.md"),
            exportMarkdown(notes, filter).map { it.fileName },
        )
    }

    @Test
    fun filtersByTagExactly() {
        val notes =
            listOf(
                noteOf("With", 1, tags = setOf(soup, work)),
                noteOf("Without", 2, tags = setOf(work)),
                noteOf("Other case", 3, tags = setOf(TagName.parse("Soup"))),
            )
        assertEquals(
            listOf("With.md"),
            exportMarkdown(notes, ExportFilter(tag = soup)).map { it.fileName },
        )
    }

    @Test
    fun aFilteredOutNoteDoesNotAffectNames() {
        val notes = listOf(noteOf("Soup", 1), noteOf("soup", 2, tags = setOf(soup)))
        assertEquals(
            listOf("soup.md"),
            exportMarkdown(notes, ExportFilter(tag = soup)).map { it.fileName },
        )
    }

    @Test
    fun filesAreSortedByNameWhateverTheInputOrder() {
        val notes = listOf(noteOf("b", 1), noteOf("c", 2), noteOf("a", 3))
        val expected = listOf("a.md", "b.md", "c.md")
        assertEquals(expected, exportMarkdown(notes).map { it.fileName })
        assertEquals(exportMarkdown(notes), exportMarkdown(notes.reversed()))
    }

    @Test
    fun arbitraryFieldsRoundTrip() =
        runTest {
            val text = texts(allowLoneSurrogates = false)
            val tags = Arb.list(text, 0..5).map { it.mapNotNull(TagName::parseOrNull).toSet() }
            val bodies = Arb.choice(text, text.map { "---\n$it\n---\n$it" }, Arb.constant("---"))
            checkAll(text, tags, bodies) { title, tagSet, body ->
                val file =
                    exportMarkdown(listOf(noteOf(title, tags = tagSet, body = body))).single()
                val parsed = parseFile(file.content)
                assertEquals(title, parsed.fields["title"])
                assertEquals(tagSet.map { it.toString() }.sorted(), parsed.tags)
                assertEquals(body, parsed.body)
            }
        }
}
