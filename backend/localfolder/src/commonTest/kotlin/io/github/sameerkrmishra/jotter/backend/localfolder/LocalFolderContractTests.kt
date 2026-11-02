package io.github.sameerkrmishra.jotter.backend.localfolder

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.contract.BlobStoreContract
import kotlin.test.AfterTest

/** The contract suite with hard links, on whatever file system holds the temporary directory. */
class HardLinkContractTest : BlobStoreContract() {
    private val folders = TempFolders()

    override suspend fun newStore(): BlobStore =
        linkingStore(folders.newFolder()).apply { initialize() }

    override suspend fun unreachableStore(): BlobStore = linkingStore(folders.newPath())

    @AfterTest
    fun deleteFolders() = folders.deleteAll()
}

/** The contract suite with the fallback for file systems without hard links. */
class RenameContractTest : BlobStoreContract() {
    private val folders = TempFolders()

    override suspend fun newStore(): BlobStore =
        renamingStore(folders.newFolder()).apply { initialize() }

    override suspend fun unreachableStore(): BlobStore = renamingStore(folders.newPath())

    @AfterTest
    fun deleteFolders() = folders.deleteAll()
}
