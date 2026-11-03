// Tests pick their threads, and the numbers are the point of each test.
@file:Suppress("InjectDispatcher", "MagicNumber")

package io.github.sameerkrmishra.jotter.backend.peer

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.InvalidBlobPathException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.peer.FakeHub.Companion.answer
import io.github.sameerkrmishra.jotter.backend.peer.FakeHub.Companion.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The client against hubs that misbehave, which the real server does not. The tests of the
 * `server` module run the contract suite through this client against a real hub.
 */
class PeerBlobStoreTest {
    private val fakes = mutableListOf<FakeHub>()
    private val stores = mutableListOf<PeerBlobStore>()

    @AfterTest
    fun closeAll() {
        stores.forEach { it.close() }
        fakes.forEach { it.close() }
    }

    private fun test(body: suspend () -> Unit): TestResult =
        runTest(timeout = 1.minutes) { withContext(Dispatchers.Default) { body() } }

    /** A store on a fake hub that answers every request with [text]. */
    private suspend fun storeAnswering(text: String): PeerBlobStore = storeOn { send(text) }

    private suspend fun storeOn(
        answer: suspend io.ktor.utils.io.ByteWriteChannel.(String) -> Unit,
    ): PeerBlobStore {
        val fake = FakeHub.start(answer).also { fakes += it }
        return PeerBlobStore(
            fake.address,
            TOKEN,
            PeerTimeouts(socket = 500.milliseconds, request = 2.seconds),
            Dispatchers.Default,
        ).also {
            stores +=
                it
        }
    }

    @Test
    fun aTokenThatCouldChangeTheRequestIsRefused() {
        listOf("", "a b", "a\r\nX-Injected: 1", "a:b", "é").forEach { token ->
            assertFailsWith<IllegalArgumentException>(
                token,
            ) { PeerBlobStore(PeerAddress("h"), token) }
        }
    }

    @Test
    fun requestsCarryTheTokenAndEncodeThePath(): TestResult =
        test {
            val fake = FakeHub.start { send(answer(200, "x")) }.also { fakes += it }
            PeerBlobStore(fake.address, TOKEN).use { it.read("dir/a b+c&d=e%f") }
            val head = fake.requests.single()
            assertContains(head, "GET ${PeerProtocol.BLOB}?path=dir%2Fa+b%2Bc%26d%3De%25f HTTP/1.1")
            assertContains(head, "Authorization: Bearer $TOKEN\r\n")
        }

    @Test
    fun anAnswerWithoutTheProtocolHeaderIsNotAHub(): TestResult =
        test {
            val store = storeAnswering(answer(200, "[]", asHub = false))
            val failure = assertFailsWith<BackendUnavailableException> { store.list("") }
            assertContains(failure.message!!, "does not answer as a Jotter hub")
            val ping = store.ping()
            assertEquals(UnreachableReason.FAILED, (ping as Reachability.Unreachable).reason)
        }

    @Test
    fun aBodyCutShortIsAHubThatWentAway(): TestResult =
        test {
            val store = storeAnswering(answer(200, "[{\"pa", length = 100))
            assertFailsWith<BackendUnavailableException> { store.list("") }
            assertFailsWith<BackendUnavailableException> { store.read("a") }
        }

    @Test
    fun aBodyLargerThanTheLimitIsRefused(): TestResult =
        test {
            val store = storeAnswering(answer(200, "", length = PeerProtocol.MAX_FILE_BYTES + 1))
            assertFailsWith<BlobStoreFailureException> { store.read("a") }
            assertFailsWith<BlobStoreFailureException> { store.list("") }
        }

    @Test
    fun aBodyWithoutALengthIsRefused(): TestResult =
        test {
            val store = storeAnswering(answer(200, "[]", length = null))
            assertFailsWith<BlobStoreFailureException> { store.list("") }
        }

    @Test
    fun aListingThatNoStoreCouldGiveIsRefused(): TestResult =
        test {
            val listings =
                listOf(
                    "" to "not json",
                    "" to "{}",
                    "" to """[{"path":"a"}]""",
                    "" to """[{"path":"b","size":1},{"path":"a","size":1}]""",
                    "" to """[{"path":"a","size":1},{"path":"a","size":1}]""",
                    "" to """[{"path":"a/../b","size":1}]""",
                    "" to """[{"path":".jotter-tmp-x","size":1}]""",
                    "" to """[{"path":"a","size":-1}]""",
                    "a" to """[{"path":"b/c","size":1}]""",
                    "a" to """[{"path":"a","size":1}]""",
                    "a" to """[{"path":"ab/c","size":1}]""",
                )
            listings.forEach { (prefix, body) ->
                val store = storeAnswering(answer(200, body))
                assertFailsWith<BlobStoreFailureException>(body) { store.list(prefix) }
            }
            val good =
                storeAnswering(answer(200, """[{"path":"a/b","size":2},{"path":"a/c","size":0}]"""))
            assertEquals(listOf(BlobInfo("a/b", 2), BlobInfo("a/c", 0)), good.list("a"))
        }

