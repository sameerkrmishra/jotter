package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.peer.PeerAddress
import io.github.sameerkrmishra.jotter.backend.peer.PeerBlobStore
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The hub's lifecycle, pairing and configuration: the API that the app hosting it uses. */
class PeerHubTest {
    private val hubs = TestHubs()

    @AfterTest
    fun stopHubs() = kotlinx.coroutines.runBlocking { hubs.close() }

    private fun test(body: suspend () -> Unit): TestResult = runTest(timeout = 1.minutes) { body() }

    private fun config(port: Int = 0): HubConfig {
        val root = hubs.newPath()
        return HubConfig(root / "store", root / "devices.json", port = port)
    }

    @Test
    fun theDevicesFileIsThisUsersAloneFromTheStart(): TestResult =
        test {
            val root = hubs.newPath()
            val tokens = root / "config" / "devices.json"
            val hub = PeerHub(HubConfig(root / "store", tokens))
            hub.pair("Phone")

            fun mode(path: okio.Path) =
                java.nio.file.attribute.PosixFilePermissions.toString(
                    java.nio.file.Files
                        .getPosixFilePermissions(path.toNioPath()),
                )
            assertEquals("rw-------", mode(tokens))
            assertEquals("rwx------", mode(tokens.parent!!))
            hub.pair("Laptop")
            assertEquals("rw-------", mode(tokens))
            assertEquals(
                listOf("devices.json"),
                FileSystem.SYSTEM.list(tokens.parent!!).map { it.name },
            )
        }

    @Test
    fun startStopAndStatus(): TestResult =
        test {
            val hub = PeerHub(config())
            assertEquals(HubStatus.Stopped, hub.status.value)
            val running = hub.start()
            assertEquals(running, hub.status.value)
            assertEquals(LOOPBACK, running.host)
            assertSame(running, hub.start(), "starting a running hub changes nothing")
            hub.stop()
            assertEquals(HubStatus.Stopped, hub.status.value)
            hub.stop()
            assertIs<HubStatus.Running>(hub.start())
            hub.stop()
        }

    @Test
    fun aTakenPortFailsTheStart(): TestResult =
        test {
            val first = hubs.start()
            val hub = PeerHub(config(port = first.port))
            val failure = assertFailsWith<HubStartException> { hub.start() }
            assertEquals(HubStatus.Failed(failure.message!!), hub.status.value)
            assertContains(failure.message!!, "${first.port}")
        }

    @Test
    fun aHubWithoutItsStoreSaysSo(): TestResult =
        test {
            val hub = hubs.start(setUp = false)
            val ping = hubs.client(hub).ping()
            assertEquals(UnreachableReason.MISSING, (ping as Reachability.Unreachable).reason)
            FileSystem.SYSTEM.createDirectories(hub.storeRoot)
            val notSetUp = hubs.client(hub).ping() as Reachability.Unreachable
            assertEquals(UnreachableReason.NOT_INITIALIZED, notSetUp.reason)
            hub.hub.setUpStore()
            hub.hub.setUpStore() // Again is harmless.
            assertEquals(Reachability.Reachable, hubs.client(hub).ping())
        }

    @Test
    fun aStoreThatCannotBeCreatedFails(): TestResult =
        test {
            val root = hubs.newPath()
            FileSystem.SYSTEM.write(root) { writeUtf8("a file, not a folder") }
            val hub = PeerHub(HubConfig(root / "store", hubs.newPath() / "devices.json"))
            assertFailsWith<BlobStoreFailureException> { hub.setUpStore() }
        }

    @Test
    fun pairedDevicesSurviveARestartAndOnlyHashesAreStored(): TestResult =
        test {
            val config = config()
            val first = PeerHub(config)
            val phone = first.pair("Phone")
            val laptop = first.pair("Laptop")
            assertEquals(listOf(phone.device, laptop.device), first.pairedDevices())
            val stored = FileSystem.SYSTEM.read(config.tokenFile) { readUtf8() }
            assertFalse(phone.token.substringAfter('.') in stored)

            val second = PeerHub(config)
            assertEquals(listOf(phone.device, laptop.device), second.pairedDevices())
            assertTrue(second.unpair(phone.device.id))
            assertFalse(second.unpair(phone.device.id))
            second.setUpStore()
            val running = second.start()
            try {
                val address = PeerAddress(LOOPBACK, running.port)
                PeerBlobStore(
                    address,
                    laptop.token,
                ).use { assertEquals(Reachability.Reachable, it.ping()) }
                PeerBlobStore(
                    address,
                    phone.token,
                ).use { assertIs<Reachability.Unreachable>(it.ping()) }
            } finally {
                second.stop()
            }
            assertEquals(listOf(laptop.device), PeerHub(config).pairedDevices())
        }

