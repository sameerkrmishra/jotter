package io.github.sameerkrmishra.jotter.backend.localfolder

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What the contract suite cannot say, because it is about this store's folder on disk. */
class LocalFolderBlobStoreTest {
    private val folders = TempFolders()
    private val fs = FileSystem.SYSTEM

    @AfterTest
    fun deleteFolders() = folders.deleteAll()

    private suspend fun readyFolder(): Path =
        folders.newFolder().also { linkingStore(it).initialize() }

    private fun Path.write(text: String = "x") {
        fs.createDirectories(parent!!)
        fs.write(this) { writeUtf8(text) }
    }

    private fun reasonOf(reachability: Reachability): UnreachableReason =
        assertIs<Reachability.Unreachable>(reachability).reason

    @Test
    fun aMissingFolderIsMissing() =
        runTest {
            val store = linkingStore(folders.newPath())
            assertEquals(UnreachableReason.MISSING, reasonOf(store.ping()))
            val failure = assertFailsWith<BackendUnavailableException> { store.list("") }
            assertEquals(UnreachableReason.MISSING, failure.reachability.reason)
        }

    @Test
    fun aDriveThatGoesAwayIsNotAnEmptyStore() =
        runTest {
            val root = readyFolder()
            val store = linkingStore(root)
            store.writeNew("jotter/format.json", byteArrayOf(1))
            fs.deleteRecursively(root)
            assertEquals(UnreachableReason.MISSING, reasonOf(store.ping()))
            assertFailsWith<BackendUnavailableException> { store.list("jotter") }
            assertFailsWith<BackendUnavailableException> { store.read("jotter/format.json") }
        }

    @Test
    fun aBareMountPointIsNotAnEmptyStoreAndStaysUntouched() =
        runTest {
            val mountPoint = folders.newFolder()
            val store = linkingStore(mountPoint)
            assertEquals(UnreachableReason.NOT_INITIALIZED, reasonOf(store.ping()))
            assertFailsWith<BackendUnavailableException> { store.list("") }
            assertFailsWith<BackendUnavailableException> { store.read("a") }
            assertFailsWith<BackendUnavailableException> { store.writeNew("a/b", byteArrayOf(1)) }
            assertEquals(emptyList(), fs.list(mountPoint))
        }

    @Test
    fun aFolderWithFilesButNoMarkerIsNotAStoreEither() =
        runTest {
            val root = folders.newFolder()
            (root / "jotter" / "format.json").write()
            assertEquals(UnreachableReason.NOT_INITIALIZED, reasonOf(linkingStore(root).ping()))
        }

    @Test
    fun aFileInsteadOfAFolderIsUnreachable() =
        runTest {
            val file = folders.newFolder() / "file"
            file.write()
            assertIs<Reachability.Unreachable>(linkingStore(file).ping())
        }

    @Test
    fun aRootBehindASymbolicLinkWorks() =
        runTest {
            val root = readyFolder()
            val link = folders.newFolder() / "link"
            fs.createSymlink(link, root)
            val store = linkingStore(link)
            assertEquals(Reachability.Reachable, store.ping())
            store.writeNew("a/file", byteArrayOf(1))
            assertEquals(listOf(BlobInfo("a/file", 1)), store.list(""))
        }

    @Test
    fun initializeNeedsAnExistingFolder() =
        runTest {
            val root = folders.newPath()
            assertFailsWith<BackendUnavailableException> { linkingStore(root).initialize() }
            assertFalse(fs.exists(root))
        }

    @Test
    fun initializeTwiceKeepsTheFiles() =
        runTest {
            val root = readyFolder()
            val store = linkingStore(root)
            store.writeNew("a", byteArrayOf(1))
            store.initialize()
            assertEquals(listOf(BlobInfo("a", 1)), store.list(""))
        }

