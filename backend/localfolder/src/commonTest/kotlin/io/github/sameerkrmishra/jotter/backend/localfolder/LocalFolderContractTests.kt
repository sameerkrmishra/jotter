package io.github.sameerkrmishra.jotter.backend.localfolder

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.contract.BlobStoreContract
import okio.FileSystem
import okio.Path
import kotlin.test.AfterTest

/** The contract suite on real folders, with stores made by [newStoreAt]. */
@Suppress("AbstractClassCanBeConcreteClass") // Concrete, the test runners would try to run it.
abstract class FolderContract internal constructor(
    private val newStoreAt: (Path) -> LocalFolderBlobStore,
) : BlobStoreContract() {
    private val folders = TempFolders()
    private val roots = HashMap<BlobStore, Path>()

    override suspend fun newStore(): BlobStore {
        val root = folders.newFolder()
        return newStoreAt(root).also {
            it.initialize()
            roots[it] = root
        }
    }

    override suspend fun unreachableStore(): BlobStore = newStoreAt(folders.newPath())

    override suspend fun takeAway(store: BlobStore) {
        FileSystem.SYSTEM.deleteRecursively(roots.getValue(store))
    }

    @AfterTest
    fun deleteFolders() = folders.deleteAll()
}

/** With hard links, on whatever file system holds the temporary directory. */
class HardLinkContractTest : FolderContract(::linkingStore)

/** With the fallback for file systems without hard links. */
class RenameContractTest : FolderContract(::renamingStore)

/** With hard links but no locks: a hard link alone must keep every promise. */
class NoLockContractTest : FolderContract(::locklessStore)
