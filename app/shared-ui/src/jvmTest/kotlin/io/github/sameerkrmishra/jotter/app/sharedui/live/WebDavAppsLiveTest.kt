package io.github.sameerkrmishra.jotter.app.sharedui.live

import io.github.sameerkrmishra.jotter.app.sharedui.WebDavTwoApps
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two apps syncing through lighttpd in a container, as `backend/webdav/scripts/live-test.sh`
 * starts it for the `webdav` CI job. Only that script runs this package, with
 * `JOTTER_WEBDAV_LIVE=1` and the server's URL; `check` leaves it out.
 */
class WebDavAppsLiveTest {
    private val server =
        checkNotNull(System.getenv("JOTTER_WEBDAV_LIGHTTPD_URL")) {
            "Run this through backend/webdav/scripts/live-test.sh"
        }

    @Test
    fun twoAppsSyncThroughLighttpd() {
        // A collection of its own: the store must exist, and other live tests share the server.
        val url = "${server.trimEnd('/')}/apps-${Random.nextLong().toULong()}/"
        val request =
            HttpRequest
                .newBuilder(URI(url))
                .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                .header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString("$USER:$PASSWORD".toByteArray()),
                ).build()
        val status =
            // HTTP/1.1: lighttpd ends the connection when the JDK tries to upgrade to HTTP/2.
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use {
                it.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            }
        assertEquals(CREATED, status)
        WebDavTwoApps(url, USER, PASSWORD).run()
    }

    private companion object {
        // The test account of the script's servers. Not a real account.
        const val USER = "jotter"
        const val PASSWORD = "secret"
        const val CREATED = 201
    }
}