    @Suppress("InjectDispatcher") // Tests choose their threads directly.
    @Test
    fun concurrentInitializeSucceeds() =
        runTest {
            val root = folders.newFolder()
            (0 until WRITERS)
                .map { async(Dispatchers.Default) { linkingStore(root).initialize() } }
                .awaitAll()
            assertEquals(Reachability.Reachable, linkingStore(root).ping())
        }

    @Test
    fun aFailureToInitializeIsAStoreFailure() =
        runTest {
            val root = folders.newFolder()
            val fileSystem = FaultyFileSystem { operation, _ -> operation == "sink" }
            assertFailsWith<BlobStoreFailureException> {
                storeWith(root, fileSystem = fileSystem).initialize()
            }
        }

    @Test
    fun aWriteLeavesOnlyTheFileBehind() =
        runTest {
            listOf(::linkingStore, ::renamingStore).forEach { factory ->
                val root = readyFolder()
                factory(root).writeNew("dir/file", byteArrayOf(1))
                assertEquals(listOf(root / "dir" / "file"), fs.list(root / "dir"))
            }
        }

    @Test
    fun theStoreKeepsItsOwnFilesOutOfTheListing() =
        runTest {
            val root = readyFolder()
            val store = linkingStore(root)
            store.writeNew("a", byteArrayOf(1))
            assertTrue(fs.exists(root / MARKER_NAME) && fs.exists(root / LOCK_NAME))
            assertEquals(listOf("a"), store.list("").map { it.path })
        }

    @Test
    fun staleTemporaryFilesFromACrashAreInvisibleAndHarmless() =
        runTest {
            val root = readyFolder()
            val store = linkingStore(root)
            (root / "dir" / "${TEMPORARY_PREFIX}1").write("half a seg")
            (root / "${TEMPORARY_PREFIX}2").write("half a seg")

            assertEquals(emptyList(), store.list(""))
            assertEquals(emptyList(), store.list("dir"))
            assertFailsWith<BlobNotFoundException> { store.read("dir/file") }
            store.writeNew("dir/file", byteArrayOf(1))
            assertEquals(listOf("dir/file"), store.list("").map { it.path })
        }

    @Test
    fun aFailedWriteLeavesNothingBehind() =
        runTest {
            listOf(
                "writing the file" to "openReadWrite",
                "publishing it" to "atomicMove",
            ).forEach { (what, operation) ->
                val root = readyFolder()
                val fileSystem = FaultyFileSystem { name, _ -> name == operation }
                val noLinks = FolderAccess(hardLink = { _, _ -> LinkResult.NOT_SUPPORTED })
                val store = storeWith(root, noLinks, fileSystem)
                val failure =
                    assertFailsWith<BlobStoreFailureException>(what) {
                        store.writeNew("dir/file", byteArrayOf(1))
                    }
                assertContains(failure.message.orEmpty(), "dir/file")
                assertEquals(emptyList(), fs.list(root / "dir"), what)
            }
        }

    @Test
    fun aDirectoryThatCannotBeMadeFailsTheWrite() =
        runTest {
            val root = readyFolder()
            val fileSystem = FaultyFileSystem { name, _ -> name == "createDirectory" }
            val store = storeWith(root, fileSystem = fileSystem)
            assertFailsWith<BlobStoreFailureException> { store.writeNew("a/b", byteArrayOf(1)) }
        }

    @Test
    fun aDirectoryMadeByAConcurrentWriterIsUsed() =
        runTest {
            val root = readyFolder()
            val fileSystem =
                FaultyFileSystem { name, path ->
                    if (name == "createDirectory") fs.createDirectory(path)
                    name == "createDirectory"
                }
            storeWith(root, fileSystem = fileSystem).writeNew("a/b", byteArrayOf(1))
            assertContentEquals(byteArrayOf(1), linkingStore(root).read("a/b"))
        }

    @Test
    fun aFailureToRemoveTheTemporaryFileDoesNotFailTheWrite() =
        runTest {
            val root = readyFolder()
            val fileSystem = FaultyFileSystem { name, _ -> name == "delete" }
            val store = storeWith(root, fileSystem = fileSystem)
            store.writeNew("file", byteArrayOf(1))
            assertContentEquals(byteArrayOf(1), linkingStore(root).read("file"))
        }

