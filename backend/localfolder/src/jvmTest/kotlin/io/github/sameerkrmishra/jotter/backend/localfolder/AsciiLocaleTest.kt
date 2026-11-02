package io.github.sameerkrmishra.jotter.backend.localfolder

import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Runs in a process of its own, under `LC_ALL=C`, on the folder that [AsciiLocaleTest] made. */
internal object AsciiLocaleLister {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = linkingStore(args[0].toPath())
        runBlocking {
            println(System.getProperty("sun.jnu.encoding"))
            println(store.list("").joinToString(",") { it.path })
            try {
                store.read("café")
                println("read")
            } catch (e: BlobStoreException) {
                println(e::class.simpleName)
            }
        }
    }
}

/** The JVM cannot express a non-ASCII name in the C locale. That must not escape as a crash. */
class AsciiLocaleTest {
    private val folders = TempFolders()

    @AfterTest
    fun deleteFolders() = folders.deleteAll()

    @Test
    fun aNameTheLocaleCannotExpressIsSkippedAndAPathLikeItFails() {
        val root = folders.newFolder()
        runBlocking { linkingStore(root).initialize() }
        // Through the shell, so that the name's bytes do not depend on this process's locale.
        val touch =
            ProcessBuilder("sh", "-c", "touch plain \"\$(printf 'caf\\303\\251')\"")
                .directory(File(root.toString()))
                .start()
        assertEquals(0, touch.waitFor())

        val lister =
            ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").path,
                "-cp",
                System.getProperty("java.class.path"),
                AsciiLocaleLister::class.java.name,
                root.toString(),
            ).redirectError(ProcessBuilder.Redirect.INHERIT)
        lister.environment().apply {
            remove("LANG")
            remove("LC_CTYPE")
            put("LC_ALL", "C")
        }
        val process = lister.start()
        val lines = process.inputReader().readLines()
        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        assertEquals(0, process.exitValue(), "output: $lines")
        assertNotEquals("UTF-8", lines[0], "the child must not be able to express the name")
        assertEquals("plain", lines[1])
        assertEquals("BlobStoreFailureException", lines[2])
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
    }
}
