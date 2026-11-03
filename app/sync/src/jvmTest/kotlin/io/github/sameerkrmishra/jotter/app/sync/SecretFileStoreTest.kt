package io.github.sameerkrmishra.jotter.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The file a user may choose for secrets, in a temporary folder. */
@OptIn(ExperimentalPathApi::class)
class SecretFileStoreTest {
    private val home = createTempDirectory("jotter-secret-file")
    private val folder = home.resolve("config/jotter")
    private val store = SecretFileStore(folder, Dispatchers.IO)
    private val password = SecretKey("b-1", SecretKind.APP_PASSWORD)
    private val pairing = SecretKey("b-2", SecretKind.PAIRING_SECRET)

    @AfterTest
    fun cleanUp() {
        home.deleteRecursively()
    }

    private fun mode(path: java.nio.file.Path) =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun itKeepsSecretsInAFileOnlyThisUserCanRead() =
        runTest {
            assertNull(store.read(password))
            assertFalse(Files.exists(folder))
            store.write(password, Secret("one"))
            store.write(pairing, Secret("two"))
            assertEquals("rwx------", mode(folder))
            assertEquals("rw-------", mode(store.file))
            assertEquals("one", store.read(password)?.reveal())
            assertEquals("two", store.read(pairing)?.reveal())

            store.delete(password)
            assertNull(store.read(password))
            assertEquals("two", store.read(pairing)?.reveal())
            store.delete(password)
            // Only the file and its lock: no temporary file is left behind.
            assertEquals(
                setOf("secrets.json", "secrets.lock"),
                folder.listDirectoryEntries().map { it.name }.toSet(),
            )
            assertEquals("rw-------", mode(folder.resolve("secrets.lock")))
        }

    @Test
    fun aFileOthersMayReadIsRefused() =
        runTest {
            store.write(password, Secret("one"))
            Files.setPosixFilePermissions(store.file, PosixFilePermissions.fromString("rw-r--r--"))
            val failure = assertFailsWith<SecretStoreException> { store.read(password) }
            assertTrue(failure.message!!.contains("other users may read it"), failure.message)
            assertFailsWith<SecretStoreException> { store.write(pairing, Secret("x")) }
        }

    @Test
    fun aLinkIsRefused() =
        runTest {
            val elsewhere = home.resolve("elsewhere.json")
            elsewhere.writeText("{}")
            Files.setPosixFilePermissions(elsewhere, PosixFilePermissions.fromString("rw-------"))
            Files.createDirectories(folder)
            Files.createSymbolicLink(store.file, elsewhere)
            val failure = assertFailsWith<SecretStoreException> { store.read(password) }
            assertTrue(failure.message!!.contains("it is a link"), failure.message)
        }

    @Test
    fun aDamagedFileNeverShowsItsText() =
        runTest {
            store.write(password, Secret("one"))
            store.file.writeText("""{"b-1/APP_PASSWORD": "hunter2" """)
            val failure = assertFailsWith<SecretStoreException> { store.read(password) }
            assertTrue(failure.message!!.contains("is damaged"), failure.message)
            // Nor does any cause, such as the parser's message, which can quote the text.
            generateSequence<Throwable>(failure) { it.cause }.forEach {
                assertFalse(it.toString().contains("hunter2"), it.toString())
            }

            store.file.writeText("""["hunter2"]""")
            val other = assertFailsWith<SecretStoreException> { store.read(password) }
            assertFalse(other.message!!.contains("hunter2"))
        }

    @Test
    fun aFolderThatCannotBeMadeSaysSo() =
        runTest {
            home.resolve("config").writeText("a file where the folder should be")
            val failure =
                assertFailsWith<SecretStoreException> { store.write(password, Secret("x")) }
            assertTrue(failure.message!!.startsWith("Cannot create the folder"), failure.message)
        }

    @Test
    fun anUnreadableFileSaysSo() =
        runTest {
            store.write(password, Secret("one"))
            Files.setPosixFilePermissions(store.file, PosixFilePermissions.fromString("-w-------"))
            val failure = assertFailsWith<SecretStoreException> { store.read(password) }
            assertTrue(failure.message!!.contains("Cannot read"), failure.message)
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"))
            val write = assertFailsWith<SecretStoreException> { store.write(pairing, Secret("x")) }
            assertTrue(write.message!!.contains("Cannot"), write.message)
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"))
        }

    @Test
    fun theLinuxStoresFollowXdg() =
        runTest {
            val env = mapOf("XDG_CONFIG_HOME" to "$home/xdg", "DBUS_SESSION_BUS_ADDRESS" to "")
            val stores = linuxSecretStores(env::get, "/nowhere", Dispatchers.IO)
            assertEquals("$home/xdg/jotter/secrets.json", stores.fileLocation)
            assertEquals(
                "$home/h/.config/jotter/secrets.json",
                linuxSecretStores(mapOf("HOME" to "$home/h")::get, "/x", Dispatchers.IO)
                    .fileLocation,
            )
            assertEquals(
                "/fallback/.config",
                configDirectory(
                    mapOf("XDG_CONFIG_HOME" to "relative")::get,
                    "/fallback",
                ).toString(),
            )
            val homeless = linuxSecretStores(mapOf<String, String>()::get, "?", Dispatchers.IO)
            assertNull(homeless.fileLocation)
            assertNull(homeless.file.read(password))
            homeless.file.delete(password)
            assertFailsWith<SecretStoreException> { homeless.file.write(password, Secret("x")) }
            // The empty bus address counts as none, so the keyring fails closed.
            assertFailsWith<SecretStoreUnavailableException> {
                SecretServiceStore(null, Dispatchers.IO, listOf("/nonexistent")).read(password)
            }
            assertTrue(stores.of(SecretPlace.KEYRING) is SecretServiceStore)
            assertEquals(stores.fileLocation, (stores.file as SecretFileStore).file.toString())
            assertTrue(SecretStores.NONE.of(SecretPlace.FILE) === NoSecretStore)
            // Nothing is created until a secret is written.
            assertFalse(Files.exists(home.resolve("xdg")))
        }
}
