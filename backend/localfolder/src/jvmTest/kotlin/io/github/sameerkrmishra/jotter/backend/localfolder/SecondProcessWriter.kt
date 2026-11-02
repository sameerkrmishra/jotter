package io.github.sameerkrmishra.jotter.backend.localfolder

import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath

/** The other process of [TwoProcessTest], started with this test's classpath. */
internal object SecondProcessWriter {
    const val LINK = "link"
    const val RENAME = "rename"
    const val RACE = "race"
    const val WRITE_FOREVER = "write-forever"
    const val READY = "ready"
    const val PAYLOAD_SIZE = 1 shl 20
    const val RACED_PATHS = 200

    /** The content of the file at [index], different per [writer]. */
    fun payload(
        writer: Int,
        index: Int,
    ) = ByteArray(PAYLOAD_SIZE) { (writer + index + it / 4096).toByte() }

    /** Arguments: root folder, [LINK] or [RENAME], [RACE] or [WRITE_FOREVER], writer number. */
    @Suppress("MagicNumber") // The argument positions, documented above.
    @JvmStatic
    fun main(args: Array<String>) {
        val root = args[0]
        val publishing = args[1]
        val task = args[2]
        val writer = args[3]
        val store =
            if (publishing == LINK) linkingStore(root.toPath()) else renamingStore(root.toPath())
        println(READY)
        readln() // Lets all writers start together.
        runBlocking {
            if (task == RACE) {
                repeat(RACED_PATHS) { index ->
                    try {
                        store.writeNew("race/$index", ByteArray(1) { writer.toByte() })
                        println(index)
                    } catch (_: BlobAlreadyExistsException) {
                        // Another process won this one.
                    }
                }
            } else {
                generateSequence(0) { it + 1 }.forEach { index ->
                    store.writeNew("crash/$index", payload(0, index))
                    println(index)
                }
            }
        }
    }
}
