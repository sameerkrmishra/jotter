package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.Stamp
import io.github.sameerkrmishra.jotter.core.model.TagName
import okio.Buffer

/**
 * Returns the id an automatic merge must have: SHA-256 of [canonicalMergeBytes].
 *
 * @throws IllegalArgumentException if [revision] is not an automatic merge.
 */
internal fun mergeRevisionId(revision: Revision): RevisionId =
    RevisionId.fromMergeHash(Buffer().write(canonicalMergeBytes(revision)).sha256().toByteArray())

/**
 * The canonical bytes of an automatic merge, every field but its id. This layout is permanent, as
 * ADR-0013 explains, and specified there. `MergeHashTest` pins it with fixed vectors.
 */
internal fun canonicalMergeBytes(revision: Revision): ByteArray {
    val merge = revision.kind as? RevisionKind.AutomaticMerge
    requireNotNull(merge) { "Revision ${revision.id} is not an automatic merge" }
    val out = Buffer()
    out.writeUtf8(DOMAIN)
    out.text(revision.noteId.toString())
    val parents = revision.parents.sorted()
    out.writeInt(parents.size)
    parents.forEach { out.text(it.toString()) }
    out.writeLong(revision.timestamp.physicalMillis)
    out.writeInt(revision.timestamp.counter)
    out.text(revision.deviceId.toString())
    val loser = merge.losingParent
    out.writeByte(if (loser == null) 0 else 1)
    if (loser != null) out.text(loser.toString())
    when (val content = revision.content) {
        RevisionContent.Tombstone -> out.writeByte(0)
        is RevisionContent.Snapshot -> out.snapshot(content)
    }
    return out.readByteArray()
}

private fun Buffer.snapshot(content: RevisionContent.Snapshot) {
    writeByte(1)
    text(content.title)
    text(content.body)
    val tags = content.tags.map(TagName::toString).sorted()
    writeInt(tags.size)
    tags.forEach { text(it) }
    writeByte(if (content.pinned.value) 1 else 0)
    stamp(content.pinned.stamp)
    text(content.state.value.name)
    stamp(content.state.stamp)
}

private fun Buffer.stamp(stamp: Stamp) {
    writeLong(stamp.timestamp.physicalMillis)
    writeInt(stamp.timestamp.counter)
    text(stamp.deviceId.toString())
    text(stamp.revisionId.toString())
}

/** A string: its length in UTF-8 bytes, as a big-endian 32-bit integer, then those bytes. */
private fun Buffer.text(value: String) {
    val bytes = value.encodeToByteArray()
    writeInt(bytes.size)
    write(bytes)
}

/** Separates these bytes from anything else that is ever hashed, and names the layout. */
private const val DOMAIN = "jotter-merge-v1\n"
