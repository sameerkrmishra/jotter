package io.github.sameerkrmishra.jotter.backend.webdav.live

import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.webdav.WebDavBlobStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** rclone's `serve webdav`, which ignores `If-None-Match`, must be refused when it is set up. */
class RcloneTest {
    @Test
    fun aServerThatIgnoresIfNoneMatchIsRefused() =
        runTest {
            LiveServer("JOTTER_WEBDAV_RCLONE_URL").use { server ->
                WebDavBlobStore(server.newCollection(), LIVE_CREDENTIALS).use { store ->
                    val failure = assertFailsWith<BlobStoreFailureException> { store.initialize() }
                    assertContains(failure.message.orEmpty(), "ignores If-None-Match")
                    // No marker was written, so the store stays unusable.
                    val reachability = store.ping()
                    assertEquals(
                        UnreachableReason.NOT_INITIALIZED,
                        (reachability as Reachability.Unreachable).reason,
                    )
                }
            }
        }
}
