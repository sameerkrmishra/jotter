// Test code in a main source set, so it can be shared: each test is its own documentation, tests
// pick their threads, and the sizes are the point of the test.
@file:Suppress("InjectDispatcher", "MagicNumber", "TooManyFunctions", "UndocumentedPublicFunction")

package io.github.sameerkrmishra.jotter.backend.api.contract

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.backend.api.InvalidBlobPathException
import io.github.sameerkrmishra.jotter.backend.api.RESERVED_SEGMENT_PREFIX
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/**
 * What every [BlobStore] must do. A backend's tests subclass this, in each target's test source
 * set they want it to run on, and implement the two factories. A backend is done when it passes.
 *
 * The tests use real concurrency on `Dispatchers.Default`, so that a store which only looks atomic
 * loses a race here. A store that loses one only sometimes makes the test flaky rather than red:
 * treat a flaky failure in this class as a real bug.
 */
public abstract class BlobStoreContract {
    /** Returns a new, empty and reachable store that no other test uses. */
    protected abstract suspend fun newStore(): BlobStore

    /** Returns a store that cannot be reached, for example one whose location is gone. */
    protected abstract suspend fun unreachableStore(): BlobStore

    private fun contractTest(body: suspend () -> Unit): TestResult =
        runTest(timeout = TIMEOUT_MINUTES.minutes) { body() }

    @Test
    public fun readReturnsWhatWasWritten(): TestResult =
        contractTest {
            val store = newStore()
            store.writeNew("a/b/file.txt", "hello".encodeToByteArray())
            assertContentEquals("hello".encodeToByteArray(), store.read("a/b/file.txt"))
        }

    @Test
    public fun aFileCanSitAtTheTopLevel(): TestResult =
        contractTest {
            val store = newStore()
            store.writeNew("format.json", byteArrayOf(1))
            assertEquals(listOf(BlobInfo("format.json", 1)), store.list(""))
        }

    @Test
    public fun writingToATakenPathFailsAndKeepsTheFirstContent(): TestResult =
        contractTest {
            val store = newStore()
            store.writeNew("a/file", byteArrayOf(1))
            val failure =
                assertFailsWith<BlobAlreadyExistsException> {
                    store.writeNew("a/file", byteArrayOf(2, 2))
                }
            assertEquals("a/file", failure.path)
            assertContentEquals(byteArrayOf(1), store.read("a/file"))
            assertEquals(listOf(BlobInfo("a/file", 1)), store.list(""))
        }

    @Test
    public fun writingIdenticalContentToATakenPathFailsToo(): TestResult =
        contractTest {
            val store = newStore()
            store.writeNew("file", byteArrayOf(1))
            assertFailsWith<BlobAlreadyExistsException> { store.writeNew("file", byteArrayOf(1)) }
        }

    @Test
    public fun writingToAPathThatIsADirectoryFails(): TestResult =
        contractTest {
            val store = newStore()
            store.writeNew("a/b", byteArrayOf(1))
            assertFailsWith<BlobAlreadyExistsException> { store.writeNew("a", byteArrayOf(2)) }
            assertEquals(listOf("a/b"), store.list("").map { it.path })
        }

    @Test
    public fun writingBelowAFileFails(): TestResult =
        contractTest {
            val store = newStore()
            store.writeNew("a", byteArrayOf(1))
            assertFailsWith<BlobStoreException> { store.writeNew("a/b", byteArrayOf(2)) }
            assertEquals(listOf("a"), store.list("").map { it.path })
        }

    @Test
    public fun readingAMissingFileFails(): TestResult =
        contractTest {
            val store = newStore()
            val failure = assertFailsWith<BlobNotFoundException> { store.read("missing") }
            assertEquals("missing", failure.path)
        }

    @Test
    public fun readingADirectoryOrBelowAFileFailsAsNotFound(): TestResult =
        contractTest {
            val store = newStore()
            store.writeNew("a/b", byteArrayOf(1))
            assertFailsWith<BlobNotFoundException> { store.read("a") }
            assertFailsWith<BlobNotFoundException> { store.read("a/b/c") }
        }

    @Test
    public fun aNewStoreIsReachableAndEmpty(): TestResult =
        contractTest {
            val store = newStore()
            assertEquals(Reachability.Reachable, store.ping())
            assertEquals(emptyList(), store.list(""))
        }

