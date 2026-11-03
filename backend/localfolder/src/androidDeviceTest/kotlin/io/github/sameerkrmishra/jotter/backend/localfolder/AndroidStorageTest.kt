package io.github.sameerkrmishra.jotter.backend.localfolder

import androidx.test.platform.app.InstrumentationRegistry
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The app's private storage, where the temporary directory of the other tests is. */
internal val appStorage: Path
    get() =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext.filesDir.path
            .toPath()

/**
 * The app's folder in shared storage (`Android/data/<package>/files`): FUSE on Android 11 and
 * later, sdcardfs before. A folder that another app, such as Syncthing, fills is on this storage.
 */
internal val sharedStorage: Path
    get() =
        requireNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        ) { "No shared storage" }.path.toPath()

/**
 * What ADR-0012 assumes of Android's storage. Neither storage has hard links for an app, so a store
 * on Android always publishes by rename under the `fcntl` lock, which must work on both.
 */
class AndroidStorageTest {
    private val folders = listOf(TempFolders(appStorage), TempFolders(sharedStorage))

    @AfterTest
    fun deleteFolders() = folders.forEach { it.deleteAll() }

    @Test
    fun appStorageRefusesHardLinks() {
        val root = folders[0].newFolder()
        FileSystem.SYSTEM.write(root / "a") { }
        // SELinux denies apps link(2) on their own files (EACCES), on every API level we support.
        assertFailsWith<AccessDeniedException> {
            Files.createLink((root / "b").toNioPath(), (root / "a").toNioPath())
        }
        assertEquals(LinkResult.NOT_SUPPORTED, systemHardLink(root / "a", root / "b"))
    }

    @Test
    fun sharedStorageHasNoHardLinksEither() {
        val root = folders[1].newFolder()
        FileSystem.SYSTEM.write(root / "a") { }
        assertEquals(LinkResult.NOT_SUPPORTED, systemHardLink(root / "a", root / "b"))
    }

    @Test
    fun fcntlLocksCanBeTakenOnBothStorages() {
        folders.forEach { temp ->
            val root = temp.newFolder()
            var failure: java.io.IOException? = java.io.IOException("not run")
            withProcessFileLock(root / LOCK_NAME) { failure = it }
            assertNull(failure, "$root")
        }
    }
}

/** The contract suite in shared storage, with the store as an app gets it. */
class SharedStorageContractTest : FolderContract(::linkingStore, sharedStorage)
