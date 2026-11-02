@file:OptIn(ExperimentalForeignApi::class)
// detekt takes this source set for production code.
@file:Suppress("UndocumentedPublicFunction")

package io.github.sameerkrmishra.jotter.backend.localfolder

import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.system
import platform.posix.usleep
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FOLDER_VARIABLE = "JOTTER_TEST_RACE_FOLDER"
private const val MODE_VARIABLE = "JOTTER_TEST_RACE_MODE"
private const val RENAME_MODE = "rename"
private const val PATHS = 200
private const val WRITERS = 3
private const val POLL_MICROSECONDS = 1000u

/**
 * [TwoProcessTest] for the native target, whose lock is its own code. The test binary starts itself
 * again, and [writerProcess] does the writing in those copies. It does nothing in a normal run.
 */
class NativeTwoProcessTest {
    private val folders = TempFolders()
    private val fs = FileSystem.SYSTEM

    @AfterTest
    fun deleteFolders() = folders.deleteAll()

    @Test
    fun writerProcess() {
        val folder = getenv(FOLDER_VARIABLE)?.toKString()?.toPath() ?: return
        val root = folder / "store"
        val store =
            if (getenv(MODE_VARIABLE)?.toKString() == RENAME_MODE) {
                renamingStore(root)
            } else {
                linkingStore(root)
            }
        while (!fs.exists(folder / "go")) usleep(POLL_MICROSECONDS)
        val won =
            (0 until PATHS).filter { index ->
                try {
                    runBlocking { store.writeNew("race/$index", byteArrayOf(1)) }
                    true
                } catch (_: BlobAlreadyExistsException) {
                    false
                }
            }
        fs.write(folder / "won-${getpid()}") { writeUtf8(won.joinToString("\n")) }
    }

    @Test
    fun processesRacingWithHardLinksHaveOneWinnerPerPath() = race("link")

    @Test
    fun processesRacingWithoutHardLinksHaveOneWinnerPerPath() = race(RENAME_MODE)

    private fun race(mode: String) {
        val folder = folders.newFolder()
        val root = folder / "store"
        fs.createDirectory(root)
        runBlocking { linkingStore(root).initialize() }

        assertEquals(0, system(shellCommand(folder, mode)), "a writer process failed")

        val won = fs.list(folder).filter { it.name.startsWith("won-") }.map(::wonIndexes)
        assertEquals(WRITERS, won.size)
        assertEquals((0 until PATHS).toList(), won.flatten().sorted())
        assertTrue(won.count { it.isNotEmpty() } > 1, "no race took place: $won")
    }

    private fun wonIndexes(file: Path): List<Int> =
        fs
            .read(file) { readUtf8() }
            .lines()
            .filter { it.isNotEmpty() }
            .map { it.toInt() }

    private fun shellCommand(
        folder: Path,
        mode: String,
    ): String {
        val binary = "/proc/${getpid()}/exe"
        val writer =
            "$FOLDER_VARIABLE=$folder $MODE_VARIABLE=$mode $binary " +
                "--ktest_filter=*.NativeTwoProcessTest.writerProcess > /dev/null"
        val background = List(WRITERS) { "$writer & pids=\"\$pids \$!\"" }.joinToString("; ")
        val waitAll = "for pid in \$pids; do wait \$pid || exit 1; done"
        return "$background; sleep 0.3; touch $folder/go; $waitAll"
    }
}
