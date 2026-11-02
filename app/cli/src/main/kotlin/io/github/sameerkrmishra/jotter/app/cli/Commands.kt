package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.ProcessedArgument
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.validate
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.NoteFilter
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.NoteTooLargeException
import java.io.IOException
import java.io.InputStream
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A subcommand that works on the notes in the database at [location]. */
internal abstract class NoteCommand(
    private val location: DatabaseLocation,
    name: String,
) : CoreCliktCommand(name = name) {
    final override fun run() {
        withNotes(location.path, ::execute)
    }

    /** Does the work on the open [notes], which are closed afterwards. */
    protected abstract fun execute(notes: NoteService)
}

/**
 * `jotter add`: creates a note and prints only its id, for scripts.
 *
 * Checklist items start with `-`, so whatever Clikt does not know as an option is the note's text.
 * The exception is what [looksLikeOption] says is a mistyped option, as a note called `--tga` is
 * never what was meant. `--` before the text stores even that.
 */
internal class AddCommand(
    location: DatabaseLocation,
    private val stdin: InputStream,
    private val argv: List<String>,
) : NoteCommand(location, "add") {
    override val treatUnknownOptionsAsArgs: Boolean = true

    private val text by argument(name = "TEXT", help = "The note, or - to read it from stdin.")
        .validate {
            if (looksLikeOption(it) && "--" !in argv) {
                fail("no such option $it (put -- before it if it is the note's text)")
            }
        }
    private val title by option("--title", help = "The title.").default("")
    private val tags by option("--tag", help = "A tag. May be repeated.").tag().multiple()

    override fun help(context: Context): String = "Add a note and print its id."

    override fun execute(notes: NoteService) {
        val body = if (text == STDIN) readStdin() else text
        if (title.isBlank() && body.isBlank()) throw CliktError("jotter: the note is empty")
        try {
            echo(notes.create(title, body, tags.toSet()).id)
        } catch (e: NoteTooLargeException) {
            throw CliktError("jotter: the note is too long: ${e.message}", e)
        }
    }

    private fun readStdin(): String =
        try {
            stdin.readBytes().decodeToString().trimEnd('\n')
        } catch (e: IOException) {
            throw CliktError("jotter: cannot read stdin: ${e.message}", e)
        }

    private companion object {
        const val STDIN = "-"
    }
}

/**
 * Whether [text] is `--word` or `--word=value`. Prose such as `--force is risky` has a space before
 * any `=`, and `---` or `-- item` have no letter after the dashes.
 */
private fun looksLikeOption(text: String): Boolean =
    // Regex only when needed: `jotter add` starts in about 160 ms and most notes do not get here.
    text.startsWith("--") &&
        Regex("""--\p{L}[^\s=]*(=.*)?""", RegexOption.DOT_MATCHES_ALL).matches(text)

/** `jotter list`: prints one line per note, in [NoteService.list]'s order. */
internal class ListCommand(
    location: DatabaseLocation,
) : NoteCommand(location, "list") {
    private val tag by option("--tag", help = "Only notes with this tag.").tag()
    private val state by option(help = "Archived notes, or the trash, instead of active notes.")
        .switch("--archived" to NoteState.ARCHIVED, "--trash" to NoteState.TRASHED)
        .default(NoteState.ACTIVE)

    override fun help(context: Context): String =
        "List notes as tab-separated lines: id, updated, pinned, tags, summary."

    override fun execute(notes: NoteService) {
        notes.list(NoteFilter(state, tag)).forEach { echo(listLine(it)) }
    }
}

/** `jotter show`: prints one note. */
internal class ShowCommand(
    location: DatabaseLocation,
) : NoteCommand(location, "show") {
    private val id by argument(name = "ID", help = "A note id, or a unique start of one.")

    override fun help(context: Context): String = "Show a note: headers, a blank line, the body."

    override fun execute(notes: NoteService) {
        echo(fullNote(notes.resolve(id)))
    }
}

/** `jotter search`: prints one line per hit, best first. */
internal class SearchCommand(
    location: DatabaseLocation,
) : NoteCommand(location, "search") {
    private val query by argument(name = "QUERY", help = "Words; the last may be the start of one.")

    override fun help(context: Context): String =
        "Search active notes, printing tab-separated lines: id, summary, snippet."

    override fun execute(notes: NoteService) {
        notes.search(query).forEach { echo(searchLine(it)) }
    }
}

/** `jotter tags`: prints one line per tag. */
internal class TagsCommand(
    location: DatabaseLocation,
) : NoteCommand(location, "tags") {
    override fun help(context: Context): String =
        "List tags as tab-separated lines: tag, number of notes outside the trash."

    override fun execute(notes: NoteService) {
        notes.tags().forEach { echo(tagLine(it)) }
    }
}

internal fun NullableOption<String, String>.tag() =
    convert("TAG") { TagName.parseOrNull(it) ?: fail("not a valid tag: \"$it\"") }

/** An empty path would otherwise mean the current directory. */
internal fun ProcessedArgument<String, String>.path() =
    convert {
        if (it.isEmpty()) fail("the path is empty")
        try {
            Path.of(it)
        } catch (_: InvalidPathException) {
            fail("not a valid path: $it")
        }
    }
