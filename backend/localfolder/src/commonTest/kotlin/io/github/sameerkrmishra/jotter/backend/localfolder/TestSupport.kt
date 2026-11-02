package io.github.sameerkrmishra.jotter.backend.localfolder

import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random
import kotlin.random.nextULong

/** Temporary directories for one test class instance. [deleteAll] removes them. */
internal class TempFolders {
    private val created = mutableListOf<Path>()

    /** A new, empty directory. */
    fun newFolder(): Path {
        val folder = newPath()
        FileSystem.SYSTEM.createDirectories(folder)
        return folder
    }

    /** A path where nothing exists. */
    fun newPath(): Path {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-folder-${Random.nextULong()}"
        created += path
        return path
    }

    fun deleteAll() {
        created.forEach { FileSystem.SYSTEM.deleteRecursively(it) }
    }
}

/** A store on the real file system, as on a file system with hard links. */
internal fun linkingStore(root: Path) = LocalFolderBlobStore(root)

/** A store on the real file system, as on one without hard links, such as a FAT USB stick. */
internal fun renamingStore(root: Path) =
    storeWith(root, FolderAccess(hardLink = { _, _ -> LinkResult.NOT_SUPPORTED }))

/** A store where the lock cannot be taken, as on storage without `fcntl` locks. */
internal fun locklessStore(root: Path) = storeWith(root, FolderAccess(lock = ::failToLock))

/** A lock that is never taken. */
internal fun failToLock(
    file: Path,
    block: (IOException?) -> Unit,
) = block(IOException("simulated: no locks on $file"))

/** A store with the given parts replaced. */
@Suppress("InjectDispatcher") // Tests choose their threads directly.
internal fun storeWith(
    root: Path,
    access: FolderAccess = FolderAccess(),
    fileSystem: FileSystem = FileSystem.SYSTEM,
) = LocalFolderBlobStore(root, fileSystem, access, Dispatchers.Default)

/** A file system where [failing], given an operation's name and path, makes the operation fail. */
internal class FaultyFileSystem(
    private val failing: (operation: String, path: Path) -> Boolean,
) : ForwardingFileSystem(SYSTEM) {
    override fun onPathParameter(
        path: Path,
        functionName: String,
        parameterName: String,
    ): Path {
        if (failing(functionName, path)) throw IOException("simulated failure of $functionName")
        return path
    }
}
