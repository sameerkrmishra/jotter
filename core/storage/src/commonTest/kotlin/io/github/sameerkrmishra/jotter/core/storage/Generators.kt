package io.github.sameerkrmishra.jotter.core.storage

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
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.cyrillic
import io.kotest.property.arbitrary.egyptianHieroglyphs
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.katakana
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.printableAscii
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.whitespace
import kotlin.uuid.Uuid

/**
 * Uniformly random bytes, without Kotest's edge cases (such as all zeros), so that ids generated
 * from them are unique and tests can share one database across iterations.
 */
private fun randomBytes(size: Int): Arb<ByteArray> = arbitrary { it.random.nextBytes(size) }

val noteIds: Arb<NoteId> = randomBytes(Uuid.SIZE_BYTES).map { NoteId.parse(it.toHexString()) }

val deviceIds: Arb<DeviceId> = randomBytes(Uuid.SIZE_BYTES).map { DeviceId.parse(it.toHexString()) }

val userRevisionIds: Arb<RevisionId> =
    randomBytes(Uuid.SIZE_BYTES).map { RevisionId.parse(it.toHexString()) }

val mergeRevisionIds: Arb<RevisionId> =
    randomBytes(RevisionId.MERGE_HASH_SIZE).map { RevisionId.fromMergeHash(it) }

val revisionIds: Arb<RevisionId> = Arb.choice(userRevisionIds, mergeRevisionIds)

/** Any timestamp, including the extremes [HybridTimestamp.ZERO] and [HybridTimestamp.MAX]. */
val timestamps: Arb<HybridTimestamp> =
    Arb.bind(Arb.long(0..Long.MAX_VALUE), Arb.int(0..Int.MAX_VALUE), ::HybridTimestamp)

val stamps: Arb<Stamp> = Arb.bind(timestamps, deviceIds, revisionIds, ::Stamp)

/**
 * Characters that need care in storage: U+FEFF, which round-trips unless it starts the text, and
 * the ones that do not and become U+FFFD ([toStorableText]): NUL, U+FFFE, U+FFFF and unpaired
 * surrogates.
 */
private val awkwardCharacters: Arb<Codepoint> =
    Arb.element(0x0000, 0xFEFF, 0xFFFE, 0xFFFF, 0xD83D, 0xDE00).map(::Codepoint)

/**
 * Text as a person or a buggy program may hand it over: empty, ASCII (mixed case, punctuation,
 * whitespace), other scripts, characters outside the Basic Multilingual Plane (surrogate pairs)
 * and [awkwardCharacters].
 */
val rawTexts: Arb<String> =
    Arb.choice(
        Arb.constant(""),
        Arb.string(0..40, Codepoint.printableAscii()),
        Arb.string(
            0..40,
            Arb.choice(
                Codepoint.printableAscii(),
                Codepoint.whitespace(),
                Codepoint.cyrillic(),
                Codepoint.katakana(),
                Codepoint.egyptianHieroglyphs(),
                awkwardCharacters,
            ),
        ),
    )

/** [rawTexts] made storable, as [NoteService] stores them. */
val texts: Arb<String> = rawTexts.map { it.toStorableText() }

/** Valid tags, mixed case and non-ASCII among them. Parsing rejects the unstorable ones. */
val tagNames: Arb<TagName> =
    rawTexts.filter { TagName.parseOrNull(it) != null }.map(TagName::parse)

val snapshots: Arb<RevisionContent.Snapshot> =
    Arb.bind(
        texts,
        texts,
        Arb.set(tagNames, 0..4),
        Arb.bind(Arb.boolean(), stamps) { pinned, stamp -> Stamped(pinned, stamp) },
        Arb.bind(Arb.enum<NoteState>(), stamps) { state, stamp -> Stamped(state, stamp) },
    ) { title, body, tags, pinned, state ->
        RevisionContent.Snapshot(
            title,
            body,
            tags,
            pinned,
            state,
        )
    }

/**
 * Every shape of [Revision] that its constructor allows: user revisions with zero or one parent,
 * clean and conflicted automatic merges, snapshots and tombstones.
 */
val revisions: Arb<Revision> =
    arbitrary {
        val isMerge = Arb.boolean().bind()
        val id = (if (isMerge) mergeRevisionIds else userRevisionIds).bind()
        val parents =
            if (isMerge) {
                Arb.set(revisionIds, 2..2).bind()
            } else {
                Arb.set(revisionIds, 0..1).bind()
            }
        val kind =
            if (isMerge) {
                RevisionKind.AutomaticMerge(Arb.element(parents + null).bind())
            } else {
                RevisionKind.User
            }
        val isConflicted = kind is RevisionKind.AutomaticMerge && kind.isConflicted
        val canBeTombstone = parents.isNotEmpty() && !isConflicted
        val content =
            if (canBeTombstone && Arb.boolean().bind()) {
                RevisionContent.Tombstone
            } else {
                snapshots.bind()
            }
        Revision(id, noteIds.bind(), parents, timestamps.bind(), deviceIds.bind(), kind, content)
    }

val sampleDevice: DeviceId = DeviceId.parse("5f0c2a9e-1b3d-4e6f-8a7b-9c0d1e2f3a4b")
