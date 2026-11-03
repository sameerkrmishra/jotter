package io.github.sameerkrmishra.jotter.app.android

import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.app.sync.Secret
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * On a phone without Tailscale, as this emulator is, a hub's token goes to loopback only, and the
 * app's own backend manager refuses a Tailscale address at once.
 */
@RunWith(AndroidJUnit4::class)
class TailnetTest {
    private val test = TestGraph()

    @After
    fun cleanUp() {
        test.close()
    }

    @Test
    fun withoutTailscaleOnlyLoopbackGetsAToken() {
        val check = AndroidTailnet(test.context.getSystemService(ConnectivityManager::class.java))
        assertEquals(TAILSCALE_OFF, check.refuse("100.101.102.103"))
        assertEquals(TAILSCALE_OFF, check.refuse("pc.tail1234.ts.net"))
        assertNull(check.refuse("127.0.0.1"))
    }

    @Test
    fun theAppRefusesAHubOffTheTailnetWithoutTryingIt() =
        runBlocking {
            val manager = test.ready.manager
            val took =
                measureTime {
                    val added = manager.addPeer("100.101.102.103", Secret("id.secret"))
                    assertEquals(
                        "Cannot reach the hub at 100.101.102.103:47734: $TAILSCALE_OFF. Check " +
                            "that the hub is on and that Tailscale runs on both devices.",
                        assertIs<AddResult.Refused>(added).reason,
                    )
                }
            // Not the client's 10 s connect timeout: nothing was tried.
            assertTrue(took < 5.seconds, "took $took")
            assertEquals(emptyList(), manager.list())
        }
}
