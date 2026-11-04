package io.github.sameerkrmishra.jotter.app.cli

import io.github.sameerkrmishra.jotter.app.sync.Secret
import io.github.sameerkrmishra.jotter.app.sync.SecretKey
import io.github.sameerkrmishra.jotter.app.sync.SecretKind
import io.github.sameerkrmishra.jotter.app.sync.SecretStore
import io.github.sameerkrmishra.jotter.app.sync.SecretStoreUnavailableException
import io.github.sameerkrmishra.jotter.app.sync.SecretStores
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `jotter backends add webdav` and what follows, against [TinyDavServer] in this process, with the
 * app password on stdin and secrets in memory.
 */
@OptIn(ExperimentalPathApi::class)
class WebDavCommandsTest {
    private val directory = createTempDirectory("jotter-cli-webdav")
    private val here = directory.resolve("here/notes.db")
    private val there = directory.resolve("there/notes.db")
    private val server = TinyDavServer()
    private val secretsHere = TestSecrets()
    private val secretsThere = TestSecrets()

    /** The keyring of a computer that has none: every call fails as when no bus answers. */
    private object NoKeyring : SecretStore {
        override suspend fun read(key: SecretKey): Secret? = throw unavailable()

        override suspend fun write(
            key: SecretKey,
            secret: Secret,
        ): Unit = throw unavailable()

        override suspend fun delete(key: SecretKey): Unit = throw unavailable()

        private fun unavailable() =
            SecretStoreUnavailableException("Jotter cannot reach a keyring: no bus.")
    }

    @AfterTest
    fun cleanUp() {
        server.close()
        directory.deleteRecursively()
    }

    private fun jotter(
        database: Path,
        vararg args: String,
        input: String = "",
        secrets: SecretStores = if (database == here) secretsHere.stores else secretsThere.stores,
    ) = runJotter(args.toList(), database, ByteArrayInputStream(input.toByteArray()), secrets)

    private fun ok(
        database: Path,
        vararg args: String,
        input: String = "",
    ): String {
        val result = jotter(database, *args, input = input)
        assertEquals(0, result.statusCode, result.stderr)
        return result.stdout
    }

    private fun add(
        database: Path,
        vararg more: String,
    ) = ok(
        database,
        "backends",
        "add",
        *more,
        "webdav",
        server.url,
        "--user",
        "me",
        input = "app-password\n",
    ).trim()

    @Test
    fun twoDatabasesSyncThroughAServer() {
        val asked =
            jotter(
                here,
                "backends",
                "add",
                "webdav",
                server.url,
                "--user",
                "me",
                input = "app-password",
            )
        assertEquals(1, asked.statusCode)
        assertTrue(asked.stderr.contains("is not a Jotter store yet"), asked.stderr)
        assertTrue(
            asked.stderr.contains("jotter backends add --new webdav ${server.url} --user me"),
            asked.stderr,
        )
        assertEquals("", ok(here, "backends"))

        val id = add(here, "--new", "--name", "Mailbox")
        assertEquals(
            Secret("app-password"),
            secretsHere.keyring.secrets[SecretKey(id, SecretKind.APP_PASSWORD)],
        )
        NoteStore.open(here.toString(), "x", EmptyCoroutineContext).use { store ->
            val settings =
                store.backends
                    .list()
                    .single()
                    .settings
            assertFalse(settings.contains("app-password"), settings)
        }
        add(there)
        val note = ok(here, "add", "buy milk").trim()
        assertTrue(ok(here, "sync").contains("\tMailbox\tsynced\t"))
        assertTrue(ok(there, "sync").contains("Received 1 change"))
        assertTrue(ok(there, "show", note).endsWith("\nbuy milk\n"))

        val status = ok(here, "backends", "status", id).lines()
        assertEquals("secret: Its password is kept in the keyring.", status[3])
        assertEquals("reachable: yes", status[4])
    }

    @Test
    fun aRefusedPasswordIsShownAndCanBeReplaced() {
        val wrong =
            jotter(
                here,
                "backends",
                "add",
                "--new",
                "webdav",
                server.url,
                "--user",
                "me",
                input = "nope\n",
            )
        assertEquals(1, wrong.statusCode)
        assertTrue(
            wrong.stderr.contains("The server refused the user name or app password"),
            wrong.stderr,
        )

        val id = add(here, "--new")
        server.password = "revoked-and-new"
        val failed = jotter(here, "sync")
        assertEquals(1, failed.statusCode)
        val line = failed.stdout.trim().split("\t")
        assertEquals("unauthorized", line[2])
        assertTrue(line[3].startsWith("The server refused the user name or app password."), line[3])
        assertTrue(ok(here, "backends", "status").contains("state: The server refused"))

        val stillWrong = jotter(here, "backends", "password", id, input = "app-password\n")
        assertEquals(
            "jotter: The server refused this app password too. Nothing was changed.\n",
            stillWrong.stderr,
        )
        val changed = ok(here, "backends", "password", id.take(6), input = "revoked-and-new\n")
        assertEquals("Stored the new app password of 127.0.0.1.\n", changed)
        assertTrue(ok(here, "sync").contains("\tsynced\t"))
    }

