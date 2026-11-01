package io.github.sameerkrmishra.jotter.interop.keepimport

import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.interop.keepimport.dto.KeepAnnotation
import io.github.sameerkrmishra.jotter.interop.keepimport.dto.KeepListItem
import io.github.sameerkrmishra.jotter.interop.keepimport.dto.KeepNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private const val URL = "https://example.org/a"

class KeepNoteMappingTest {
    private fun linkNote(title: String) =
        KeepNote(annotations = listOf(KeepAnnotation("WEBLINK", title, URL)))

    @Test
    fun theIdIsTheUuid5OfTheCreatedTime() {
        // python3: uuid.uuid5(uuid.NAMESPACE_URL, "urn:jotter:google-keep:1488000000000000")
        val expected = NoteId.parse("2693d05a-07d9-5fa2-a27f-8aa58b71f569")

        assertEquals(expected, keepNoteId(1_488_000_000_000_000))
    }

    @Test
    fun differentCreatedTimesGiveDifferentIds() {
        assertNotEquals(keepNoteId(1_488_000_000_000_000), keepNoteId(1_488_000_000_000_001))
    }

    @Test
    fun listItemsBecomeATaskList() {
        val note =
            KeepNote(
                listContent = listOf(KeepListItem("milk", isChecked = true), KeepListItem("eggs")),
            )

        assertEquals("- [x] milk\n- [ ] eggs", note.markdownBody())
    }

    @Test
    fun lineBreaksInAListItemStayInTheItem() {
        val note = KeepNote(listContent = listOf(KeepListItem("one\ntwo\r\nthree")))

        assertEquals("- [ ] one\n  two\n  three", note.markdownBody())
    }

    @Test
    fun anEmptyListItemKeepsItsLine() {
        val note = KeepNote(listContent = listOf(KeepListItem(""), KeepListItem("x")))

        assertEquals("- [ ] \n- [ ] x", note.markdownBody())
    }

    @Test
    fun textComesBeforeAList() {
        val note = KeepNote(textContent = "Intro", listContent = listOf(KeepListItem("x")))

        assertEquals("Intro\n\n- [ ] x", note.markdownBody())
    }

    @Test
    fun textIsKeptAsItIs() {
        assertEquals("  # a\n\nb  ", KeepNote(textContent = "  # a\n\nb  ").markdownBody())
    }

    @Test
    fun anEmptyNoteHasAnEmptyBody() {
        assertEquals("", KeepNote().markdownBody())
    }

    @Test
    fun aWebLinkWithATitleBecomesAMarkdownLink() {
        assertEquals("[Site](https://example.org/a)", linkNote("Site").markdownBody())
    }

    @Test
    fun aTitleThatAddsNothingIsLeftOut() {
        assertEquals(URL, linkNote("").markdownBody())
        assertEquals(URL, linkNote("  ").markdownBody())
        assertEquals(URL, linkNote(URL).markdownBody())
    }

    @Test
    fun aTitleThatWouldBreakTheLinkIsLeftOut() {
        assertEquals(URL, linkNote("a [b] c").markdownBody())
        val url = "https://example.org/a_(b)"
        val note = KeepNote(annotations = listOf(KeepAnnotation("WEBLINK", "Site", url)))
        assertEquals(url, note.markdownBody())
    }

    @Test
    fun linksComeAfterTheTextAndTheList() {
        val note = linkNote("").copy(textContent = "Intro", listContent = listOf(KeepListItem("x")))

        assertEquals("Intro\n\n- [ ] x\n\n$URL", note.markdownBody())
    }

    @Test
    fun aLinkAlreadyInTheTextIsNotRepeated() {
        assertEquals("See $URL now", linkNote("").copy(textContent = "See $URL now").markdownBody())
        val inList = linkNote("").copy(listContent = listOf(KeepListItem(URL)))
        assertEquals("- [ ] $URL", inList.markdownBody())
    }

    @Test
    fun aLinkListedTwiceAppearsOnce() {
        val link = KeepAnnotation("WEBLINK", "", URL)

        assertEquals(URL, KeepNote(annotations = listOf(link, link)).markdownBody())
    }

    @Test
    fun severalLinksTakeOneLineEach() {
        val other = "https://example.org/b"
        val links = listOf(KeepAnnotation("WEBLINK", "", URL), KeepAnnotation("WEBLINK", "", other))

        assertEquals("$URL\n$other", KeepNote(annotations = links).markdownBody())
    }

    @Test
    fun otherAnnotationsAndLinksWithoutAUrlAddNothing() {
        val annotations =
            listOf(KeepAnnotation("GSUITE", "Doc", URL), KeepAnnotation("WEBLINK", "No URL", ""))

        assertEquals("", KeepNote(annotations = annotations).markdownBody())
    }

    @Test
    fun theTrashWinsOverTheArchive() {
        assertEquals(NoteState.ACTIVE, KeepNote().noteState())
        assertEquals(NoteState.ARCHIVED, KeepNote(isArchived = true).noteState())
        assertEquals(NoteState.TRASHED, KeepNote(isTrashed = true).noteState())
        assertEquals(NoteState.TRASHED, KeepNote(isArchived = true, isTrashed = true).noteState())
    }
}
