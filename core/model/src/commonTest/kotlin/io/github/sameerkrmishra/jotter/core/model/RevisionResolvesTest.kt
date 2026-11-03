package io.github.sameerkrmishra.jotter.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RevisionResolvesTest {
    private val merge = RevisionId.fromMergeHash(ByteArray(RevisionId.MERGE_HASH_SIZE))
    private val id = RevisionId.generate()
    private val parent = RevisionId.generate()
    private val time = HybridTimestamp(1, 0)
    private val device = DeviceId.generate()
    private val stamp = Stamp(time, device, id)
    private val content =
        RevisionContent.Snapshot(
            title = "",
            body = "",
            tags = emptySet(),
            pinned = Stamped(false, stamp),
            state = Stamped(NoteState.ACTIVE, stamp),
        )

    private fun revision(
        resolves: RevisionId?,
        content: RevisionContent = this.content,
    ) = Revision(
        id = id,
        noteId = NoteId.generate(),
        parents = setOf(parent),
        timestamp = time,
        deviceId = device,
        kind = RevisionKind.User,
        content = content,
        resolves = resolves,
    )

    @Test
    fun aUserRevisionCanNameTheMergeItResolves() {
        assertEquals(merge, revision(merge).resolves)
    }

    @Test
    fun aHiddenUserRevisionCanBeNamedToo() {
        assertEquals(parent, revision(parent).resolves)
    }

    @Test
    fun aTombstoneResolvesNothing() {
        assertFailsWith<IllegalArgumentException> { revision(merge, RevisionContent.Tombstone) }
    }

    @Test
    fun aMergeResolvesNothing() {
        assertFailsWith<IllegalArgumentException> {
            Revision(
                id = merge,
                noteId = NoteId.generate(),
                parents = setOf(parent, id),
                timestamp = time,
                deviceId = device,
                kind = RevisionKind.AutomaticMerge(null),
                content = content,
                resolves = RevisionId.fromMergeHash(ByteArray(RevisionId.MERGE_HASH_SIZE) { 1 }),
            )
        }
    }
}
