package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.GzipSink
import okio.buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SegmentCodecTest {
    private val codec = JsonGzipCodec()
    private val root = textRevision(1, 1_000, "a\nb\n", tags = setOf("b", "a"))
    private val edit = textRevision(2, 2_000, "A\nb\n", root)
    private val other = textRevision(3, 3_000, "a\nB\n", root, device = DEVICE_B)
    private val merge = mergeRevisions(RevisionGraph(listOf(root, edit, other)), edit, other)
    private val tombstone = userRevision(4, 4_000, merge) { RevisionContent.Tombstone }
    private val segment = Segment(DEVICE_A, 7, listOf(root, edit, other, merge, tombstone))

    private fun gzip(text: String): ByteArray {
        val out = Buffer()
        GzipSink(out).buffer().use { it.writeUtf8(text) }
        return out.readByteArray()
    }

    private fun decodeFails(bytes: ByteArray) =
        assertFailsWith<SegmentFormatException> { codec.decode(bytes) }

    @Test
    fun everyShapeOfRevisionRoundTrips() {
        val resolution =
            userRevision(5, 5_000, tombstone, resolves = merge.id) { snapshot(writer = it) }
        val all = segment.copy(revisions = segment.revisions + resolution)
        assertEquals(all, codec.decode(codec.encode(all)))
    }

    @Test
    fun encodingIsStable() {
        assertContentEquals(codec.encode(segment), codec.encode(segment))
    }

    @Test
    fun theJsonFormIsPinned() {
        val one = Segment(DEVICE_A, 1, listOf(root))
        assertEquals(PINNED_JSON, segmentJson(one))
    }

    @Test
    fun aPinnedFileDecodes() {
        val one = Segment(DEVICE_A, 1, listOf(root))
        assertEquals(one, codec.decode(PINNED_FILE.decodeHex().toByteArray()))
    }

    /** Worked out outside this code from the JSON above: SHA-256, first 16 bytes, as hex. */
    @Test
    fun theContentHashIsPinned() {
        assertEquals(PINNED_HASH, codec.hash(Segment(DEVICE_A, 1, listOf(root))))
        assertEquals(PINNED_HASH, contentHash(PINNED_JSON))
    }

    @Test
    fun aFileMustHaveTheHashInItsName() {
        val one = Segment(DEVICE_A, 1, listOf(root))
        assertEquals(one, codec.decode(PINNED_FILE.decodeHex().toByteArray(), PINNED_HASH))
        val failure =
            assertFailsWith<SegmentFormatException> {
                codec.decode(codec.encode(segment), PINNED_HASH)
            }
        assertFalse(failure.isFutureFormat)
    }

    @Test
    fun aFutureFormatIsNotJudgedByItsHash() {
        val future = gzip("{\"format\":2}")
        val failure = assertFailsWith<SegmentFormatException> { codec.decode(future, PINNED_HASH) }
        assertTrue(failure.isFutureFormat)
    }

    @Test
    fun aDamagedFileIsRefused() {
        val good = codec.encode(segment)
        decodeFails(good.copyOf(good.size / 2))
        decodeFails(ByteArray(0))
        decodeFails("not gzip".encodeToByteArray())
        decodeFails(good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        decodeFails(gzip("{\"format\":1"))
        decodeFails(gzip("[]"))
    }

    @Test
    fun invalidUtf8IsRefused() {
        val out = Buffer()
        GzipSink(out).buffer().use { it.write(byteArrayOf(0xC3.toByte(), 0x28)) }
        decodeFails(out.readByteArray())
    }

    @Test
    fun aFutureFormatIsToldFromDamage() {
        val future = gzip("{\"format\":2,\"something\":\"else\"}")
        assertTrue(decodeFails(future).isFutureFormat)
        assertFalse(decodeFails(gzip("{\"format\":0}")).isFutureFormat)
    }

    @Test
    fun anUnknownFieldIsRefused() {
        val json = segmentJson(Segment(DEVICE_A, 1, listOf(root))).dropLast(1)
        decodeFails(gzip("$json,\"more\":1}"))
    }

    @Test
    fun aFileLargerThanTheLimitIsRefusedUnread() {
        val small = JsonGzipCodec(maxBytes = 10)
        assertFailsWith<SegmentFormatException> { small.decode(ByteArray(11)) }
    }

    @Test
    fun aSmallFileThatInflatesPastTheLimitIsRefused() {
        val bomb = gzip(" ".repeat(BOMB_CHARS))
        val limited = JsonGzipCodec(maxBytes = BOMB_LIMIT)
        assertTrue(bomb.size < BOMB_LIMIT)
        assertFailsWith<SegmentFormatException> { limited.decode(bomb) }
    }

    @Test
    fun aMergeThatDoesNotMatchItsHashIsRefused() {
        val forged = merge.copy(content = merge.text.copy(body = "forged"))
        val json = segmentJson(Segment(DEVICE_A, 1, listOf(forged)))
        val decoded = codec.decode(gzip(json))
        assertTrue(decoded.revisions.isEmpty())
        assertTrue(decoded.dropped.isEmpty(), "A merge holds no text of a user's own")
    }

    private fun assertDropped(json: String) {
        val decoded = codec.decode(gzip(json))
        assertTrue(decoded.revisions.isEmpty(), json)
        assertEquals(1, decoded.dropped.size, json)
    }

    @Test
    fun aRevisionWithAnInvalidValueIsDroppedAlone() {
        val json = segmentJson(Segment(DEVICE_A, 1, listOf(root)))
        assertDropped(json.replace("\"state\":\"ACTIVE\"", "\"state\":\"LOST\""))
        assertDropped(json.replace("\"counter\":0", "\"counter\":-1"))
        assertDropped(json.replace("\"tags\":[\"a\",\"b\"]", "\"tags\":[\" a\"]"))
        assertDropped(json.replace("\"tags\":[\"a\",\"b\"]", "\"tags\":[\"a\",\"a\"]"))
        assertDropped(json.replace("\"body\":\"a\\nb\\n\"", "\"body\":\"\\ud800\""))
        assertDropped(json.replace("\"parents\":[]", "\"parents\":[\"nonsense\"]"))
    }

    @Test
    fun aRevisionThatCannotSayWhoseItIsLeavesNothing() {
        val json = segmentJson(Segment(DEVICE_A, 1, listOf(root)))
        val decoded = codec.decode(gzip(json.replace("\"id\":\"${root.id}\"", "\"id\":\"x\"")))
        assertTrue(decoded.revisions.isEmpty() && decoded.dropped.isEmpty())
    }

    @Test
    fun aBadDeviceInTheHeaderIsNoSegment() {
        val json = segmentJson(Segment(DEVICE_A, 1, listOf(root)))
        decodeFails(gzip(json.replaceFirst("\"device\":\"$DEVICE_A\"", "\"device\":\"x\"")))
    }

    @Test
    fun aRepeatedParentIsRefused() {
        val child = segmentJson(Segment(DEVICE_A, 1, listOf(edit)))
        val twice =
            child.replace(
                "\"parents\":[\"${root.id}\"]",
                "\"parents\":[\"${root.id}\",\"${root.id}\"]",
            )
        assertDropped(twice)
    }

    @Test
    fun aUserRevisionWithALosingParentIsRefused() {
        val json = segmentJson(Segment(DEVICE_A, 1, listOf(edit)))
        assertDropped(
            json.replace("\"merge\":false", "\"merge\":false,\"losingParent\":\"${root.id}\""),
        )
    }

    private companion object {
        const val BOMB_CHARS = 1_000_000
        const val BOMB_LIMIT = 100_000L
        const val PINNED_JSON =
            "{\"format\":1,\"device\":\"aaaaaaaa-0000-4000-8000-000000000001\",\"number\":1," +
                "\"revisions\":[{\"id\":\"00000000000000000000000000000001\",\"note\":\"01890000-" +
                "0000-7000-8000-000000000001\",\"parents\":[],\"physicalMillis\":1000,\"counter\"" +
                ":0,\"device\":\"aaaaaaaa-0000-4000-8000-000000000001\",\"merge\":false,\"snapsho" +
                "t\":{\"title\":\"\",\"body\":\"a\\nb\\n\",\"tags\":[\"a\",\"b\"],\"pinned\":fals" +
                "e,\"pinnedStamp\":{\"physicalMillis\":1000,\"counter\":0,\"device\":\"aaaaaaaa-0" +
                "000-4000-8000-000000000001\",\"revision\":\"00000000000000000000000000000001\"}," +
                "\"state\":\"ACTIVE\",\"stateStamp\":{\"physicalMillis\":1000,\"counter\":0,\"dev" +
                "ice\":\"aaaaaaaa-0000-4000-8000-000000000001\",\"revision\":\"000000000000000000" +
                "00000000000001\"}}}]}"
        const val PINNED_HASH = "903ff4dd1a4754fe28f79209dd11a707"
        const val PINNED_FILE =
            "1f8b08000000000002ffcd503d8bc24010fd2f5327b08103bd74725c6161a5d89c169364d585cd6c" +
                "d81d0509f9efce443d2b8f3bb8c2570cccd79b37af875d882d329445068d3db9da420978436e04f9" +
                "9b86a906f3400119d0b1ad6c1c57a3ac26172841f9d5836b84c4fc8c9120b09e33c5f4dddcf9f3c9" +
                "b3731d464bac17b6921ccec9d5e817ce7b27b5428632a8c391582599bf7fd3dab897f11dfa643348" +
                "845d3a0431a60776ec954886aad09c957243d5862467dcab2040ed81ea7244b6f9a6b9a64bc6b653" +
                "a6ff577d37fe37860ff216e368f9ec63355f7fc2adf02afa86613b5c00cc31f71792020000"
    }
}
