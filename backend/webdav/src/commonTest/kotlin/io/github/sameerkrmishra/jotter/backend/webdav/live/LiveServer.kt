package io.github.sameerkrmishra.jotter.backend.webdav.live

import io.github.sameerkrmishra.jotter.backend.webdav.DavClient
import io.github.sameerkrmishra.jotter.backend.webdav.DavLocation
import io.github.sameerkrmishra.jotter.backend.webdav.DavSettings
import io.github.sameerkrmishra.jotter.backend.webdav.WebDavCredentials
import io.github.sameerkrmishra.jotter.backend.webdav.environmentVariable
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpMethod
import kotlin.random.Random
import kotlin.test.assertTrue

/** The account that scripts/live-test.sh sets up on every server. */
internal val LIVE_CREDENTIALS = WebDavCredentials("jotter", "secret")

/**
 * A real server that scripts/live-test.sh started, at the URL in the environment variable named
 * [variable]. Gives each test a collection of its own, so tests never share files.
 */
internal class LiveServer(
    variable: String,
) : AutoCloseable {
    /** The server's base URL, with a trailing `/`. */
    val url: String =
        requireNotNull(environmentVariable(variable)) {
            "$variable is not set: run backend/webdav/scripts/live-test.sh"
        }.removeSuffix("/") + "/"

    private val admin =
        DavClient(
            CIO.create { requestTimeout = 0 },
            DavLocation.parse(url).origin,
            LIVE_CREDENTIALS,
            DavSettings(),
        )

    /** Creates a new empty collection, and returns its URL with a trailing `/`. */
    suspend fun newCollection(): String {
        val collection = "${url}jotter-test-${Random.nextLong().toULong().toString(HEX)}/"
        val status = admin.send(HttpMethod("MKCOL"), collection).status
        assertTrue(status == CREATED, "MKCOL $collection answered $status")
        return collection
    }

    /** Deletes the collection at [collection] and all it holds. */
    suspend fun delete(collection: String) {
        val status = admin.send(HttpMethod.Delete, collection).status
        assertTrue(status in OK_RANGE, "DELETE $collection answered $status")
    }

    override fun close() = admin.close()

    private companion object {
        const val HEX = 16
        const val CREATED = 201
        val OK_RANGE = 200..299
    }
}
