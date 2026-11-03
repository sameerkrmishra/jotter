package io.github.sameerkrmishra.jotter.app.sync

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The keyring store. A stand-in for `secret-tool` records how it was called, to show that the
 * secret never is in an argument or the environment. The real `secret-tool` runs only against a
 * private session bus that this test starts, or none: never the user's own. No keyring daemon is
 * started on it, so the real tool is shown to fail closed, and storing is shown with the stand-in.
 */
@OptIn(ExperimentalPathApi::class)
class SecretServiceStoreTest {
    private val directory = createTempDirectory("jotter-secret-tool")
    private val tool = directory.resolve("secret-tool")
    private val key = SecretKey("b-1", SecretKind.APP_PASSWORD)
    private val password = "hunter2-NOT-IN-ARGV"

    init {
        tool.writeText(FAKE_TOOL)
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwx------"))
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun store(bus: String? = "unix:path=/nonexistent/test-bus") =
        SecretServiceStore(bus, EmptyCoroutineContext, listOf(tool.toString()))

    @Test
    fun theSecretGoesThroughStdinAndComesBackOnStdout() =
        runTest {
            val store = store()
            assertNull(store.read(key))
            store.write(key, Secret(password))
            assertEquals(password, store.read(key)?.reveal())
            store.write(key, Secret("second"))
            assertEquals("second", store.read(key)?.reveal())
            store.delete(key)
            assertNull(store.read(key))
            store.delete(key)

            val calls = directory.resolve("calls.log").readText()
            assertFalse(password in calls, calls)
            assertFalse("second" in calls, calls)
            assertTrue(
                calls.contains(
                    "store --label=Jotter: app password of backend b-1 application jotter " +
                        "backend b-1 kind APP_PASSWORD",
                ),
                calls,
            )
            // Only the given bus, never whatever this process has.
            assertEquals(setOf("unix:path=/nonexistent/test-bus"), buses())
        }

    @Test
    fun aSecretToolThatGivesNothingBackReadsAsLockedOrMissing() =
        runTest {
            // Exit 1 without a word: no such item, or an unlock prompt the user dismissed.
            val factory = StandardBackendFactory(SecretStores(store(), NoSecretStore))
            val backend =
                Backend(
                    "b-1",
                    "dav",
                    WebDavConfig("https://dav.example.org/", "me"),
                    kotlin.time.Instant.fromEpochMilliseconds(0),
                    null,
                    null,
                )
            val failure = assertFailsWith<MissingSecretException> { factory.open(backend) }
            assertTrue(failure.message!!.contains("the keyring may be locked"), failure.message)
            assertTrue(failure.message!!.contains("Unlock your keyring"), failure.message)
            // Nothing was written or deleted because of it.
            assertTrue(
                directory.resolve("calls.log").readText().lines().none {
                    it.startsWith("store") ||
                        it.startsWith("clear")
                },
            )
        }

    @Test
    fun aSilentClearOfNothingIsConfirmedBySearching() =
        runTest {
            // Nothing to delete: clear ends with 1 silently, and an empty search confirms it.
            store().delete(key)
            assertTrue(
                calls().contains("search --all application jotter backend b-1 kind APP_PASSWORD"),
                calls().toString(),
            )
            assertFalse(calls().any { it.contains("--unlock") }, calls().toString())
        }

    @Test
    fun aLockedSecretIsNotTakenForDeleted() =
        runTest {
            val store = store()
            store.write(key, Secret(password))
            // A locked keyring, or a dismissed unlock prompt: clear and lookup both end with 1
            // without a word, and only the search, which does not unlock, still lists the item.
            directory.resolve("locked").writeText("")
            assertNull(store.read(key))
            assertMayBeLocked(assertFailsWith<SecretStoreException> { store.delete(key) })

            directory.resolve("locked").toFile().delete()
            assertEquals(password, store.read(key)?.reveal())
            store.delete(key)
            assertNull(store.read(key))
        }

    @Test
    fun aSilentClearOfAListedItemIsNotTakenForDeleted() =
        runTest {
            val store = store()
            store.write(key, Secret(password))
            // Unlocked, yet clear deleted nothing: the search lists it, secret and all.
            directory.resolve("keep").writeText("")
            val failure = assertFailsWith<SecretStoreException> { store.delete(key) }
            assertMayBeLocked(failure)
            assertEquals(password, store.read(key)?.reveal())
        }

    @Test
    fun aSearchThatFailsOrSaysAnythingIsNotTakenForDeleted() =
        runTest {
            val store = store()
            directory.resolve("searchfail").writeText("secret-tool: Cannot search: oops")
            assertMayBeLocked(assertFailsWith<SecretStoreException> { store.delete(key) })

            directory.resolve("searchfail").toFile().delete()
            directory.resolve("searchodd").writeText("something unexpected")
            assertMayBeLocked(assertFailsWith<SecretStoreException> { store.delete(key) })

            directory.resolve("searchodd").toFile().delete()
            store.delete(key)
        }

    private fun assertMayBeLocked(failure: SecretStoreException) {
        val message = failure.message!!
        assertFalse(failure is SecretStoreUnavailableException)
        assertTrue(message.contains("the keyring may be locked"), message)
        assertTrue(message.contains("Unlock your keyring"), message)
        assertTrue(message.contains("\"Jotter: app password of backend b-1\""), message)
        assertFalse(password in message, message)
    }

    private fun calls() = directory.resolve("calls.log").readText().lines()

    @Test
    fun withoutASessionBusItFailsClosed() =
        runTest {
            directory.resolve("fail").writeText("Unknown or unsupported transport")
            val failure = assertFailsWith<SecretStoreUnavailableException> { store(null).read(key) }
            assertTrue(failure.message!!.contains("no desktop session"), failure.message)
            assertEquals(setOf(NO_BUS), buses())
        }

    @Test
    fun itSaysPlainlyWhatIsMissing() =
        runTest {
            val missing =
                SecretServiceStore(
                    "unix:path=/x",
                    EmptyCoroutineContext,
                    listOf("/nonexistent/secret-tool"),
                )
            val notInstalled =
                assertFailsWith<SecretStoreUnavailableException> { missing.write(key, Secret("x")) }
            assertTrue(notInstalled.message!!.contains("libsecret-tools"), notInstalled.message)

            val fail = directory.resolve("fail")
            fail.writeText(
                "secret-tool: The name org.freedesktop.secrets was not provided by any " +
                    ".service files",
            )
            val noKeyring = assertFailsWith<SecretStoreUnavailableException> { store().read(key) }
            assertTrue(noKeyring.message!!.contains("GNOME Keyring"), noKeyring.message)

            fail.writeText("secret-tool: Could not connect: No such file or directory")
            val noBus =
                assertFailsWith<SecretStoreUnavailableException> {
                    store().write(key, Secret(password))
                }
            assertTrue(noBus.message!!.contains("does not answer"), noBus.message)
            assertFalse(password in noBus.message!!)

            fail.writeText("secret-tool: Cannot create an item in a locked collection")
            val locked = assertFailsWith<SecretStoreException> { store().delete(key) }
            assertIs<SecretStoreException>(locked)
            assertFalse(locked is SecretStoreUnavailableException)
            assertEquals(
                "The keyring could not delete the password: Cannot create an item in a locked " +
                    "collection.",
                locked.message,
            )
            fail.writeText(" ")
            Files.writeString(directory.resolve("exit"), "3")
            val silent = assertFailsWith<SecretStoreException> { store().read(key) }
            assertTrue(silent.message!!.contains("no reason given"), silent.message)
        }

    @Test
    fun aKeyringThatNeverAnswersTimesOut() =
        runTest {
            directory.resolve("hang").writeText("")
            val slow =
                SecretServiceStore(
                    "unix:path=/x",
                    EmptyCoroutineContext,
                    listOf(tool.toString()),
                    300.milliseconds,
                )
            val failure = assertFailsWith<SecretStoreException> { slow.read(key) }
            assertTrue(failure.message!!.contains("did not answer"), failure.message)
        }

    /**
     * The real `secret-tool`, on a session bus of this test's own with no keyring on it, and with
     * no bus at all. Skipped where `dbus-daemon` or `secret-tool` is not installed.
     */
    @Test
    fun theRealToolFailsClosedWithoutAKeyring() =
        runTest {
            if (!installed("dbus-daemon") || !installed("secret-tool")) return@runTest
            val noBus = SecretServiceStore(null, EmptyCoroutineContext)
            val closed = assertFailsWith<SecretStoreUnavailableException> { noBus.read(key) }
            assertTrue(closed.message!!.contains("no desktop session"), closed.message)

            PrivateBus().use { bus ->
                val store = SecretServiceStore(bus.address, EmptyCoroutineContext)
                val failure =
                    assertFailsWith<SecretStoreUnavailableException> {
                        store.write(key, Secret(password))
                    }
                assertTrue(failure.message!!.contains("no keyring program"), failure.message)
                assertFailsWith<SecretStoreUnavailableException> { store.read(key) }
            }
        }

    private fun buses() =
        directory
            .resolve("bus.log")
            .readText()
            .lines()
            .filter { it.isNotEmpty() }
            .toSet()

    private fun installed(name: String) =
        System.getenv("PATH").orEmpty().split(':').any {
            it.isNotEmpty() && Files.isExecutable(Path.of(it, name))
        }

    /** A `dbus-daemon --session` of this test, with no services, on a socket in a new folder. */
    private class PrivateBus : AutoCloseable {
        // A short path: a socket's name is limited to about 100 bytes.
        private val socketDirectory = Files.createTempDirectory(Path.of("/tmp"), "jbus")
        private val config = socketDirectory.resolve("bus.conf")
        private val process: Process
        val address: String

        init {
            config.writeText(
                """
                <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
                 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
                <busconfig>
                  <type>session</type>
                  <listen>unix:dir=$socketDirectory</listen>
                  <policy context="default">
                    <allow send_destination="*" eavesdrop="true"/>
                    <allow eavesdrop="true"/>
                    <allow own="*"/>
                  </policy>
                </busconfig>
                """.trimIndent(),
            )
            process =
                ProcessBuilder(
                    "dbus-daemon",
                    "--config-file=$config",
                    "--nofork",
                    "--print-address=1",
                ).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            address = process.inputStream.bufferedReader().readLine()
        }

        override fun close() {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            socketDirectory.toFile().deleteRecursively()
        }
    }

    private companion object {
        /**
         * Stands in for `secret-tool`: keeps each item in a file next to itself, and logs its
         * arguments and its bus. A file `fail` makes it print that and exit with 1 (or what `exit`
         * says), and `hang` makes it wait.
         *
         * Like libsecret's: `clear` deletes only unlocked items and ends with 1 without a word when
         * it deleted none; `search --all` lists locked items too, never prompting, with the label
         * on stdout and the attributes on stderr, and ends with 0 also when nothing matches. A file
         * `locked` locks every item, as a locked keyring or a dismissed unlock prompt leaves it:
         * `lookup` and `clear` then end with 1 without a word. `keep` makes `clear` delete nothing
         * all the same. `searchfail` makes `search` print it and end with 1; `searchodd` makes it
         * print that on stderr and end with 0.
         */
        val FAKE_TOOL =
            """
            #!/bin/sh
            dir=${'$'}(dirname "${'$'}0")
            echo "${'$'}*" >> "${'$'}dir/calls.log"
            echo "${'$'}DBUS_SESSION_BUS_ADDRESS" >> "${'$'}dir/bus.log"
            if [ -f "${'$'}dir/hang" ]; then exec sleep 30; fi
            if [ -f "${'$'}dir/fail" ]; then
                cat "${'$'}dir/fail" >&2
                if [ -f "${'$'}dir/exit" ]; then exit "${'$'}(cat "${'$'}dir/exit")"; fi
                exit 1
            fi
            op=${'$'}1
            shift
            if [ "${'$'}op" = store ] || [ "${'$'}op" = search ]; then shift; fi
            item="${'$'}dir/item-${'$'}(echo "${'$'}*" | tr ' /' '__')"
            locked=no
            if [ -f "${'$'}dir/locked" ]; then locked=yes; fi
            case "${'$'}op" in
                store) cat > "${'$'}item" ;;
                lookup)
                    [ -f "${'$'}item" ] && [ ${'$'}locked = no ] || exit 1
                    cat "${'$'}item" ;;
                clear)
                    [ -f "${'$'}item" ] && [ ${'$'}locked = no ] || exit 1
                    if [ -f "${'$'}dir/keep" ]; then exit 1; fi
                    rm "${'$'}item" ;;
                search)
                    if [ -f "${'$'}dir/searchfail" ]; then cat "${'$'}dir/searchfail" >&2; exit 1; fi
                    if [ -f "${'$'}dir/searchodd" ]; then cat "${'$'}dir/searchodd" >&2; fi
                    if [ -f "${'$'}item" ]; then
                        echo "[1]"
                        echo "label = Jotter: app password of backend b-1"
                        if [ ${'$'}locked = no ]; then printf 'secret = '; cat "${'$'}item"; echo; fi
                        echo "attribute.application = jotter" >&2
                    fi ;;
            esac
            """.trimIndent() + "\n"
    }
}