    @Test
    fun withoutAKeyringThePasswordMayGoInTheFile() {
        val noKeyring =
            object : SecretStore by MemorySecrets() {
                override suspend fun write(
                    key: SecretKey,
                    secret: Secret,
                ): Unit =
                    throw SecretStoreUnavailableException("Jotter cannot reach a keyring: no bus.")
            }
        val stores = SecretStores(noKeyring, secretsHere.file, "/config/jotter/secrets.json")

        fun run(vararg args: String) =
            jotter(here, *args, input = "app-password\n", secrets = stores)

        val refused = run("backends", "add", "--new", "webdav", server.url, "--user", "me")
        assertEquals(1, refused.statusCode)
        assertTrue(
            refused.stderr.startsWith(
                "jotter: Jotter cannot reach a keyring: no bus. Nothing was added.",
            ),
        )
        assertTrue(refused.stderr.contains("--password-in-file"), refused.stderr)

        val added =
            run(
                "backends",
                "add",
                "--new",
                "--password-in-file",
                "webdav",
                server.url,
                "--user",
                "me",
            )
        assertEquals(0, added.statusCode, added.stderr)
        val id = added.stdout.trim()
        assertEquals(1, secretsHere.file.secrets.size)
        val status = run("backends", "status").stdout
        assertTrue(
            status.contains(
                "secret: Its password is kept in plain text in /config/jotter/secrets.json",
            ),
            status,
        )
        assertEquals(0, run("backends", "remove", id).statusCode)
        assertTrue(secretsHere.file.secrets.isEmpty())
    }

    @Test
    fun aRestoredDatabaseWithoutAKeyringKeepsThePasswordInTheFileAndBack() {
        val id = add(here, "--new", "--name", "Mailbox")
        Files.createDirectories(there.parent)
        ok(here, "export-database", there.toString())
        // Made after the export, so the restored database gets it only by syncing.
        val note = ok(here, "add", "buy milk").trim()
        assertTrue(ok(here, "sync").contains("\tMailbox\tsynced\t"))

        // The other computer has no keyring at all.
        val elsewhere = SecretStores(NoKeyring, secretsThere.file, "/config/jotter/secrets.json")

        fun run(vararg args: String) =
            jotter(there, *args, input = "app-password\n", secrets = elsewhere)

        assertEquals(1, run("sync").statusCode)
        val refused = run("backends", "password", id)
        assertEquals(1, refused.statusCode)
        assertTrue(
            refused.stderr.startsWith(
                "jotter: Jotter cannot reach a keyring: no bus. Nothing was changed.",
            ),
            refused.stderr,
        )
        assertTrue(
            refused.stderr.contains("jotter backends password --store file $id"),
            refused.stderr,
        )
        assertTrue(secretsThere.file.secrets.isEmpty())

        val moved = run("backends", "password", "--store", "file", id)
        assertEquals(0, moved.statusCode, moved.stderr)
        val lines = moved.stdout.lines()
        assertEquals(
            "Stored the new app password of Mailbox in the secrets file, in plain text.",
            lines[0],
        )
        assertTrue(lines[1].startsWith("Jotter could not delete the old copy"), lines[1])
        assertTrue(
            lines[1].contains("secret-tool clear application jotter backend $id"),
            lines[1],
        )
        assertEquals(
            Secret("app-password"),
            secretsThere.file.secrets[SecretKey(id, SecretKind.APP_PASSWORD)],
        )
        val synced = run("sync")
        assertEquals(0, synced.statusCode, synced.stderr)
        assertTrue(run("show", note).stdout.endsWith("\nbuy milk\n"))
        assertTrue(
            run("backends", "status", id).stdout.contains(
                "secret: Its password is kept in plain text in /config/jotter/secrets.json",
            ),
        )
        movesBackToTheKeyring(id)
    }

    /** Where a keyring answers again, the password of [id] moves back, out of the file. */
    private fun movesBackToTheKeyring(id: String) {
        val back =
            jotter(
                there,
                "backends",
                "password",
                "--store",
                "keyring",
                id,
                input = "app-password\n",
            )
        assertEquals(0, back.statusCode, back.stderr)
        assertEquals("Stored the new app password of Mailbox in the keyring.\n", back.stdout)
        assertTrue(secretsThere.file.secrets.isEmpty())
        assertEquals(
            Secret("app-password"),
            secretsThere.keyring.secrets[SecretKey(id, SecretKind.APP_PASSWORD)],
        )
        assertTrue(ok(there, "sync").contains("\tMailbox\tsynced\t"))
        val wrong = jotter(there, "backends", "password", "--store", "disk", id)
        assertEquals(1, wrong.statusCode)
    }

