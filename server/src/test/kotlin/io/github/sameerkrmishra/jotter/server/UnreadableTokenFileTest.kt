package io.github.sameerkrmishra.jotter.server

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A token file that cannot be read is never read as "no devices", which pairing would then
 * replace. On the JVM reading such a file fails as "not found", which is why this needs a test.
 * JVM only: it needs `chmod`, and a user who is not root.
 */
class UnreadableTokenFileTest {
    private val folders = TempFolders()

    @AfterTest
    fun deleteFolders() = folders.deleteAll()

    @Test
    fun anUnreadableFileStopsTheHubAndIsNotReplaced() =
        runTest {
            val root = folders.newPath()
            val config = HubConfig(root / "store", root / "devices.json", port = 0)
            FileSystem.SYSTEM.createDirectories(root)
            FileSystem.SYSTEM.write(config.tokenFile) { writeUtf8("kept") }
            val file = config.tokenFile.toNioPath()
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))
            try {
                val hub = PeerHub(config)
                assertFailsWith<HubStartException> { hub.start() }
                assertFailsWith<IOException> { hub.pair("phone") }
            } finally {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            }
            assertEquals("kept", FileSystem.SYSTEM.read(config.tokenFile) { readUtf8() })
        }
}
