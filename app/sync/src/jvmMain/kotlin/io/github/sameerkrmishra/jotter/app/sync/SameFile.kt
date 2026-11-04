package io.github.sameerkrmishra.jotter.app.sync

import okio.Path
import java.io.IOException

/**
 * Whether [a] and [b] are one file on the disk, by `Files.isSameFile`, which follows links and
 * sees through a case-insensitive file system. Two paths that cannot be asked about count as
 * different: the paths themselves are compared elsewhere. For `DatabaseExport`'s `sameFile`.
 */
@Suppress("SwallowedException") // Not knowing is "different"; the caller compares paths too.
public fun sameFileOnDisk(
    a: Path,
    b: Path,
): Boolean =
    try {
        java.nio.file.Files
            .isSameFile(a.toNioPath(), b.toNioPath())
    } catch (e: IOException) {
        false
    }
