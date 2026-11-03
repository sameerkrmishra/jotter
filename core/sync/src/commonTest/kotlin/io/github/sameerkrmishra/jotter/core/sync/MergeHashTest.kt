package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Pins the canonical form of ADR-0013. If one of these vectors changes, stored merges no longer
 * match their ids on other devices: that takes a new format, not a new vector.
 */
class MergeHashTest {
    private val root =
        userRevision(1, 1_000) {
            snapshot("title", "a\nb\nc\n", setOf("zebra", "Äpfel", "apple"), writer = it)
        }
    private val one = userRevision(2, 2_000, root) { root.text.copy(body = "A\nb\nc\n") }
    private val two =
        userRevision(3, 3_000, root, DEVICE_B) {
            root.text.copy(body = "a\nb\nC ü 😀\n")
        }
    private val all = listOf(root, one, two)

    @Test
    fun aCleanMergeHasItsPinnedBytesAndId() {
        val merge = mergeRevisions(RevisionGraph(all), one, two)
        assertEquals(CLEAN_BYTES, canonicalMergeBytes(merge).toHexString())
        assertEquals(CLEAN_ID, merge.id.toString())
    }

    @Test
    fun aConflictedMergeHasItsPinnedId() {
        val other = textRevision(4, 4_000, "x\nb\nc\n", root)
        val merge = mergeRevisions(RevisionGraph(all + other), one, other)
        assertEquals(CONFLICT_ID, merge.id.toString())
    }

    @Test
    fun aTombstoneMergeHasItsPinnedId() {
        val deleted = userRevision(4, 4_000, root) { RevisionContent.Tombstone }
        val other = userRevision(5, 5_000, root, DEVICE_B) { RevisionContent.Tombstone }
        val merge = mergeRevisions(RevisionGraph(listOf(root, deleted, other)), deleted, other)
        assertEquals(TOMBSTONE_ID, merge.id.toString())
    }

    @Test
    fun aPinnedTrashedMergeHasItsPinnedId() {
        val deleted = userRevision(4, 4_000, root) { RevisionContent.Tombstone }
        val pinned = root.text.copy(pinned = root.text.pinned.copy(value = true))
        val edit = userRevision(5, 5_000, root, DEVICE_B) { pinned }
        val merge = mergeRevisions(RevisionGraph(listOf(root, deleted, edit)), deleted, edit)
        assertEquals(NoteState.TRASHED, merge.text.state.value)
        assertEquals(TRASHED_ID, merge.id.toString())
    }

    @Test
    fun everyFieldIsInTheHash() {
        val merge = mergeRevisions(RevisionGraph(all), one, two)
        val changed: List<Revision> =
            listOf(
                merge.copy(content = merge.text.copy(title = "other")),
                merge.copy(content = merge.text.copy(body = "other")),
                merge.copy(content = merge.text.copy(tags = emptySet())),
                merge.copy(
                    content = merge.text.copy(pinned = merge.text.pinned.copy(value = true)),
                ),
                merge.copy(
                    content = merge.text.copy(state = merge.text.state.copy(stamp = one.stamp)),
                ),
                merge.copy(deviceId = DEVICE_A),
                merge.copy(timestamp = one.timestamp),
                merge.copy(
                    noteId =
                        io.github.sameerkrmishra.jotter.core.model.NoteId
                            .generate(),
                ),
            )
        changed.forEach { assertNotEquals(merge.id, mergeRevisionId(it)) }
    }

    @Test
    fun theLengthPrefixesKeepTitleAndBodyApart() {
        val merge = mergeRevisions(RevisionGraph(all), one, two)
        val moved =
            merge.copy(
                content =
                    merge.text.copy(
                        title = "${merge.text.title}a",
                        body = merge.text.body.drop(1),
                    ),
            )
        assertNotEquals(mergeRevisionId(merge), mergeRevisionId(moved))
    }

    @Test
    fun aUserRevisionHasNoMergeHash() {
        assertFailsWith<IllegalArgumentException> { canonicalMergeBytes(root) }
    }

    private companion object {
        const val CLEAN_BYTES =
            "6a6f747465722d6d657267652d76310a0000002430313839303030302d303030302d373030302d38" +
                "3030302d303030303030303030303031000000020000002030303030303030303030303030303030" +
                "30303030303030303030303030303032000000203030303030303030303030303030303030303030" +
                "3030303030303030303030330000000000000bb8000000000000002462626262626262622d303030" +
                "302d343030302d383030302d3030303030303030303030320001000000057469746c650000000e41" +
                "0a620a4320c3bc20f09f98800a00000003000000056170706c65000000057a6562726100000006c3" +
                "847066656c0000000000000003e8000000000000002461616161616161612d303030302d34303030" +
                "2d383030302d30303030303030303030303100000020303030303030303030303030303030303030" +
                "30303030303030303030303030310000000641435449564500000000000003e80000000000000024" +
                "61616161616161612d303030302d343030302d383030302d30303030303030303030303100000020" +
                "3030303030303030303030303030303030303030303030303030303030303031"
        const val CLEAN_ID =
            "cc132519a31d7599bdae0043f050040c36294b43134efbddbf1ac7a4fa5848ce"
        const val CONFLICT_ID =
            "7b81b731120158bf025efe3db013dc5ec68820db3941f78367145cf7d0222895"
        const val TOMBSTONE_ID =
            "440f148f4508f61b1d18164a8e0f2718ea4cbd6f25f85d514b877ef2bf1dfaeb"
        const val TRASHED_ID =
            "c257cc35342469a827d6e75934a2ae5c0aed75c900849be7c5d1a962d8ce270c"
    }
}
