package io.github.sameerkrmishra.jotter.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RevisionTest {
    private val timestamp = HybridTimestamp(1_000, 0)
    private val first = RevisionId.parse("0192e6f08b4a7c3e9d2f1a2b3c4d5e6f")
    private val second = RevisionId.parse("0192e6f08b4a7c3e9d2f1a2b3c4d5e70")
    private val mergeId = RevisionId.fromMergeHash(ByteArray(RevisionId.MERGE_HASH_SIZE) { 7 })
    private val initialStamp = Stamp(timestamp, sampleDevice, first)
    private val snapshot =
        RevisionContent.Snapshot(
            title = "Groceries",
            body = "- [ ] milk",
            tags = setOf(TagName.parse("shopping")),
            pinned = Stamped(false, initialStamp),
            state = Stamped(NoteState.ACTIVE, initialStamp),
        )

    @Test
    fun stampCombinesTimestampDeviceAndId() {
        val revision = userRevision(first, parents = emptySet())

        assertEquals(Stamp(timestamp, sampleDevice, first), revision.stamp)
    }

    @Test
    fun userRevisionHasAtMostOneParent() {
        userRevision(second, parents = setOf(first))

        val error =
            assertFailsWith<IllegalArgumentException> {
                userRevision(second, parents = setOf(first, mergeId))
            }
        assertEquals("A user revision has at most one parent: [$first, $mergeId]", error.message)
    }

    @Test
    fun userRevisionNeedsAUuidId() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                userRevision(mergeId, parents = setOf(first))
            }

        assertEquals("A user revision needs a UUID id, not $mergeId", error.message)
    }

    @Test
    fun revisionCannotBeItsOwnParent() {
        assertFailsWith<IllegalArgumentException> { userRevision(first, parents = setOf(first)) }
    }

    @Test
    fun cleanMergeOfTwoHeads() {
        val kind = RevisionKind.AutomaticMerge(losingParent = null)

        val merge = merge(kind, setOf(first, second))

        assertFalse(kind.isConflicted)
        assertEquals(setOf(first, second), merge.parents)
    }

    @Test
    fun cleanMergeMayBeATombstone() {
        // Two devices deleted the note forever concurrently.
        val merge =
            merge(
                RevisionKind.AutomaticMerge(null),
                setOf(first, second),
                content = RevisionContent.Tombstone,
            )

        assertEquals(RevisionContent.Tombstone, merge.content)
    }

    @Test
    fun conflictedMergeNamesTheLosingParent() {
        val kind = RevisionKind.AutomaticMerge(losingParent = second)

        merge(kind, setOf(first, second))

        assertTrue(kind.isConflicted)
    }

    @Test
    fun mergeNeedsAHashId() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                merge(RevisionKind.AutomaticMerge(null), setOf(first, mergeId), id = second)
            }

        assertEquals("An automatic merge needs a hash id, not $second", error.message)
    }

    @Test
    fun mergeHasExactlyTwoParents() {
        listOf(emptySet(), setOf(first), setOf(first, second, RevisionId.generate())).forEach {
            assertFailsWith<IllegalArgumentException>("Parents $it") {
                merge(RevisionKind.AutomaticMerge(null), it)
            }
        }
    }

    @Test
    fun losingParentMustBeAParent() {
        val stranger = RevisionId.generate()

        val error =
            assertFailsWith<IllegalArgumentException> {
                merge(RevisionKind.AutomaticMerge(stranger), setOf(first, second))
            }
        assertEquals("Losing parent $stranger is not in [$first, $second]", error.message)
    }

    @Test
    fun conflictedMergeCannotBeATombstone() {
        assertFailsWith<IllegalArgumentException> {
            merge(
                RevisionKind.AutomaticMerge(first),
                setOf(first, second),
                content = RevisionContent.Tombstone,
            )
        }
    }

    private fun userRevision(
        id: RevisionId,
        parents: Set<RevisionId>,
    ) = Revision(id, sampleNote, parents, timestamp, sampleDevice, RevisionKind.User, snapshot)

    private fun merge(
        kind: RevisionKind.AutomaticMerge,
        parents: Set<RevisionId>,
        id: RevisionId = mergeId,
        content: RevisionContent = snapshot,
    ) = Revision(id, sampleNote, parents, timestamp, sampleDevice, kind, content)
}