    @Test
    fun aPasswordInTheFileStaysThereWithoutAKeyring() {
        val stores = SecretStores(NoKeyring, secretsHere.file, "/config/jotter/secrets.json")

        fun run(vararg args: String) =
            jotter(here, *args, input = "app-password\n", secrets = stores)

        val added =
            run(
                "backends",
                "add",
                "--new",
                "--password-in-file",
                "webdav",
                server.url,
                "--user",
                "me",
            )
        val id = added.stdout.trim()
        val key = SecretKey(id, SecretKind.APP_PASSWORD)

        val refused = run("backends", "password", "--store", "keyring", id)
        assertEquals(1, refused.statusCode)
        assertTrue(
            refused.stderr.startsWith(
                "jotter: Jotter cannot reach a keyring: no bus. Nothing was changed.",
            ),
            refused.stderr,
        )
        assertEquals(Secret("app-password"), secretsHere.file.secrets[key])

        // Its own place, named or not, is a plain change.
        for (args in listOf(arrayOf("--store", "file", id), arrayOf(id))) {
            val same = run("backends", "password", *args)
            assertEquals("Stored the new app password of 127.0.0.1.\n", same.stdout, same.stderr)
        }
        assertEquals(1, secretsHere.file.secrets.size)
        assertEquals(
            "jotter: no backend with id nope\n",
            run("backends", "password", "--store", "file", "nope").stderr,
        )
    }

    @Test
    fun removingAWebDavBackendDeletesItsPasswordOrNothing() {
        val id = add(here, "--new")
        val noDelete =
            object : SecretStore by secretsHere.keyring {
                override suspend fun delete(key: SecretKey): Unit =
                    throw SecretStoreUnavailableException("Jotter cannot reach a keyring: no bus.")
            }
        val stuck =
            jotter(
                here,
                "backends",
                "remove",
                id,
                secrets = SecretStores(noDelete, secretsHere.file),
            )
        assertEquals(
            "jotter: cannot delete the password of $id, so nothing was removed: Jotter cannot " +
                "reach a keyring: no bus. To remove it anyway and delete the password later " +
                "yourself, run: jotter backends remove --keep-secret $id\n",
            stuck.stderr,
        )
        assertTrue(ok(here, "backends").startsWith(id))
        assertTrue(ok(here, "backends", "remove", id).startsWith("Removed 127.0.0.1"))
        assertTrue(secretsHere.keyring.secrets.isEmpty())

        // With no keyring at all, --keep-secret removes it and says where the password stays.
        val other = add(here, "--name", "Other")
        val kept =
            jotter(
                here,
                "backends",
                "remove",
                "--keep-secret",
                other,
                secrets = SecretStores(noDelete, secretsHere.file),
            )
        assertEquals(0, kept.statusCode, kept.stderr)
        assertTrue(kept.stdout.startsWith("Removed Other ($other)."), kept.stdout)
        assertTrue(
            kept.stdout.contains("secret-tool clear application jotter backend $other"),
            kept.stdout,
        )
        assertEquals("", ok(here, "backends"))
        assertEquals(1, secretsHere.keyring.secrets.size)
    }

    @Test
    fun aServerThatReplacesFilesIsRefused() {
        server.ignoresIfNoneMatch = true
        val result =
            jotter(
                here,
                "backends",
                "add",
                "--new",
                "webdav",
                server.url,
                "--user",
                "me",
                input = "app-password",
            )
        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("the server replaces a file when it is written again"),
            result.stderr,
        )
        assertTrue(secretsHere.keyring.secrets.isEmpty())
        assertEquals("", ok(here, "backends"))
    }

    @Test
    fun whatIsWrongWithTheCommandIsSaid() {
        fun error(
            vararg args: String,
            input: String = "app-password\n",
        ): String {
            val result = jotter(here, *args, input = input)
            assertEquals(1, result.statusCode, result.stdout)
            return result.stderr
        }
        assertTrue(
            error("backends", "add", "webdav", "http://dav.example.org/x/", "--user", "me")
                .contains("Plain http would send the password"),
        )
        assertTrue(error("backends", "add", "webdav").contains("For a folder called webdav"))
        assertTrue(error("backends", "add", "/a", "/b").contains("give one folder, webdav and"))
        assertTrue(
            error("backends", "add", "--user", "me", "/a").contains("for webdav and peer only"),
        )
        assertTrue(error("backends", "add", "webdav", server.url).contains("needs --user NAME"))
        assertEquals(
            "jotter: no password was given\n",
            error("backends", "add", "webdav", server.url, "--user", "me", input = ""),
        )
        assertEquals(
            "jotter: no password was given\n",
            error("backends", "add", "webdav", server.url, "--user", "me", input = "\r\n"),
        )
        assertEquals("jotter: the path is empty\n", error("backends", "add", ""))
        val unreachable =
            error("backends", "add", "webdav", "http://127.0.0.1:9/dav/", "--user", "me")
        assertTrue(
            unreachable.startsWith("jotter: Cannot reach http://127.0.0.1:9/dav/"),
            unreachable,
        )

        val folder = directory.resolve("stick")
        java.nio.file.Files
            .createDirectories(folder)
        val id = ok(here, "backends", "add", "--new", folder.toString()).trim()
        assertEquals("jotter: $id needs no password\n", error("backends", "password", id))
    }
}
