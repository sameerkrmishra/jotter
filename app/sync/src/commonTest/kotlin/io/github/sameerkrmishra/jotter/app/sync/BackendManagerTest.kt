package io.github.sameerkrmishra.jotter.app.sync

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.core.storage.BackendRecord
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Adding, removing and syncing backends, with real databases and real folders. */
class BackendManagerTest {
    private val files = FileSystem.SYSTEM

    private fun test(block: suspend TempDir.() -> Unit) = runTest { TempDir().use { it.block() } }

    @Test
    fun aFolderThatIsNotAStoreIsSetUpOnlyWhenTheUserSaysSo() =
        test {
            val folder = folder("stick")
            Device(this, "a").use { device ->
                val asked = device.manager.addLocalFolder(folder.toString())
                assertEquals(AddResult.NotAStoreYet(folder.toString()), asked)
                assertEquals(emptyList(), device.manager.list())
                assertFalse(files.exists(folder / ".jotter-store"))

                val added = device.manager.addLocalFolder(folder.toString(), setUpNewStore = true)
                val backend = assertIs<AddResult.Added>(added).backend
                assertEquals("stick", backend.name)
                assertEquals(LocalFolderConfig(folder.toString()), backend.config)
                assertEquals(Instant.fromEpochMilliseconds(device.clock.millis), backend.addedAt)
                assertNull(backend.lastAttempt)
                assertTrue(files.exists(folder / ".jotter-store"))
                assertEquals(listOf(backend), device.manager.list())
            }
        }

    @Test
    fun aFolderAnotherDeviceSetUpIsAddedWithoutAsking() =
        test {
            val folder = folder("shared")
            Device(
                this,
                "a",
            ).use { it.manager.addLocalFolder(folder.toString(), setUpNewStore = true) }
            Device(this, "b").use { device ->
                val added = device.manager.addLocalFolder("$folder/./", name = "Shared")
                assertEquals("Shared", assertIs<AddResult.Added>(added).backend.name)
            }
        }

    @Test
    fun aFolderIsAddedOnce() =
        test {
            val folder = folder("stick")
            Device(this, "a").use { device ->
                val first = device.manager.addLocalFolder(folder.toString(), setUpNewStore = true)
                val again = device.manager.addLocalFolder(folder.toString(), setUpNewStore = true)
                assertEquals(
                    AddResult.AlreadyAdded(assertIs<AddResult.Added>(first).backend),
                    again,
                )
            }
        }

    @Test
    fun aMissingOrRelativeFolderIsRefused() =
        test {
            Device(this, "a").use { device ->
                val missing = device.manager.addLocalFolder("$path/gone", setUpNewStore = true)
                assertEquals(AddResult.Refused("There is no folder at $path/gone."), missing)
                val relative = device.manager.addLocalFolder("stick", setUpNewStore = true)
                assertTrue(assertIs<AddResult.Refused>(relative).reason.contains("full path"))
                val root = device.manager.addLocalFolder("/", setUpNewStore = false)
                assertIs<AddResult.NotAStoreYet>(root)
                assertEquals(emptyList(), device.manager.list())
            }
        }

    @Test
    fun aFileIsNotAFolder() =
        test {
            val file = path / "file"
            files.write(file) { writeUtf8("x") }
            Device(this, "a").use { device ->
                val result = device.manager.addLocalFolder(file.toString(), setUpNewStore = true)
                assertIs<AddResult.Refused>(result)
            }
        }

    @Test
    fun twoDevicesConvergeThroughAFolder() =
        test {
            val folder = folder("stick")
            Device(this, "a").use { a ->
                Device(this, "b").use { b ->
                    a.manager.addLocalFolder(folder.toString(), setUpNewStore = true)
                    b.manager.addLocalFolder(folder.toString())
                    val note = a.store.notes.create("Shopping", "milk")
                    a.clock.millis += 1_000
                    val synced = a.manager.syncAll().single()
                    assertEquals(Outcome.SYNCED, synced.attempt.outcome)
                    assertEquals("Synced. Received 0 changes, sent 1 file.", synced.attempt.detail)
                    val received = b.manager.syncAll().single()
                    assertEquals(
                        "Synced. Received 1 change, sent 0 files.",
                        received.attempt.detail,
                    )
                    assertEquals(
                        "milk",
                        b.store.notes
                            .get(note.id)
                            ?.body,
                    )

                    val stored = a.manager.list().single()
                    assertEquals(synced.attempt, stored.lastAttempt)
                    assertEquals(synced.attempt.at, stored.lastSuccessAt)
                    assertEquals(
                        "Synced. Nothing new.",
                        a.manager
                            .syncAll()
                            .single()
                            .attempt.detail,
                    )
                }
            }
        }