    @Test
    fun errorsMapToTheStoreExceptions(): TestResult =
        test {
            fun error(
                status: Int,
                code: String,
                reason: String? = null,
            ) = answer(
                status,
                """{"code":"$code","detail":"why","reason":${reason?.let { "\"$it\"" }}}""",
            )

            assertFailsWith<BlobNotFoundException> {
                storeAnswering(
                    error(404, "NOT_FOUND"),
                ).read("a")
            }
            assertFailsWith<BlobAlreadyExistsException> {
                storeAnswering(error(409, "ALREADY_EXISTS")).writeNew("a", byteArrayOf(1))
            }
            assertFailsWith<InvalidBlobPathException> {
                storeAnswering(
                    error(400, "INVALID_PATH"),
                ).read("a")
            }
            assertFailsWith<BlobStoreFailureException> {
                storeAnswering(
                    error(500, "FAILED"),
                ).read("a")
            }
            assertFailsWith<BlobStoreFailureException> {
                storeAnswering(
                    error(400, "BAD_REQUEST"),
                ).read("a")
            }

            val missing = storeAnswering(error(503, "UNAVAILABLE", "NOT_INITIALIZED"))
            val unavailable = assertFailsWith<BackendUnavailableException> { missing.list("") }
            assertEquals(UnreachableReason.NOT_INITIALIZED, unavailable.reachability.reason)
            assertContains(unavailable.reachability.detail, "why")
            assertEquals(unavailable.reachability, missing.ping())

            val strange = storeAnswering(error(503, "UNAVAILABLE", "SOMETHING_NEW"))
            assertEquals(
                UnreachableReason.FAILED,
                (strange.ping() as Reachability.Unreachable).reason,
            )

            val refused = storeAnswering(error(401, "UNAUTHORIZED"))
            assertFailsWith<BackendUnavailableException> { refused.read("a") }
            assertContains((refused.ping() as Reachability.Unreachable).detail, "token")
        }

    @Test
    fun aCodeOnlyCountsWithItsOwnStatus(): TestResult =
        test {
            val store = storeAnswering(answer(404, """{"code":"ALREADY_EXISTS","detail":"x"}"""))
            assertFailsWith<BlobStoreFailureException> { store.writeNew("a", byteArrayOf(1)) }
            val unavailable = storeAnswering(answer(500, """{"code":"UNAVAILABLE","detail":"x"}"""))
            assertFailsWith<BlobStoreFailureException> { unavailable.list("") }
        }

    @Test
    fun anErrorWithoutABodyItCanReadIsAFailure(): TestResult =
        test {
            listOf(answer(500, "oops"), answer(500, "", length = null), answer(418, "{}")).forEach {
                val store = storeAnswering(it)
                val failure = assertFailsWith<BlobStoreFailureException> { store.read("a") }
                assertContains(failure.message!!, "no reason given")
                assertIs<Reachability.Unreachable>(store.ping())
            }
        }

    @Test
    fun aRedirectIsNotFollowed(): TestResult =
        test {
            val fake =
                FakeHub.start {
                    send(
                        answer(302, "", extra = "Location: http://127.0.0.1:1/x\r\n"),
                    )
                }
            fakes += fake
            val store = PeerBlobStore(fake.address, TOKEN).also { stores += it }
            assertFailsWith<BlobStoreFailureException> { store.read("a") }
            assertEquals(1, fake.requests.size)
        }

    @Test
    fun aHubThatIsNotThereIsUnavailable(): TestResult =
        test {
            val fake = FakeHub.start { }
            val address = fake.address
            fake.close()
            val store = PeerBlobStore(address, TOKEN).also { stores += it }
            assertFailsWith<BackendUnavailableException> { store.list("") }
            assertIs<Reachability.Unreachable>(store.ping())
        }

    @Test
    fun aHubThatNeverAnswersIsUnavailable(): TestResult =
        test {
            val store = storeOn { awaitCancellation() }
            assertFailsWith<BackendUnavailableException> { store.read("a") }
        }

    @Test
    fun theCallersCancellationIsNotAnError(): TestResult =
        test {
            val fake = FakeHub.start { awaitCancellation() }.also { fakes += it }
            val store = PeerBlobStore(fake.address, TOKEN).also { stores += it }
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(
                    200.milliseconds,
                ) { store.read("a") }
            }
        }

    @Test
    fun aClosedStoreIsUnavailable(): TestResult =
        test {
            val store = storeAnswering(answer(200, "{}"))
            assertEquals(Reachability.Reachable, store.ping())
            store.close()
            assertFailsWith<BackendUnavailableException> { store.read("a") }
        }

    @Test
    fun aFileLargerThanTheLimitIsNotSent(): TestResult =
        test {
            val fake = FakeHub.start { send(answer(201, "")) }.also { fakes += it }
            val store = PeerBlobStore(fake.address, TOKEN).also { stores += it }
            assertFailsWith<BlobStoreFailureException> {
                store.writeNew("a", ByteArray(PeerProtocol.MAX_FILE_BYTES + 1))
            }
            assertTrue(fake.requests.isEmpty())
            store.writeNew("a", byteArrayOf(1))
            assertContains(fake.requests.single(), "PUT ${PeerProtocol.BLOB}?path=a")
        }

    @Test
    fun readReturnsTheBody(): TestResult =
        test {
            assertContentEquals(
                "abc".encodeToByteArray(),
                storeAnswering(answer(200, "abc")).read("a"),
            )
        }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef.Secret-part_1"
    }
}
