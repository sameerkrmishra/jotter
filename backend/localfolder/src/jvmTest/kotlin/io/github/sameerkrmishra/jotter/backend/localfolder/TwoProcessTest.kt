package io.github.sameerkrmishra.jotter.backend.localfolder

import kotlinx.coroutines.runBlocking
import okio.Path
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Separate processes on one folder, as the desktop app and the CLI can be: both ways of publishing
 * must hold across processes, where the in-process lock does not help. A native run is in
 * `linuxX64Test`.
 */
class TwoProcessTest {
    private val folders = TempFolders()

    @AfterTest
    fun deleteFolders() = folders.deleteAll()

    private fun start(
        root: Path,
        publishing: String,
        task: String,
        writer: Int,
    ): Process =
        ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp",
            System.getProperty("java.class.path"),
            SecondProcessWriter::class.java.name,
            root.toString(),
            publishing,
            task,
            writer.toString(),
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    private fun Process.awaitReady() {
        assertEquals(SecondProcessWriter.READY, inputReader().readLine())
    }

    private fun Process.go() {
        outputWriter().apply {
            newLine()
            flush()
        }
    }

    @Test
    fun processesRacingWithHardLinksHaveOneWinnerPerPath() {
        raceProcesses(SecondProcessWriter.LINK)
    }

    @Test
    fun processesRacingWithoutHardLinksHaveOneWinnerPerPath() {
        raceProcesses(SecondProcessWriter.RENAME)
    }

    private fun raceProcesses(publishing: String) =
        runBlocking {
            val root = folders.newFolder()
            val store = linkingStore(root).apply { initialize() }
            val writers =
                (0 until WRITERS).map { start(root, publishing, SecondProcessWriter.RACE, it) }
            try {
                writers.forEach { it.awaitReady() }
                writers.forEach { it.go() }
                val won = writers.map { process -> process.inputReader().readLines() }
                writers.forEach { process ->
                    assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    assertEquals(0, process.exitValue())
                }

                val allWon = won.flatten().map { it.toInt() }
                assertEquals((0 until SecondProcessWriter.RACED_PATHS).toList(), allWon.sorted())
                assertTrue(won.count { it.isNotEmpty() } > 1, "no race took place: $won")
                won.forEachIndexed { writer, indexes ->
                    indexes.forEach { index ->
                        val expected = byteArrayOf(writer.toByte())
                        assertContentEquals(expected, store.read("race/$index"))
                    }
                }
            } finally {
                writers.forEach { it.destroyForcibly() }
            }
        }

    @Test
    fun aKilledWriterWithHardLinksLeavesOnlyCompleteFiles() {
        killWriter(SecondProcessWriter.LINK)
    }

    @Test
    fun aKilledWriterWithoutHardLinksLeavesOnlyCompleteFiles() {
        killWriter(SecondProcessWriter.RENAME)
    }

    private fun killWriter(publishing: String) =
        runBlocking {
            val root = folders.newFolder()
            val store = linkingStore(root).apply { initialize() }
            val writer = start(root, publishing, SecondProcessWriter.WRITE_FOREVER, 0)
            try {
                writer.awaitReady()
                writer.go()
                repeat(WRITES_BEFORE_KILL) { writer.inputReader().readLine() }
            } finally {
                writer.destroyForcibly()
            }
            assertTrue(writer.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val listed = store.list("crash")
            assertFalse(listed.isEmpty())
            listed.forEach { info ->
                val index = info.path.substringAfterLast('/').toInt()
                val expected = SecondProcessWriter.payload(0, index)
                assertContentEquals(expected, store.read(info.path), info.path)
            }
            // The lock died with the writer, and the next write finds the folder usable.
            store.writeNew("crash/after", byteArrayOf(1))
        }

    private companion object {
        const val WRITERS = 3
        const val WRITES_BEFORE_KILL = 3
        const val TIMEOUT_SECONDS = 60L
    }
}