    @Test
    fun anUnpluggedFolderIsUnreachableAndKeepsItsLastSuccess() =
        test {
            val folder = folder("stick")
            Device(this, "a").use { device ->
                device.manager.addLocalFolder(folder.toString(), setUpNewStore = true)
                val first =
                    device.manager
                        .syncAll()
                        .single()
                        .attempt
                files.deleteRecursively(folder)
                device.clock.millis += 60_000

                val failed =
                    device.manager
                        .syncAll()
                        .single()
                        .attempt
                assertEquals(Outcome.UNREACHABLE, failed.outcome)
                assertTrue(failed.detail.startsWith("Not found"), failed.detail)
                val backend = device.manager.list().single()
                assertEquals(failed, backend.lastAttempt)
                assertEquals(first.at, backend.lastSuccessAt)

                val status = device.manager.status().single()
                assertEquals(
                    UnreachableReason.MISSING,
                    (status.reachability as Reachability.Unreachable).reason,
                )
                assertTrue(status.reachabilityText.contains("plug it in"))
            }
        }

    @Test
    fun anEmptyMountPointIsNeverSetUpBySync() =
        test {
            val folder = folder("stick")
            Device(this, "a").use { device ->
                device.manager.addLocalFolder(folder.toString(), setUpNewStore = true)
                // The drive is unplugged, and its mount point stays as an empty directory.
                files.deleteRecursively(folder)
                files.createDirectories(folder)
                device.store.notes.create("", "milk")

                assertEquals(
                    Outcome.UNREACHABLE,
                    device.manager
                        .syncAll()
                        .single()
                        .attempt.outcome,
                )
                assertEquals(emptyList(), files.list(folder))
                assertTrue(
                    device.manager
                        .status()
                        .single()
                        .reachabilityText
                        .contains("not marked"),
                )
            }
        }

    @Test
    fun removingForgetsTheBackendButLeavesItsFiles() =
        test {
            val folder = folder("stick")
            val secrets = InMemorySecretStore()
            Device(this, "a", secrets = secrets).use { device ->
                device.store.notes.create("", "milk")
                val first = device.manager.addLocalFolder(folder.toString(), setUpNewStore = true)
                val backend = assertIs<AddResult.Added>(first).backend
                device.manager.syncAll()
                val other = SecretKey("other", SecretKind.APP_PASSWORD)
                secrets.write(other, Secret("kept"))
                val before = files.listRecursively(folder).toList()

                assertTrue(device.manager.remove(backend.id))
                assertFalse(device.manager.remove(backend.id))
                assertEquals(emptyList(), device.manager.list())
                assertEquals(before, files.listRecursively(folder).toList())
                assertEquals(mapOf(other to Secret("kept")), secrets.secrets)

                // Added again, it is a new backend, which gets the whole history again.
                val again = device.manager.addLocalFolder(folder.toString())
                assertIs<AddResult.Added>(again)
                assertEquals(
                    1,
                    device.manager
                        .syncAll()
                        .single()
                        .report
                        ?.pushedSegments,
                )
            }
        }

    @Test
    fun aBackendOfANewerVersionIsListedButNeverSynced() =
        test {
            Device(this, "a").use { device ->
                device.store.backends.add(record("hub", kind = "peer", settings = "{}"))
                val backend = device.manager.list().single()
                assertEquals(UnknownConfig("peer"), backend.config)
                val sync = device.manager.syncAll().single()
                assertEquals(Outcome.UNSUPPORTED, sync.attempt.outcome)
                assertNull(sync.report)
                val status = device.manager.status().single()
                assertEquals(
                    UnreachableReason.FAILED,
                    (status.reachability as Reachability.Unreachable).reason,
                )
                assertTrue(device.manager.remove(backend.id))
            }
        }

