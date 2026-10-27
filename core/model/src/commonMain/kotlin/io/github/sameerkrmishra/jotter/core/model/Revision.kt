package io.github.sameerkrmishra.jotter.core.model

/**
 * One immutable version of a note, a node in the note's revision DAG (ADR-0003). Every edit,
 * pin, state change or deletion creates a new revision whose [parents] are the revisions it
 * builds on. Revisions are what devices store and exchange; [Note] is derived from them.
 *
 * Invariants, checked on construction:
 *
 * - The [kind] matches the [id]: a user revision has a UUID id, an automatic merge a hash id.
 * - A user revision has no parent (it creates the note) or one.
 * - An automatic merge has exactly two parents, and a conflicted one names one of them as the
 *   loser and carries a [RevisionContent.Snapshot], whose text is the one shown.
 * - A revision is not its own parent, and a tombstone has a parent: only an existing note can
 *   be deleted.
 *
 * Rules that need other revisions, such as a user revision's timestamp being greater than its
 * parents', are the job of the code that creates and applies revisions.
 *
 * @property id The revision's id.
 * @property noteId The note this revision belongs to.
 * @property parents The revisions this one builds on. Empty only for the first revision.
 * @property timestamp For a user revision, the device's fresh clock reading. For an automatic
 *   merge, the timestamp of the parent with the greater [stamp].
 * @property deviceId For a user revision, the device that made it. For an automatic merge, the
 *   device of the parent with the greater [stamp]; the device that computed the merge is not
 *   recorded, so every device computes the same revision.
 * @property kind Whether a person or the merge code made this revision.
 * @property content The note's content as of this revision, or a tombstone.
 */
public data class Revision(
    val id: RevisionId,
    val noteId: NoteId,
    val parents: Set<RevisionId>,
    val timestamp: HybridTimestamp,
    val deviceId: DeviceId,
    val kind: RevisionKind,
    val content: RevisionContent,
) {
    init {
        require(id !in parents) { "Revision $id cannot be its own parent" }
        require(parents.isNotEmpty() || content is RevisionContent.Snapshot) {
            "The first revision of a note cannot be a tombstone"
        }
        when (kind) {
            RevisionKind.User -> {
                require(!id.isMergeHash) { "A user revision needs a UUID id, not $id" }
                require(parents.size <= 1) { "A user revision has at most one parent: $parents" }
            }

            is RevisionKind.AutomaticMerge -> {
                require(id.isMergeHash) { "An automatic merge needs a hash id, not $id" }
                require(parents.size == 2) { "An automatic merge has two parents: $parents" }
                kind.losingParent?.let { loser ->
                    require(loser in parents) { "Losing parent $loser is not in $parents" }
                    require(content is RevisionContent.Snapshot) {
                        "A conflicted merge must carry the winning text, not a tombstone"
                    }
                }
            }
        }
    }

    /** This revision's place in the total order of ADR-0003. */
    public val stamp: Stamp get() = Stamp(timestamp, deviceId, id)
}

/** Who made a [Revision]: a person, or the merge code. */
public sealed interface RevisionKind {
    /**
     * Anything a person does: an edit, a pin, a state change, a deletion or a conflict
     * resolution. How a resolution names the conflict it closes is settled in M3.
     */
    public data object User : RevisionKind

    /**
     * A deterministic merge of two concurrent heads, computed identically by every device that
     * merges them (ADR-0003).
     *
     * @property losingParent `null` for a clean merge. For a conflicted merge, the parent whose
     *   `body` and `title` lost; its text goes into the device's local conflict table.
     */
    public data class AutomaticMerge(
        val losingParent: RevisionId?,
    ) : RevisionKind {
        /** Whether the `body` and `title` of the two parents could not be merged. */
        public val isConflicted: Boolean get() = losingParent != null
    }
}

/** What a [Revision] says about its note: its full content, or that it was deleted forever. */
public sealed interface RevisionContent {
    /**
     * The complete content of the note as of the revision. Every revision carries the whole
     * note rather than a change, so a three-way merge can read its common ancestor directly.
     *
     * @property title The title, possibly empty.
     * @property body The Markdown body.
     * @property tags The tags, merged as an OR-set.
     * @property pinned Whether the note is pinned, merged last-writer-wins by its stamp.
     * @property state The note's state, merged last-writer-wins by its stamp.
     */
    public data class Snapshot(
        val title: String,
        val body: String,
        val tags: Set<TagName>,
        val pinned: Stamped<Boolean>,
        val state: Stamped<NoteState>,
    ) : RevisionContent

    /**
     * The note was deleted forever from the trash. The tombstone syncs like any other revision.
     * A concurrent edit beats it and brings the note back as [NoteState.TRASHED].
     */
    public data object Tombstone : RevisionContent
}
