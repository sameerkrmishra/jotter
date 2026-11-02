@file:OptIn(ExperimentalForeignApi::class)
// detekt takes this source set for production code.
@file:Suppress("UndocumentedPublicFunction", "UndocumentedPublicClass")

package io.github.sameerkrmishra.jotter.backend.localfolder

import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import platform.posix.EACCES
import platform.posix.EINVAL
import platform.posix.EIO
import platform.posix.ENOSPC
import platform.posix.ENOSYS
import platform.posix.ENOTSUP
import platform.posix.EPERM
import platform.posix.chmod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val OWNER_ONLY = 0x1C0u // rwx------

internal actual fun setSearchable(
    directory: Path,
    searchable: Boolean,
) {
    assertEquals(0, chmod(directory.toString(), if (searchable) OWNER_ONLY else 0u))
}

class UnsupportedLinkErrorTest {
    @Test
    fun onlyNoHardLinksCounts() {
        assertTrue(isUnsupportedLinkError(EPERM))
        assertTrue(isUnsupportedLinkError(EACCES))
        assertTrue(isUnsupportedLinkError(ENOTSUP))
        assertTrue(isUnsupportedLinkError(ENOSYS))
        assertFalse(isUnsupportedLinkError(EIO))
        assertFalse(isUnsupportedLinkError(ENOSPC))
    }
}

class UnsupportedSyncErrorTest {
    @Test
    fun onlyNoSuchOperationCountsAsUnsupported() {
        assertTrue(isUnsupportedSyncError(EINVAL))
        assertTrue(isUnsupportedSyncError(ENOSYS))
        assertFalse(isUnsupportedSyncError(EIO))
    }
}
