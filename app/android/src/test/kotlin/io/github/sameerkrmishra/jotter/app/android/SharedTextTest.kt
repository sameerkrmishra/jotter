package io.github.sameerkrmishra.jotter.app.android

import io.github.sameerkrmishra.jotter.core.storage.MAX_NOTE_CHARS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What a share becomes: plain rules, on the JVM. */
class SharedTextTest {
    private fun share(
        text: CharSequence? = "buy milk",
        subject: CharSequence? = null,
        hasStream: Boolean = false,
        isSend: Boolean = true,
    ) = Share(isSend, "text/plain", text, subject, hasStream)

    @Test
    fun sharedTextBecomesANoteWithTheSubjectAsTitle() {
        assertEquals(
            ShareOutcome.NewNote("Shopping", "buy milk", null),
            outcomeOf(share(subject = "Shopping")),
        )
        assertEquals(ShareOutcome.NewNote("", "buy milk", null), outcomeOf(share()))
    }

    @Test
    fun aSubjectThatRepeatsTheTextIsNoTitle() {
        val link = "https://example.org/a"
        assertEquals(
            ShareOutcome.NewNote("", link, null),
            outcomeOf(share(text = link, subject = " $link\n")),
        )
    }

    @Test
    fun aTitleIsOneLineAndShort() {
        val outcome = outcomeOf(share(subject = "  A page\n  about   cats  \n"))
        assertEquals("A page about   cats", assertIs<ShareOutcome.NewNote>(outcome).title)

        val long = "x".repeat(MAX_SHARED_TITLE + 50)
        val cut = assertIs<ShareOutcome.NewNote>(outcomeOf(share(subject = long))).title
        assertEquals(MAX_SHARED_TITLE, cut.length)
        assertTrue(cut.endsWith("…"))

        // An emoji across the cut is not split in two.
        val emoji = "x".repeat(MAX_SHARED_TITLE - 2) + "😀" + "tail"
        val title = assertIs<ShareOutcome.NewNote>(outcomeOf(share(subject = emoji))).title
        assertEquals("x".repeat(MAX_SHARED_TITLE - 2) + "…", title)
    }

    @Test
    fun filesAndImagesAreRefused() {
        assertEquals(
            ShareOutcome.Refused(FILES_REFUSED),
            outcomeOf(share(text = null, hasStream = true)),
        )
        assertEquals(
            ShareOutcome.Refused(FILES_REFUSED),
            outcomeOf(share(text = "  ", hasStream = true)),
        )
    }

    @Test
    fun textBesideAFileIsKeptAndTheFileNamed() {
        assertEquals(
            ShareOutcome.NewNote("", "look", ATTACHMENT_LEFT_OUT),
            outcomeOf(share(text = "look", hasStream = true)),
        )
    }

    @Test
    fun nothingToSaveAndOtherActionsAreRefused() {
        assertEquals(ShareOutcome.Refused(NOTHING_TO_SAVE), outcomeOf(share(text = "\n ")))
        assertEquals(ShareOutcome.Refused(NOTHING_TO_SAVE), outcomeOf(share(text = null)))
        assertEquals(ShareOutcome.Refused(ONE_TEXT_ONLY), outcomeOf(share(isSend = false)))
    }

    @Test
    fun textAtTheCapIsKeptAndOverItRefusedWhole() {
        val most = "a".repeat(MAX_NOTE_CHARS)
        assertIs<ShareOutcome.NewNote>(outcomeOf(share(text = most)))

        val over = outcomeOf(share(text = most, subject = "t"))
        val reason = assertIs<ShareOutcome.Refused>(over).reason
        assertTrue(reason.contains("${MAX_NOTE_CHARS + 1} characters"), reason)
        assertTrue(reason.contains("Nothing was saved"), reason)
    }

    @Test
    fun styledTextIsTakenAsPlainText() {
        val styled = StringBuilder("bold")
        assertEquals(ShareOutcome.NewNote("", "bold", null), outcomeOf(share(text = styled)))
    }
}