    @Test
    fun tokensAreUniqueAndOfTheDocumentedForm(): TestResult =
        test {
            val hub = PeerHub(config())
            val tokens = List(20) { hub.pair("device $it").token }
            assertEquals(tokens.size, tokens.toSet().size)
            tokens.forEach { assertTrue(it.matches(Regex("[0-9a-f]{32}\\.[A-Za-z0-9_-]{43}")), it) }
        }

    @Test
    fun deviceNamesAreChecked(): TestResult =
        test {
            val hub = PeerHub(config())
            listOf("", " ", "a".repeat(65), "line\nbreak", "tab\there").forEach { name ->
                assertFailsWith<IllegalArgumentException>(name) { hub.pair(name) }
            }
            assertEquals("a".repeat(64), hub.pair("a".repeat(64)).device.name)
            assertEquals("Pixel 9 é", hub.pair("Pixel 9 é").device.name)
        }

    @Test
    fun aDamagedFileOfDevicesStopsTheHubFromStarting(): TestResult =
        test {
            val damaged =
                listOf(
                    "not json",
                    """{"version":2,"devices":[]}""",
                    """{"version":1,"devices":[{"id":"a","name":"b","secretSha256":"zz"}]}""",
                    """{"version":1}""",
                )
            damaged.forEach { text ->
                val config = config()
                FileSystem.SYSTEM.createDirectories(config.tokenFile.parent!!)
                FileSystem.SYSTEM.write(config.tokenFile) { writeUtf8(text) }
                val hub = PeerHub(config)
                assertFailsWith<HubStartException>(text) { hub.start() }
                assertIs<HubStatus.Failed>(hub.status.value)
                assertFailsWith<IOException>(text) { hub.pair("phone") }
                // Nothing was replaced: a damaged file is the user's to look at.
                assertEquals(text, FileSystem.SYSTEM.read(config.tokenFile) { readUtf8() })
            }
        }

    @Test
    fun onlyLoopbackAndTailscaleAddressesAreAllowed(): TestResult =
        test {
            listOf("127.0.0.1", "127.255.0.9", "100.64.0.1", "100.101.102.103", "100.127.255.255")
                .forEach { host -> assertTrue(isAllowedBindAddress(host), host) }
            listOf(
                "0.0.0.0",
                "192.168.1.10",
                "10.0.0.1",
                "100.63.255.255",
                "100.128.0.1",
                "localhost",
                "my-pc.tailnet.ts.net",
                "::1",
                "::",
                "",
                "127.0.0",
                "127.0.0.1.2",
                "127.0.0.01",
                "127.0.0.256",
                "127.0.0.-1",
                "127.0.0.1 ",
                "1270.0.0.1",
            ).forEach { host ->
                assertFalse(isAllowedBindAddress(host), host)
                assertFailsWith<IllegalArgumentException>(host) {
                    HubConfig(hubs.newPath(), hubs.newPath(), host = host)
                }
            }
        }

    @Test
    fun configurationIsChecked(): TestResult =
        test {
            assertFailsWith<IllegalArgumentException> {
                HubConfig(
                    hubs.newPath(),
                    hubs.newPath(),
                    port = -1,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                HubConfig(hubs.newPath(), hubs.newPath(), port = 65536)
            }
            assertFailsWith<IllegalArgumentException> { HubLimits(maxConnections = 0) }
            assertFailsWith<IllegalArgumentException> { HubLimits(maxTransfers = 0) }
            assertFailsWith<IllegalArgumentException> { HubLimits(maxConnectionsPerClient = 0) }
            val idle = PeerHub(config())
            assertEquals(0, idle.openConnections)
            idle.breakListener() // Nothing to break on a stopped hub.
            assertFailsWith<IllegalArgumentException> {
                HubLimits(
                    headerTimeout = kotlin.time.Duration.ZERO,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                HubLimits(
                    idleTimeout = kotlin.time.Duration.ZERO,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                HubLimits(
                    requestDeadline = kotlin.time.Duration.ZERO,
                )
            }
        }
}