    @Test
    public fun listReturnsEveryFileWithItsSizeInPathOrder(): TestResult =
        contractTest {
            val store = newStore()
            // Written out of order. "." sorts before "/", so a directory walk alone gets it wrong.
            store.writeNew("z", ByteArray(3))
            store.writeNew("a/b/c", ByteArray(1))
            store.writeNew("a/a", ByteArray(0))
            store.writeNew("a.b", ByteArray(2))
            assertEquals(
                listOf(
                    BlobInfo("a.b", 2),
                    BlobInfo("a/a", 0),
                    BlobInfo("a/b/c", 1),
                    BlobInfo("z", 3),
                ),
                store.list(""),
            )
        }

    @Test
    public fun listMatchesWholeSegmentsNotStringPrefixes(): TestResult =
        contractTest {
            val store = newStore()
            store.writeNew("dev/one", byteArrayOf())
            store.writeNew("dev/sub/two", byteArrayOf())
            store.writeNew("device/three", byteArrayOf())
            store.writeNew("dev-old", byteArrayOf())
            assertEquals(listOf("dev/one", "dev/sub/two"), store.list("dev").map { it.path })
            assertEquals(listOf("dev/sub/two"), store.list("dev/sub").map { it.path })
            assertEquals(emptyList(), store.list("de"))
        }

    @Test
    public fun listOfAMissingPrefixOrOfAFileIsEmpty(): TestResult =
        contractTest {
            val store = newStore()
            store.writeNew("a/file", byteArrayOf())
            assertEquals(emptyList(), store.list("missing"))
            assertEquals(emptyList(), store.list("a/missing/deeper"))
            assertEquals(emptyList(), store.list("a/file"))
            assertEquals(emptyList(), store.list("a/file/below"))
        }

    @Test
    public fun anEmptyFileIsAFile(): TestResult =
        contractTest {
            val store = newStore()
            store.writeNew("empty", byteArrayOf())
            assertContentEquals(byteArrayOf(), store.read("empty"))
            assertEquals(listOf(BlobInfo("empty", 0)), store.list(""))
            assertFailsWith<BlobAlreadyExistsException> { store.writeNew("empty", byteArrayOf()) }
        }

    @Test
    public fun everyByteValueSurvives(): TestResult =
        contractTest {
            val store = newStore()
            val bytes = ByteArray(BYTE_VALUES) { it.toByte() }
            store.writeNew("binary", bytes)
            assertContentEquals(bytes, store.read("binary"))
        }

    @Test
    public fun aLargeFileSurvives(): TestResult =
        contractTest {
            val store = newStore()
            val bytes = ByteArray(LARGE_FILE_SIZE) { (it * PATTERN_FACTOR shr 3).toByte() }
            store.writeNew("large", bytes)
            assertContentEquals(bytes, store.read("large"))
            assertEquals(listOf(BlobInfo("large", LARGE_FILE_SIZE.toLong())), store.list(""))
        }

    @Test
    public fun theBytesPassedInCanChangeAfterTheWrite(): TestResult =
        contractTest {
            val store = newStore()
            val bytes = byteArrayOf(1)
            store.writeNew("file", bytes)
            bytes[0] = 2
            val read = store.read("file")
            assertContentEquals(byteArrayOf(1), read)
            read[0] = 3
            assertContentEquals(byteArrayOf(1), store.read("file"))
        }

    @Test
    public fun nonAsciiNamesSurvive(): TestResult =
        contractTest {
            val store = newStore()
            val path = "ordner/caf\u00e9 \u65e5\u672c.txt"
            store.writeNew(path, byteArrayOf(1))
            assertEquals(listOf(path), store.list("ordner").map { it.path })
            assertContentEquals(byteArrayOf(1), store.read(path))
        }

    @Test
    public fun concurrentWritersOfOnePathHaveExactlyOneWinner(): TestResult =
        contractTest {
            val store = newStore()
            repeat(RACE_ROUNDS) { round ->
                val path = "race/$round"
                val winners =
                    coroutineScope {
                        (0 until RACE_WRITERS)
                            .map { writer ->
                                async(
                                    Dispatchers.Default,
                                ) { writer to tryWrite(store, path, writer) }
                            }.awaitAll()
                    }.filter { (_, won) -> won }.map { (writer, _) -> writer }
                assertEquals(1, winners.size, "winners of $path: $winners")
                assertContentEquals(contentOf(winners.single()), store.read(path))
            }
            assertEquals(RACE_ROUNDS, store.list("race").size)
        }

    private suspend fun tryWrite(
        store: BlobStore,
        path: String,
        writer: Int,
    ): Boolean =
        try {
            store.writeNew(path, contentOf(writer))
            true
        } catch (_: BlobAlreadyExistsException) {
            false
        }

