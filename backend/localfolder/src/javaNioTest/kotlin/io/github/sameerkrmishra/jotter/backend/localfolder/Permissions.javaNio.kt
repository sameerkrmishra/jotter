// detekt takes this source set for production code.
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.github.sameerkrmishra.jotter.backend.localfolder

import okio.Path
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal actual fun setSearchable(
    directory: Path,
    searchable: Boolean,
) {
    val mode = if (searchable) "rwx------" else "---------"
    Files.setPosixFilePermissions(directory.toNioPath(), PosixFilePermissions.fromString(mode))
}

class UnsupportedLinkFailureTest {
    @Test
    fun onlyExactTextsOrAFileStoreWithoutHardLinksCount() {
        assertTrue(isUnsupportedLinkFailure("Operation not permitted", "ext4"))
        assertTrue(isUnsupportedLinkFailure("Operation not supported", "ext4"))
        assertTrue(isUnsupportedLinkFailure("Die Operation ist nicht erlaubt", "vfat"))
        assertFalse(isUnsupportedLinkFailure("Input/output error", "ext4"))
        assertFalse(isUnsupportedLinkFailure("No space left on device", "ext4"))
        assertFalse(isUnsupportedLinkFailure(null, "ext4"))
    }
}

class UnsupportedSyncFailureTest {
    @Test
    fun onlyNoSuchOperationCountsAsUnsupported() {
        assertTrue(isUnsupportedSyncFailure(IOException("Invalid argument")))
        assertTrue(isUnsupportedSyncFailure(IOException("/a: Is a directory")))
        assertFalse(isUnsupportedSyncFailure(IOException("Input/output error")))
        assertFalse(isUnsupportedSyncFailure(IOException()))
    }
}
