package io.github.sameerkrmishra.jotter.backend.localfolder

import androidx.test.platform.app.InstrumentationRegistry
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import kotlinx.coroutines.runBlocking
import okio.Path
import okio.Path.Companion.toPath
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Separate processes of one app on one folder, as the app and its sync worker could be. On Android
 * a store publishes by rename (see [AndroidStorageTest]), so only the `fcntl` lock keeps two
 * processes from replacing each other's files: with a lock that is never taken, this test fails on
 * both storages, as checked on API 36. The other processes run this test APK's code through
 * `app_process`, with the app's user and SELinux domain.
 */
class TwoProcessDeviceTest {
    private val folders = listOf(TempFolders(appStorage), TempFolders(sharedStorage))

    @AfterTest
    fun deleteFolders() = folders.forEach { it.deleteAll() }

    private class Writer(
        val process: Process,
        val errors: File,
    ) {
        val output = BufferedReader(InputStreamReader(process.inputStream))

        fun go() {
            process.outputStream.write('\n'.code)
            process.outputStream.flush()
        }

        fun errorText(): String = errors.readText()
    }

    private fun start(
        root: Path,
        writer: Int,
    ): Writer {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val apk = instrumentation.context.applicationInfo.sourceDir
        val errors = File(instrumentation.targetContext.cacheDir, "writer-$writer.err")
        val process =
            ProcessBuilder(
                "/system/bin/app_process",
                "/system/bin",
                DeviceSecondWriter::class.java.name,
                root.toString(),
                writer.toString(),
            ).apply { environment()["CLASSPATH"] = apk }
                .redirectError(errors)
                .start()
        return Writer(process, errors)
    }

    @Test
    fun processesRacingInAppStorageHaveOneWinnerPerPath() = raceProcesses(folders[0].newFolder())

    @Test
    fun processesRacingInSharedStorageHaveOneWinnerPerPath() = raceProcesses(folders[1].newFolder())

    private fun raceProcesses(root: Path) =
        runBlocking {
            val store = linkingStore(root).apply { initialize() }
            val writers = (0 until WRITERS).map { start(root, it) }
            try {
                writers.forEach { writer ->
                    val first = writer.output.readLine()
                    assertEquals(DeviceSecondWriter.READY, first, writer.errorText())
                }
                writers.forEach { it.go() }
                val output = writers.map { writer -> writer.output.readLines() }
                writers.forEachIndexed { index, writer ->
                    assertTrue(writer.process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    val exit = writer.process.exitValue()
                    assertEquals(0, exit, "writer $index: ${writer.errorText()}")
                }

                val won = output.map { lines -> lines.map { it.toInt() } }
                val allPaths = (0 until DeviceSecondWriter.RACED_PATHS).toList()
                assertEquals(allPaths, won.flatten().sorted())
                assertTrue(won.count { it.isNotEmpty() } > 1, "no race took place: $won")
                won.forEachIndexed { writer, indexes ->
                    indexes.forEach { index ->
                        val expected = byteArrayOf(writer.toByte())
                        assertContentEquals(expected, store.read("race/$index"))
                    }
                }
            } finally {
                writers.forEach {
                    it.process.destroyForcibly()
                    it.errors.delete()
                }
            }
        }

    private companion object {
        const val WRITERS = 3
        const val TIMEOUT_SECONDS = 120L
    }
}

/** The other processes of [TwoProcessDeviceTest]. */
internal object DeviceSecondWriter {
    const val READY = "ready"
    const val RACED_PATHS = 100

    /** Arguments: root folder, writer number. Prints each index it won. */
    @JvmStatic
    fun main(args: Array<String>) {
        val store = linkingStore(args[0].toPath())
        val writer = args[1].toInt()
        println(READY)
        readln() // Lets all writers start together.
        runBlocking {
            repeat(RACED_PATHS) { index ->
                try {
                    store.writeNew("race/$index", byteArrayOf(writer.toByte()))
                    println(index)
                } catch (_: BlobAlreadyExistsException) {
                    // Another process won this one.
                }
            }
        }
    }
}
