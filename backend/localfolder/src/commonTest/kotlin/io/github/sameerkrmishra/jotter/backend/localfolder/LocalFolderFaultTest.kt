package io.github.sameerkrmishra.jotter.backend.localfolder

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Errors from the file system must never read as "empty", "missing" or "free". */
class LocalFolderFaultTest {
    private val folders = TempFolders()
    private val fs = FileSystem.SYSTEM

    @AfterTest
    fun deleteFolders() = folders.deleteAll()

    private suspend fun folderWithFile(path: String = "dir/file"): Path =
        folders.newFolder().also {
            linkingStore(it).initialize()
            linkingStore(it).writeNew(path, byteArrayOf(1))
        }

    private fun simulatedEio(): Nothing = throw IOException("simulated EIO")

    /** A store whose drive goes away just before the first look at [trigger]. */
    private fun storeUnmountedBy(
        root: Path,
        trigger: Path,
    ) = storeWith(
        root,
        FolderAccess(stat = { path ->
            if (path == trigger) fs.deleteRecursively(root)
            strictStat(path)
        }),
    )

    private fun canList(path: Path): Boolean =
        try {
            strictListNames(path)
            true
        } catch (_: IOException) {
            false
        }

    @Test
    fun aFailingStatIsReportedByPing() =
        runTest {
            val store = storeWith(folderWithFile(), FolderAccess(stat = { simulatedEio() }))
            val reachability = assertIs<Reachability.Unreachable>(store.ping())
            assertEquals(UnreachableReason.FAILED, reachability.reason)
        }

    @Test
    fun aDirectoryThatFailsPartWayFailsTheListing() =
        runTest {
            val store = storeWith(folderWithFile(), FolderAccess(listNames = { simulatedEio() }))
            assertFailsWith<BlobStoreFailureException> { store.list("dir") }
        }

    @Test
    fun aStoreThatVanishesDuringAListingIsNotPartial() =
        runTest {
            val root = folderWithFile()
            val store = storeUnmountedBy(root, root / "dir" / "file")
            assertFailsWith<BackendUnavailableException> { store.list("dir") }
        }

    @Test
    fun aStoreThatVanishesDuringAReadIsNotAMissingFile() =
        runTest {
            val root = folderWithFile()
            val store = storeUnmountedBy(root, root / "dir")
            assertFailsWith<BackendUnavailableException> { store.read("dir/file") }
        }

    @Test
    fun anErrorFromAVanishedStoreIsNotAFailureOfThatCall() =
        runTest {
            val root = folderWithFile()
            val access =
                FolderAccess(listNames = {
                    fs.deleteRecursively(root)
                    simulatedEio()
                })
            assertFailsWith<BackendUnavailableException> { storeWith(root, access).list("dir") }
        }

    @Test
    fun aRealPermissionErrorIsAFailureAndNeverEmptyOrMissing() =
        runTest {
            val root = folderWithFile("jotter/devices/file")
            val store = linkingStore(root)
            setSearchable(root / "jotter", false)
            try {
                // The superuser ignores permissions, so there would be no fault to see.
                if (canList(root / "jotter")) return@runTest
                assertFailsWith<BlobStoreFailureException> { store.list("jotter/devices") }
                assertFailsWith<BlobStoreFailureException> { store.list("jotter") }
                assertFailsWith<BlobStoreFailureException> { store.read("jotter/devices/file") }
                assertFailsWith<BlobStoreFailureException> {
                    store.writeNew("jotter/devices/new", byteArrayOf(1))
                }
            } finally {
                setSearchable(root / "jotter", true)
            }
        }

    @Test
    fun anUnreadableRootIsUnreachable() =
        runTest {
            val root = folderWithFile()
            val store = linkingStore(root)
            setSearchable(root, false)
            try {
                if (canList(root)) return@runTest
                val reachability = assertIs<Reachability.Unreachable>(store.ping())
                assertEquals(UnreachableReason.FAILED, reachability.reason)
                assertFailsWith<BackendUnavailableException> { store.list("") }
            } finally {
                setSearchable(root, true)
            }
        }

    @Test
    fun strictStatSeesAFileADirectoryALinkAndNothing() {
        val root = folders.newFolder()
        fs.write(root / "file") { writeUtf8("abc") }
        fs.createSymlink(root / "link", root / "file")
        assertEquals(FolderEntry(false, true, 3), strictStat(root / "file"))
        assertEquals(true, strictStat(root)?.isDirectory)
        val link = assertNotNull(strictStat(root / "link"))
        assertFalse(link.isDirectory || link.isRegularFile)
        assertNull(strictStat(root / "missing"))
    }

