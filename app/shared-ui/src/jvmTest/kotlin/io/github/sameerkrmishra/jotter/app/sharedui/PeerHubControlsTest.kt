@file:OptIn(ExperimentalPathApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 10_000L

/** The desktop app's hub controls, on 127.0.0.1, in a temporary folder. */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class PeerHubControlsTest {
    private val folders: Path = createTempDirectory("jotter-hub-controls")
    private val device = SyncedDevice("pc")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        device.close()
        folders.deleteRecursively()
    }

    private fun controls(defaults: HubSettings = HubSettings("$folders/store", "127.0.0.1", 0)) =
        PeerHubControls(
            device.manager,
            folders.resolve("config/hub.properties"),
            folders.resolve("config/hub-devices.json"),
            scope,
            Dispatchers.IO,
            defaults,
        )

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out" }
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun aHubLeftOnStartsAgainWithItsDevices() =
        runBlocking {
            val first = controls()
            assertNull(first.turnOn(first.settings))
            waitFor { first.state.value is HubState.Running }
            val pairing = first.pair("Phone")
            assertTrue(
                pairing.toString().contains("Phone") && !pairing.toString().contains(pairing.token),
            )
            assertEquals(listOf(pairing.device), first.devices())
            // The folder is this PC's own backend too.
            assertEquals(1, device.manager.list().size)
            // Settings and paired devices are this user's alone, from the moment they exist.
            for (name in listOf("hub.properties", "hub-devices.json")) {
                val mode = Files.getPosixFilePermissions(folders.resolve("config/$name"))
                assertEquals("rw-------", PosixFilePermissions.toString(mode), name)
            }
            assertEquals(
                "rwx------",
                PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(folders.resolve("config")),
                ),
            )
            assertEquals(
                setOf("hub.properties", "hub-devices.json"),
                Files.list(folders.resolve("config")).use { s ->
                    s.map { it.fileName.toString() }.toList().toSet()
                },
            )
            first.turnOff()
            assertEquals(
                "rw-------",
                PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(folders.resolve("config/hub.properties")),
                ),
            )
            waitFor { first.state.value == HubState.Off }

            assertTrue(first.settings.on.not())
            assertNull(first.turnOn(first.settings))
            val second = controls(HubSettings("/elsewhere", "127.0.0.2", 1))
            assertEquals(first.settings, second.settings)
            first.turnOff()
            // Left on in the settings file, as the app quits without turning it off.
            Files.writeString(
                folders.resolve("config/hub.properties"),
                "folder=$folders/store\nhost=127.0.0.1\nport=0\non=true\n",
            )
            val third = controls()
            third.resume()
            waitFor { third.state.value is HubState.Running }
            assertEquals(listOf(pairing.device), third.devices())
            third.unpair(pairing.device.id)
            assertEquals(emptyList(), third.devices())
            third.turnOff()
        }

    @Test
    fun stoppingFreesThePortAndKeepsTheHubOnForTheNextStart() =
        runBlocking {
            val first = controls()
            assertNull(first.turnOn(first.settings))
            waitFor { first.state.value is HubState.Running }
            val port = (first.state.value as HubState.Running).address.substringAfterLast(':').toInt()

            first.stop()

            // The port is free: something else can listen on it at once.
            java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).close()
            assertTrue(first.settings.on)
            val next = controls()
            assertTrue(next.settings.on)
            next.resume()
            waitFor { next.state.value is HubState.Running }
            next.turnOff()
        }

    @Test
    fun anOpenConfigFolderOfItsOwnIsTightenedButNotItsParent() =
        runBlocking {
            val open = PosixFilePermissions.fromString("rwxr-xr-x")
            val parent = folders.resolve("xdg")
            val own = parent.resolve("jotter")
            val mode = { path: Path ->
                PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
            }
            val loosen = {
                Files.setPosixFilePermissions(parent, open)
                Files.setPosixFilePermissions(own, open)
            }
            Files.createDirectories(own)
            val controls =
                PeerHubControls(
                    device.manager,
                    own.resolve("hub.properties"),
                    own.resolve("hub-devices.json"),
                    scope,
                    Dispatchers.IO,
                    HubSettings("$folders/store", "127.0.0.1", 0),
                )
            // Writing the paired devices.
            loosen()
            val pairing = controls.pair("Phone")
            assertEquals("rwx------", mode(own))
            assertEquals("rwxr-xr-x", mode(parent))
            // Removing one writes them again.
            loosen()
            controls.unpair(pairing.device.id)
            assertEquals("rwx------", mode(own))
            assertEquals("rwxr-xr-x", mode(parent))
            // Writing the settings.
            loosen()
            controls.turnOff()
            assertEquals("rwx------", mode(own))
            assertEquals("rwxr-xr-x", mode(parent))
        }

    @Test
    fun whatCannotBeUsedIsSaidPlainly() =
        runBlocking {
            val hub = controls()
            assertEquals(
                "Give the hub's folder as a full path, starting with /.",
                hub.turnOn(HubSettings("relative", "127.0.0.1", 0)),
            )
            val lan = hub.turnOn(HubSettings("$folders/store", "192.168.1.5", 0))!!
            assertTrue(lan.startsWith("The hub only listens on loopback"), lan)
            assertTrue(lan.contains("this PC's Tailscale address"), lan)
            folders.resolve("file").writeText("not a folder")
            val file = hub.turnOn(HubSettings("$folders/file", "127.0.0.1", 0))!!
            assertTrue(file.startsWith("Cannot set up $folders/file"), file)
            val inDatabase =
                hub.turnOn(
                    HubSettings(
                        Path
                            .of(device.file)
                            .parent
                            .resolve("hub")
                            .toString(),
                        "127.0.0.1",
                        0,
                    ),
                )!!
            assertTrue(inDatabase.contains("notes database"), inDatabase)
            assertIs<HubState.Off>(hub.state.value)
            assertFailsWith<IllegalStateException> { hub.pair("bad\nname") }

            val broken = controls(HubSettings("$folders/store", "not an address", 0))
            assertEquals(emptyList(), broken.devices())
            broken.unpair("x")
            broken.resume()
            assertEquals(
                "Turn the hub on first.",
                assertFailsWith<IllegalStateException> {
                    broken.pair("x")
                }.message,
            )
        }

    @Test
    fun aTakenPortIsShown() =
        runBlocking {
            val first = controls()
            assertNull(first.turnOn(first.settings))
            val port =
                (first.state.value as? HubState.Running)?.address?.substringAfter(':')
                    ?: run {
                        waitFor { first.state.value is HubState.Running }
                        (first.state.value as HubState.Running).address.substringAfter(':')
                    }
            val other =
                PeerHubControls(
                    device.manager,
                    folders.resolve("other/hub.properties"),
                    folders.resolve("other/hub-devices.json"),
                    scope,
                    Dispatchers.IO,
                    HubSettings("$folders/store", "127.0.0.1", port.toInt()),
                )
            val taken = other.turnOn(other.settings)!!
            assertTrue(taken.startsWith("The hub cannot start on 127.0.0.1:$port"), taken)
            waitFor { other.state.value is HubState.Failed }
            first.turnOff()
        }

    private companion object {
        const val POLL_MS = 20L
    }
}
