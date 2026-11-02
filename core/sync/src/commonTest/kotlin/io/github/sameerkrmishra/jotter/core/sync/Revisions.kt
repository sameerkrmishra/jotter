@file:Suppress("LongParameterList") // Fixtures with defaults, read by name.

package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.Stamp
import io.github.sameerkrmishra.jotter.core.model.Stamped
import io.github.sameerkrmishra.jotter.core.model.TagName

/** Fixed ids, so that fixtures and pinned vectors never change. */
internal val NOTE: NoteId = NoteId.parse("01890000-0000-7000-8000-000000000001")
internal val DEVICE_A: DeviceId = DeviceId.parse("aaaaaaaa-0000-4000-8000-000000000001")
internal val DEVICE_B: DeviceId = DeviceId.parse("bbbbbbbb-0000-4000-8000-000000000002")

/** The id of the [number]th user revision of a fixture. */
internal fun revisionId(number: Int): RevisionId =
    RevisionId.parse(number.toString().padStart(HEX_IN_UUID, '0'))

private const val HEX_IN_UUID = 32

/** A snapshot whose last-writer-wins fields were written by [writer]. */
internal fun snapshot(
    title: String = "",
    body: String = "",
    tags: Set<String> = emptySet(),
    pinned: Boolean = false,
    state: NoteState = NoteState.ACTIVE,
    writer: Stamp,
): RevisionContent.Snapshot =
    RevisionContent.Snapshot(
        title = title,
        body = body,
        tags = tags.mapTo(LinkedHashSet(), TagName::parse),
        pinned = Stamped(pinned, writer),
        state = Stamped(state, writer),
    )

/** A user revision at [time], numbered [number], with one parent or none. */
internal fun userRevision(
    number: Int,
    time: Long,
    parent: Revision? = null,
    device: DeviceId = DEVICE_A,
    resolves: RevisionId? = null,
    content: (Stamp) -> RevisionContent,
): Revision {
    val id = revisionId(number)
    val timestamp = HybridTimestamp(time, 0)
    return Revision(
        id = id,
        noteId = NOTE,
        parents = setOfNotNull(parent?.id),
        timestamp = timestamp,
        deviceId = device,
        kind = RevisionKind.User,
        content = content(Stamp(timestamp, device, id)),
        resolves = resolves,
    )
}

/** A user revision with just this [body], and tags. */
internal fun textRevision(
    number: Int,
    time: Long,
    body: String,
    parent: Revision? = null,
    tags: Set<String> = emptySet(),
    device: DeviceId = DEVICE_A,
): Revision =
    userRevision(number, time, parent, device) { snapshot(body = body, tags = tags, writer = it) }

/** The snapshot of this revision, which must have one. */
internal val Revision.text: RevisionContent.Snapshot
    get() = content as RevisionContent.Snapshot