    @Test
    fun strictStatThrowsForAnythingButAbsent() {
        val root = folders.newFolder()
        fs.write(root / "file") { writeUtf8("abc") }
        assertFailsWith<IOException> { strictStat(root / "file" / "below") } // ENOTDIR
    }

    @Test
    fun strictListNamesGivesNamesAndFailsOnWhatItCannotOpen() {
        val root = folders.newFolder()
        fs.write(root / "a") { }
        fs.write(root / "b") { }
        assertEquals(listOf("a", "b"), strictListNames(root).sorted())
        assertFailsWith<IOException> { strictListNames(root / "missing") }
        assertFailsWith<IOException> { strictListNames(root / "a") }
    }

    @Test
    fun aFailedDirectorySyncFailsTheWriteAndARetryIsDefined() =
        runTest {
            val root = folders.newFolder().also { linkingStore(it).initialize() }
            var syncFails = true
            val access = FolderAccess(syncDirectory = { if (syncFails) simulatedEio() })
            val store = storeWith(root, access)

            assertFailsWith<BlobStoreFailureException> {
                store.writeNew("dir/file", byteArrayOf(1))
            }
            // The file stays, whole, and without a temporary file next to it.
            assertContentEquals(byteArrayOf(1), store.read("dir/file"))
            assertEquals(listOf(root / "dir" / "file"), fs.list(root / "dir"))

            // Still failing: the retry must not say that the file is there for good.
            assertFailsWith<BlobStoreFailureException> {
                store.writeNew("dir/file", byteArrayOf(1))
            }
            syncFails = false
            assertFailsWith<BlobAlreadyExistsException> {
                store.writeNew("dir/file", byteArrayOf(1))
            }
        }

    @Test
    fun aRetryThatFindsTheFileSyncsEveryDirectoryUpToTheRoot() =
        runTest {
            val root = folderWithFile("a/b/file")
            val synced = mutableListOf<Path>()
            val store = storeWith(root, FolderAccess(syncDirectory = { synced += it }))
            assertFailsWith<BlobAlreadyExistsException> {
                store.writeNew("a/b/file", byteArrayOf(1))
            }
            assertEquals(listOf(root / "a" / "b", root / "a", root), synced)
        }

    // The file is there, and okio would see it. Only the strict lookup fails, so a store that
    // still asks okio reports the file as taken instead of the failure.
    @Test
    fun aFailedLookupInTheFallbackFailsTheWriteAndReplacesNothing() =
        runTest {
            val root = folders.newFolder().also { linkingStore(it).initialize() }
            val target = root / "f"
            val access =
                FolderAccess(
                    hardLink = { _, _ ->
                        fs.write(target) { writeUtf8("first") }
                        LinkResult.NOT_LINKED
                    },
                    stat = { path ->
                        if (path == target && fs.exists(path)) simulatedEio()
                        strictStat(path)
                    },
                )
            assertFailsWith<BlobStoreFailureException> {
                storeWith(root, access).writeNew("f", byteArrayOf(1))
            }
            assertEquals("first", fs.read(target) { readUtf8() })
        }

    @Test
    fun withoutALockHardLinksStillRefuseATakenPath() =
        runTest {
            val root = folderWithFile("f")
            val store = locklessStore(root)
            store.writeNew("g", byteArrayOf(2))
            assertContentEquals(byteArrayOf(2), store.read("g"))
            assertFailsWith<BlobAlreadyExistsException> { store.writeNew("f", byteArrayOf(2)) }
        }

    @Test
    fun withNeitherLockNorHardLinksNothingIsPublished() =
        runTest {
            val root = folders.newFolder().also { linkingStore(it).initialize() }
            val access =
                FolderAccess(lock = ::failToLock, hardLink = { _, _ -> LinkResult.NOT_LINKED })
            val failure =
                assertFailsWith<BlobStoreFailureException> {
                    storeWith(root, access).writeNew("dir/file", byteArrayOf(1))
                }
            assertContains(failure.message.orEmpty(), "no lock")
            assertEquals(emptyList(), fs.list(root / "dir"))
        }

    @Test
    fun aLockThatCannotBeTakenIsReportedToTheBlock() {
        val root = folders.newFolder()
        var failure: IOException? = null
        withProcessFileLock(root / "missing" / "lock") { failure = it }
        assertNotNull(failure)
    }

    @Test
    fun aLockThatIsTakenIsReportedAsHeld() {
        val root = folders.newFolder()
        var ran = false
        withProcessFileLock(root / "lock") { lockFailure ->
            ran = true
            assertNull(lockFailure)
        }
        assertTrue(ran)
    }

    @Test
    fun realDirectorySyncWorksAndFailsOnAMissingDirectory() {
        val root = folders.newFolder()
        syncDirectory(root)
        assertFailsWith<IOException> { syncDirectory(root / "missing") }
    }
}
