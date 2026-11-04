package io.github.sameerkrmishra.jotter.core.storage

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class BackendStoreTest {
    private val stick =
        BackendRecord(
            id = "b1",
            kind = "local-folder",
            name = "USB stick",
            settings = """{"path":"/media/stick"}""",
            addedAt = Instant.fromEpochMilliseconds(5_000),
        )

    @Test
    fun aNewBackendIsListedWithoutAnyAttempt() =
        withStore { store ->
            store.backends.add(stick.copy(lastSuccessAt = Instant.fromEpochMilliseconds(1)))
            assertEquals(listOf(stick), store.backends.list())
            assertEquals(stick, store.backends.get("b1"))
            assertNull(store.backends.get("b2"))
        }

    @Test
    fun backendsAreListedOldestFirstAndSurviveReopening() =
        withTempDir { dir ->
            val later = stick.copy(id = "a0", addedAt = Instant.fromEpochMilliseconds(9_000))
            openStore(dir.path).use { store ->
                store.backends.add(later)
                store.backends.add(stick)
            }
            openStore(dir.path).use { store ->
                assertEquals(dir.path, store.path)
                assertEquals(listOf(stick, later), store.backends.list())
            }
        }

    @Test
    fun anIdCanBeAddedOnlyOnce() =
        withStore { store ->
            store.backends.add(stick)
            assertFailsWith<IllegalArgumentException> { store.backends.add(stick.copy(name = "x")) }
            assertEquals(listOf(stick), store.backends.list())
        }

    @Test
    fun removingSaysWhetherTheBackendWasThere() =
        withStore { store ->
            store.backends.add(stick)
            assertTrue(store.backends.remove("b1"))
            assertFalse(store.backends.remove("b1"))
            assertEquals(emptyList(), store.backends.list())
        }

    @Test
    fun settingsCanBeReplacedKeepingTheRest() =
        withStore { store ->
            store.backends.add(stick)
            val ok = SyncAttempt(Instant.fromEpochMilliseconds(10_000), "COMPLETED", "", listOf())
            store.backends.recordAttempt("b1", ok, succeeded = true)
            val before = store.backends.get("b1")!!
            assertTrue(store.backends.updateSettings("b1", """{"path":"/media/other"}"""))
            assertEquals(
                before.copy(settings = """{"path":"/media/other"}"""),
                store.backends.get("b1"),
            )
            assertFalse(store.backends.updateSettings("b2", "{}"))
            assertNull(store.backends.get("b2"))
        }

    @Test
    fun aFailedAttemptKeepsTheLastSuccess() =
        withStore { store ->
            store.backends.add(stick)
            val ok =
                SyncAttempt(Instant.fromEpochMilliseconds(10_000), "COMPLETED", "Synced", listOf())
            store.backends.recordAttempt("b1", ok, succeeded = true)
            assertEquals(ok, store.backends.get("b1")?.lastAttempt)

            val failed =
                SyncAttempt(
                    Instant.fromEpochMilliseconds(20_000),
                    "UNREACHABLE",
                    "Not found",
                    listOf("first\nline", "second"),
                )
            store.backends.recordAttempt("b1", failed, succeeded = false)
            val found = store.backends.get("b1")
            assertEquals(failed.copy(issues = listOf("first line", "second")), found?.lastAttempt)
            assertEquals(Instant.fromEpochMilliseconds(10_000), found?.lastSuccessAt)
        }

    @Test
    fun recordingForARemovedBackendDoesNothing() =
        withStore { store ->
            val attempt = SyncAttempt(Instant.fromEpochMilliseconds(1), "COMPLETED", "", listOf())
            store.backends.recordAttempt("gone", attempt, succeeded = true)
            assertEquals(emptyList(), store.backends.list())
        }

    @Test
    fun theFlowFollowsChanges() =
        runTest {
            withStore(queryContext = StandardTestDispatcher(testScheduler)) { store ->
                val emissions = Channel<List<BackendRecord>>(Channel.UNLIMITED)
                backgroundScope.launch { store.backends.observe().collect(emissions::send) }
                assertEquals(emptyList(), emissions.receive())
                store.backends.add(stick)
                assertEquals(listOf(stick), emissions.receive())
                store.backends.remove("b1")
                assertEquals(emptyList(), emissions.receive())
            }
        }

    @Test
    fun theRevisionMarkRisesWithEveryRevision() =
        runTest {
            withStore(queryContext = StandardTestDispatcher(testScheduler)) { store ->
                val marks = Channel<Long>(Channel.UNLIMITED)
                backgroundScope.launch { store.notes.observeRevisionMark().collect(marks::send) }
                assertEquals(0, marks.receive())
                val note = store.notes.create("", "milk")
                val first = marks.receive()
                store.notes.edit(note.id, note.revisionId, "", "oat milk")
                assertTrue(marks.receive() > first)
            }
        }
}