    @Test
    fun aFileThatAppearsDuringAWriteWinsWithHardLinks() =
        runTest {
            val root = readyFolder()
            val store =
                storeWith(
                    root,
                    FolderAccess(hardLink = { _, target ->
                        target.write("first")
                        systemHardLink(root / MARKER_NAME, target)
                    }),
                )
            assertFailsWith<BlobAlreadyExistsException> { store.writeNew("f", byteArrayOf(1)) }
            assertContentEquals("first".encodeToByteArray(), linkingStore(root).read("f"))
        }

    @Test
    fun aFileThatAppearsDuringAWriteWinsWithoutHardLinks() =
        runTest {
            val root = readyFolder()
            val store =
                storeWith(
                    root,
                    FolderAccess(hardLink = { _, target ->
                        target.write("first")
                        LinkResult.NOT_SUPPORTED
                    }),
                )
            assertFailsWith<BlobAlreadyExistsException> { store.writeNew("f", byteArrayOf(1)) }
            assertContentEquals("first".encodeToByteArray(), linkingStore(root).read("f"))
        }

    @Test
    fun aDriveUnpluggedWhilePublishingFailsTheWrite() =
        runTest {
            val root = readyFolder()
            val fileSystem =
                FaultyFileSystem { operation, _ ->
                    // The marker goes after the first check, as the file is being written.
                    if (operation == "openReadWrite") fs.delete(root / MARKER_NAME)
                    false
                }
            val noPublishing = FolderAccess(hardLink = { _, _ -> error("must not publish") })
            val store = storeWith(root, noPublishing, fileSystem)
            assertFailsWith<BackendUnavailableException> { store.writeNew("f", byteArrayOf(1)) }
            assertEquals(listOf(root / LOCK_NAME), fs.list(root))
        }

    @Test
    fun symbolicLinksAreNotFollowedOutOfTheStore() =
        runTest {
            val root = readyFolder()
            val outside = folders.newFolder()
            (outside / "secret").write("secret")
            fs.createSymlink(root / "dir", outside)
            fs.createSymlink(root / "file", outside / "secret")
            val store = linkingStore(root)

            assertEquals(emptyList(), store.list(""))
            assertEquals(emptyList(), store.list("dir"))
            assertFailsWith<BlobNotFoundException> { store.read("dir/secret") }
            assertFailsWith<BlobNotFoundException> { store.read("file") }
            assertFailsWith<BlobStoreFailureException> {
                store.writeNew("dir/new", byteArrayOf(1))
            }
            assertFailsWith<BlobAlreadyExistsException> { store.writeNew("file", byteArrayOf(1)) }
            assertEquals(listOf(outside / "secret"), fs.list(outside))
        }

    @Test
    fun aFileNameThatNoPathCanHaveIsIgnored() =
        runTest {
            val root = readyFolder()
            val store = linkingStore(root)
            (root / "note:1").write()
            store.writeNew("kept", byteArrayOf(1))
            assertEquals(listOf("kept"), store.list("").map { it.path })
        }

    @Test
    fun readFailuresAreStoreFailures() =
        runTest {
            val root = readyFolder()
            linkingStore(root).writeNew("f", byteArrayOf(1))
            val fileSystem = FaultyFileSystem { name, _ -> name == "source" }
            val store = storeWith(root, fileSystem = fileSystem)
            assertFailsWith<BlobStoreFailureException> { store.read("f") }
        }

    @Test
    fun contentIsStoredVerbatim() =
        runTest {
            val root = readyFolder()
            linkingStore(root).writeNew("f", "abc".encodeToByteArray())
            assertEquals("abc".encodeUtf8(), fs.read(root / "f") { readByteString() })
        }

    private companion object {
        const val WRITERS = 8
    }
}
