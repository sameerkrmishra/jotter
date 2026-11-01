package io.github.sameerkrmishra.jotter.app.cli

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole round in real processes: a synthetic Takeout of 1,000 notes in, again without duplicates,
 * and out. That is ten times `jotter search`'s limit, the only page size in `core:storage`, so a
 * limit on the way out would show. Every run has a temporary `HOME` and its own `JOTTER_DB`.
 */
@OptIn(ExperimentalPathApi::class)
class InteropEndToEndTest {
    private val directory = createTempDirectory("jotter-end-to-end")
    private val keep = directory.resolve("Takeout/Keep").createDirectories()

    @AfterTest
    fun deleteFiles() {
        directory.deleteRecursively()
    }

    private class Exit(
        val status: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun jotter(vararg args: String): Exit {
        val javaBinary = Path(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(javaBinary, "-cp", System.getProperty("java.class.path"), MAIN_CLASS)
        val builder = ProcessBuilder(command + args)
        builder.environment().apply {
            remove("XDG_DATA_HOME")
            put("HOME", directory.resolve("home").toString())
            put(DATABASE_ENVVAR, directory.resolve("notes.db").toString())
        }
        val stderr = directory.resolve("stderr")
        val process = builder.redirectError(stderr.toFile()).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "jotter did not exit")
        return Exit(process.exitValue(), output, stderr.readText())
    }

    private fun assertSucceeds(
        expected: String,
        exit: Exit,
    ) {
        assertEquals("", exit.stderr)
        assertEquals(0, exit.status)
        assertEquals(expected, exit.stdout)
    }

    private fun writeTakeout() {
        repeat(NOTES) { index ->
            val state =
                when {
                    index < TRASHED -> ""","isTrashed":true"""
                    index < TRASHED + ARCHIVED -> ""","isArchived":true"""
                    else -> ""
                }
            // Some titles repeat, as they do in a real Keep, and must not cost a file.
            val title = "Note ${index % DISTINCT_TITLES}"
            val created = FIRST_CREATED_USEC + index * USEC_PER_MINUTE
            val file = keep.resolve("note-$index.json")
            file.writeText(keepNote(created, title, "Text $index", state))
            keep.resolve("note-$index.html").writeText("<html>")
        }
    }

    private fun countLines(text: String) = text.lineSequence().count(String::isNotEmpty)

    private fun Path.markdownFiles() = listDirectoryEntries("*.md").size

    @Test
    fun aThousandNotesGoInOnceAndComeOutAgain() {
        writeTakeout()

        assertSucceeds(summary(imported = NOTES, skipped = 0), jotter("import", keep.toString()))
        assertSucceeds(summary(imported = 0, skipped = NOTES), jotter("import", keep.toString()))

        val active = NOTES - TRASHED - ARCHIVED
        assertEquals(active, countLines(jotter("list").stdout))
        assertEquals(ARCHIVED, countLines(jotter("list", "--archived").stdout))
        assertEquals(TRASHED, countLines(jotter("list", "--trash").stdout))

        val out = directory.resolve("out")
        assertSucceeds("exported\t${active + ARCHIVED}\n", jotter("export", out.toString()))
        assertEquals(active + ARCHIVED, out.markdownFiles())

        val all = directory.resolve("all")
        assertSucceeds("exported\t$NOTES\n", jotter("export", "--trash", all.toString()))
        assertEquals(NOTES, all.markdownFiles())
    }

    @Test
    fun aFailedImportExitsNonZeroWithItsSummary() {
        keep.resolve("bad.json").writeText("not json")

        val exit = jotter("import", keep.toString())

        assertEquals(1, exit.status)
        assertTrue(exit.stdout.startsWith("imported\t0\n"), exit.stdout)
        assertTrue("failure\tbad.json\tMALFORMED_JSON" in exit.stderr, exit.stderr)
    }

    private fun summary(
        imported: Int,
        skipped: Int,
    ) = "imported\t$imported\nskipped\t$skipped\nignored\t$NOTES\nwarnings\t0\nfailures\t0\n"

    private companion object {
        const val MAIN_CLASS = "io.github.sameerkrmishra.jotter.app.cli.MainKt"
        const val TIMEOUT_SECONDS = 120L
        const val NOTES = 1_000
        const val TRASHED = 30
        const val ARCHIVED = 70
        const val DISTINCT_TITLES = 900

        /** 2020-01-01T00:00:00Z. */
        const val FIRST_CREATED_USEC = 1_577_836_800_000_000L
        const val USEC_PER_MINUTE = 60_000_000L
    }
}
