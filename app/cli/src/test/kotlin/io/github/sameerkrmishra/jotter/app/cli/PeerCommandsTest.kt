package io.github.sameerkrmishra.jotter.app.cli

import io.github.sameerkrmishra.jotter.app.sync.Secret
import io.github.sameerkrmishra.jotter.app.sync.SecretKey
import io.github.sameerkrmishra.jotter.app.sync.SecretKind
import io.github.sameerkrmishra.jotter.server.HubConfig
import io.github.sameerkrmishra.jotter.server.PeerHub
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `jotter backends add peer`, against a real PC hub on 127.0.0.1, with secrets in memory. */
@OptIn(ExperimentalPathApi::class)
class PeerCommandsTest {
    private val directory = createTempDirectory("jotter-cli-peer")
    private val here = directory.resolve("here/notes.db")
    private val there = directory.resolve("there/notes.db")
    private val hub =
        PeerHub(
            HubConfig(
                storeRoot = directory.resolve("hub").toOkioPath(),
                tokenFile = directory.resolve("hub-devices.json").toOkioPath(),
                host = "127.0.0.1",
                port = 0,
            ),
        )
    private val address =
        runBlocking {
            hub.setUpStore()
            "127.0.0.1:${hub.start().port}"
        }
    private val secretsHere = TestSecrets()
    private val secretsThere = TestSecrets()

    @AfterTest
    fun cleanUp() {
        runBlocking { hub.stop() }
        directory.deleteRecursively()
    }

    private fun jotter(
        database: Path,
        vararg args: String,
        input: String = "",
    ) = runJotter(
        args.toList(),
        database,
        ByteArrayInputStream(input.toByteArray()),
        if (database == here) secretsHere.stores else secretsThere.stores,
    )

    private fun ok(
        database: Path,
        vararg args: String,
        input: String = "",
    ): String {
        val result = jotter(database, *args, input = input)
        assertEquals(0, result.statusCode, result.stderr)
        return result.stdout
    }

    @Test
    fun twoDatabasesSyncThroughTheHub() {
        val laptop = runBlocking { hub.pair("laptop") }
        val phone = runBlocking { hub.pair("phone") }
        val id = ok(here, "backends", "add", "peer", address, input = laptop.token + "\n").trim()
        assertEquals(
            Secret(laptop.token),
            secretsHere.keyring.secrets[SecretKey(id, SecretKind.PAIRING_SECRET)],
        )
        val other =
            ok(
                there,
                "backends",
                "add",
                "--name",
                "Desk",
                "peer",
                address,
                input = phone.token,
            ).trim()
        val note = ok(here, "add", "buy milk").trim()
        assertTrue(ok(here, "sync").contains("\tPC hub\tsynced\t"))
        assertTrue(ok(there, "sync").contains("Received 1 change"))
        assertTrue(ok(there, "show", note).endsWith("\nbuy milk\n"))
        assertTrue(
            ok(
                here,
                "backends",
                "status",
            ).contains("secret: Its pairing token is kept in the keyring."),
        )

        runBlocking { hub.unpair(phone.device.id) }
        val refused = jotter(there, "sync")
        assertEquals(1, refused.statusCode)
        assertTrue(
            refused.stdout.contains(
                "\tunauthorized\tThe PC hub refused this device's pairing token",
            ),
            refused.stdout,
        )
        val again = runBlocking { hub.pair("phone") }
        assertEquals(
            "Stored the new pairing token of Desk.\n",
            ok(there, "backends", "password", other, input = again.token),
        )
        assertTrue(ok(there, "sync").contains("\tsynced\t"))
    }

    @Test
    fun aHubOutsideTheTailnetOrAWrongTokenIsRefused() {
        val token = runBlocking { hub.pair("laptop") }.token
        val lan = jotter(here, "backends", "add", "peer", "192.168.1.20", input = token)
        assertTrue(
            lan.stderr.startsWith("jotter: 192.168.1.20 is not on your tailnet or this machine."),
            lan.stderr,
        )
        val wrong = jotter(here, "backends", "add", "peer", address, input = "0".repeat(32) + ".x")
        assertTrue(wrong.stderr.contains("The hub refused this pairing token"), wrong.stderr)
        val usage = jotter(here, "backends", "add", "peer")
        assertTrue(usage.stderr.contains("peer needs the hub's address"), usage.stderr)
        val newFlag = jotter(here, "backends", "add", "--new", "peer", address, input = token)
        assertTrue(newFlag.stderr.contains("the hub's folder is set up on the PC"), newFlag.stderr)
        assertEquals("", ok(here, "backends"))
    }
}