    // Different content per writer, so that a store that overwrites cannot go unnoticed.
    private fun contentOf(writer: Int) = ByteArray(RACE_FILE_SIZE) { writer.toByte() }

    @Test
    public fun concurrentWritersOfDifferentPathsAllSucceed(): TestResult =
        contractTest {
            val store = newStore()
            coroutineScope {
                repeat(RACE_WRITERS * RACE_WRITERS) { index ->
                    launch(Dispatchers.Default) {
                        store.writeNew("shared/dir/$index", byteArrayOf(index.toByte()))
                    }
                }
            }
            assertEquals(RACE_WRITERS * RACE_WRITERS, store.list("shared").size)
        }

    @Test
    public fun aReaderNeverSeesAPartOfAFile(): TestResult =
        contractTest {
            val store = newStore()
            val bytes = ByteArray(PARTIAL_READ_FILE_SIZE) { 7 }
            coroutineScope {
                launch(Dispatchers.Default) {
                    repeat(PARTIAL_READ_ROUNDS) { store.writeNew("seen/$it", bytes) }
                }
                launch(Dispatchers.Default) {
                    var complete = 0
                    while (complete < PARTIAL_READ_ROUNDS) {
                        val listed = store.list("seen")
                        listed.forEach { assertEquals(bytes.size.toLong(), it.size, it.path) }
                        listed.forEach { assertContentEquals(bytes, store.read(it.path), it.path) }
                        complete = listed.size
                    }
                }
            }
        }

    @Test
    public fun badPathsAreRefusedByEveryMethod(): TestResult =
        contractTest {
            val store = newStore()
            store.writeNew("a/file", byteArrayOf(1))
            BAD_PATHS.forEach { path ->
                assertFailsWith<InvalidBlobPathException>("list \"$path\"") { store.list(path) }
                assertFailsWith<InvalidBlobPathException>("read \"$path\"") { store.read(path) }
                assertFailsWith<InvalidBlobPathException>("writeNew \"$path\"") {
                    store.writeNew(path, byteArrayOf(1))
                }
            }
            assertEquals(listOf("a/file"), store.list("").map { it.path })
        }

    @Test
    public fun theEmptyPathIsOnlyValidAsAPrefix(): TestResult =
        contractTest {
            val store = newStore()
            assertFailsWith<InvalidBlobPathException> { store.read("") }
            assertFailsWith<InvalidBlobPathException> { store.writeNew("", byteArrayOf()) }
        }

    @Test
    public fun anUnreachableStoreNeverLooksEmpty(): TestResult =
        contractTest {
            val store = unreachableStore()
            assertIs<Reachability.Unreachable>(store.ping())
            assertFailsWith<BackendUnavailableException> { store.list("") }
            assertFailsWith<BackendUnavailableException> { store.list("a/b") }
            assertFailsWith<BackendUnavailableException> { store.read("a") }
            assertFailsWith<BackendUnavailableException> { store.writeNew("a", byteArrayOf(1)) }
        }

    @Test
    public fun anUnreachableStoreChecksPathsFirst(): TestResult =
        contractTest {
            val store = unreachableStore()
            assertFailsWith<InvalidBlobPathException> { store.read("../a") }
            assertFailsWith<InvalidBlobPathException> { store.list("a//b") }
            assertFailsWith<InvalidBlobPathException> { store.writeNew("/a", byteArrayOf(1)) }
        }

    private companion object {
        const val TIMEOUT_MINUTES = 3
        const val BYTE_VALUES = 256
        const val LARGE_FILE_SIZE = 8 * 1024 * 1024 + 13
        const val PATTERN_FACTOR = 31
        const val RACE_ROUNDS = 30
        const val RACE_WRITERS = 8
        const val RACE_FILE_SIZE = 4096
        const val PARTIAL_READ_ROUNDS = 20
        const val PARTIAL_READ_FILE_SIZE = 256 * 1024

        val BAD_PATHS =
            listOf(
                "..",
                ".",
                "../secret",
                "a/../../secret",
                "a/..",
                "a/./b",
                "/etc/passwd",
                "/",
                "a/",
                "a//b",
                "a\\b",
                "..\\secret",
                "C:\\Windows",
                "C:/Windows",
                "C:secret",
                "a\u0000b",
                "a/\u0000",
                "${RESERVED_SEGMENT_PREFIX}anything",
                "a/${RESERVED_SEGMENT_PREFIX}anything",
                "a/${RESERVED_SEGMENT_PREFIX.uppercase()}anything",
            )
    }
}