    @Test
    fun aFailureOnThisDeviceEndsTheSyncNotTheApp() =
        test {
            val broken = BackendFactory { throw SecretStoreException("The keyring is locked") }
            Device(this, "a", factory = broken).use { device ->
                device.store.backends.add(record("dav"))
                val sync = device.manager.syncAll().single()
                assertEquals(Outcome.FAILED, sync.attempt.outcome)
                assertTrue(sync.attempt.detail.contains("The keyring is locked"))
                assertEquals(
                    Outcome.FAILED,
                    device.manager
                        .list()
                        .single()
                        .lastAttempt
                        ?.outcome,
                )
                val status = device.manager.status().single()
                assertEquals(
                    Reachability.Unreachable(UnreachableReason.FAILED, "The keyring is locked"),
                    status.reachability,
                )
            }
        }

    @Test
    fun aBackendThatFailsPartWayIsInterrupted() =
        test {
            val blobs =
                object : BlobStore by InMemoryBlobStore() {
                    override suspend fun list(prefix: String) =
                        throw BlobStoreFailureException("disk error")
                }
            Device(this, "a", factory = NamedStores(mapOf("mem" to blobs))).use { device ->
                device.store.backends.add(record("mem"))
                val sync = device.manager.syncAll().single()
                assertEquals(Outcome.INTERRUPTED, sync.attempt.outcome)
                assertTrue(sync.attempt.detail.contains("disk error"), sync.attempt.detail)
                assertEquals(0, sync.report?.pushedSegments)
                assertNull(
                    device.manager
                        .list()
                        .single()
                        .lastSuccessAt,
                )
            }
        }

    @Test
    fun aBackendInANewerFormatIsIncompatible() =
        test {
            val blobs = InMemoryBlobStore()
            blobs.writeNew(
                "jotter/format.json",
                """{"format":99,"codec":"json+gzip"}""".encodeToByteArray(),
            )
            Device(this, "a", factory = NamedStores(mapOf("mem" to blobs))).use { device ->
                device.store.backends.add(record("mem"))
                val attempt =
                    device.manager
                        .syncAll()
                        .single()
                        .attempt
                assertEquals(Outcome.INCOMPATIBLE, attempt.outcome)
                assertTrue(attempt.detail.contains("format 99"), attempt.detail)
            }
        }

    @Test
    fun anOutcomeOfANewerVersionReadsAsAFailure() =
        test {
            Device(this, "a").use { device ->
                device.store.backends.add(record("mem"))
                device.store.backends.recordAttempt(
                    "mem",
                    io.github.sameerkrmishra.jotter.core.storage.SyncAttempt(
                        Instant.fromEpochMilliseconds(1),
                        "SOMETHING_NEW",
                        "",
                        listOf(),
                    ),
                    succeeded = false,
                )
                assertEquals(
                    Outcome.FAILED,
                    device.manager
                        .list()
                        .single()
                        .lastAttempt
                        ?.outcome,
                )
            }
        }

    @Test
    fun syncingARemovedBackendDoesNothing() =
        test {
            Device(this, "a").use { device ->
                assertNull(device.manager.sync("gone"))
            }
        }

    @Test
    fun aDatabaseThatCannotRecordDoesNotChangeTheOutcome() =
        test {
            val factory = NamedStores(mapOf("mem" to InMemoryBlobStore()))
            Device(this, "a", factory = factory).use { device ->
                device.store.backends.add(record("mem"))
                BundledSQLiteDriver().open(device.file).use {
                    it.execSQL(
                        "CREATE TRIGGER fail BEFORE UPDATE ON backend " +
                            "BEGIN SELECT RAISE(ABORT, 'disk full'); END",
                    )
                }
                val sync = device.manager.sync("mem")
                assertEquals(Outcome.SYNCED, sync?.attempt?.outcome)
                assertNull(
                    device.manager
                        .list()
                        .single()
                        .lastAttempt,
                )
            }
        }

    @Test
    fun theFlowFollowsTheBackends() =
        runTest {
            TempDir().use { dir ->
                val folder = dir.folder("stick")
                Device(dir, "a").use { device ->
                    val seen = Channel<List<Backend>>(Channel.UNLIMITED)
                    backgroundScope.launch { device.manager.observe().collect(seen::send) }
                    assertEquals(emptyList(), seen.receive())
                    device.manager.addLocalFolder(folder.toString(), setUpNewStore = true)
                    assertEquals("stick", seen.receive().single().name)
                }
            }
        }

    private fun record(
        name: String,
        kind: String = "local-folder",
        settings: String = """{"path":"/nowhere/$name"}""",
    ) = BackendRecord(name, kind, name, settings, Instant.fromEpochMilliseconds(0))
}
