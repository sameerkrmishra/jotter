#!/usr/bin/env python3
"""Checks that the tests can fail: applies one source mutation at a time and runs them.

Run it from the repository root, on a clean tree, in chunks. It stops by itself between two
mutations once its budget is used, and prints where to go on. See core/sync/README.md.

    python3 core/sync/scripts/mutants.py --list
    python3 core/sync/scripts/mutants.py --first 0 --budget-minutes 100
    python3 core/sync/scripts/mutants.py "own folder not read"

Results go to build/mutants.out, one line per mutation:

    KILLED | name | sim [tests] [first seeds] | unit count [tests]

A mutation that cannot be applied is NOT-APPLIED, which means this list is out of date. Each file
is restored after its run, also on SIGTERM and SIGINT, and the tree is checked at the end.
"""
import argparse
import glob
import os
import re
import signal
import subprocess
import sys
import time

G = "RevisionGraph.kt"
P = "Puller.kt"
E = "SyncEngine.kt"
S = "SyncStore.kt"
C = "SegmentCodec.kt"
R = "ConflictResolver.kt"
U = "Push.kt"
LIVE = "heads.filterNot { it.id in settled || isCovered(it) }"
OPENF = "conflicted.filter { it.id !in deleted || it.id in withText }"
STAMP = ("revision.timestamp != HybridTimestamp.MAX &&\n"
         "            parents.all { revision.timestamp > it.timestamp }")
BAD = ("Verdict.INVALID in parentVerdicts ||\n            revision.id in refused ||\n"
       "            revision.parents.any { it in refused }")
LOSER = "(losingParent == null || (losingParent == lower.id && parents.all(::hasText)))"
HASH_CHECK = ('            if (expectedHash != null && expectedHash != contentHash(json)) {\n'
              '                throw SegmentFormatException('
              '"The content does not match the hash in its name")\n'
              '            }\n')
FORMAT_CHECK = ("            requireKnownFormat("
                "headerJson.decodeFromString<SegmentHeaderDto>(json).format)\n")

M = [
    ("no covered heads", G, LIVE, "heads.filterNot { it.id in settled }"),
    ("settled head still counts", G, LIVE, "heads.filterNot { isCovered(it) }"),
    ("twin text rule gone", G,
     "if (headHasText == otherHasText) other.id < head.id else otherHasText", "other.id < head.id"),
    ("base walks through tombstones", G,
     "reachableFrom(listOf(id), throughTombstones = false)", "reachableFrom(listOf(id))"),
    ("base is the lowest stamp", G,
     "bestCommonAncestors(a, b).maxByOrNull { it.stamp }",
     "bestCommonAncestors(a, b).minByOrNull { it.stamp }"),
    ("tags walk through tombstones", "NoteMerge.kt",
     "(graph.textAncestors(side.id) - graph.textAncestors(other.id))",
     "(graph.ancestors(side.id) - graph.ancestors(other.id))"),
    ("merge without what it reads", G,
     "return reachesNoGap(a.id, common) && reachesNoGap(b.id, common)", "return true"),
    ("whole graph must be complete", "Reconcile.kt",
     "!graph.isCoveredBy(head, current) && graph.canMerge(current, head)",
     "!graph.isCoveredBy(head, current) && graph.isComplete"),
    ("no pause on open conflict", "Reconcile.kt",
     "if (start.openConflicts.isNotEmpty()) return emptyList()", ""),
    ("tombstone closes beside an edit", G, OPENF, "conflicted.filter { it.id !in deleted }"),
    ("tombstone never closes", G, OPENF, "conflicted"),
    ("bad stamp counts", G, STAMP, "revision.timestamp != HybridTimestamp.MAX"),
    ("highest timestamp counts", G, STAMP, "parents.all { revision.timestamp > it.timestamp }"),
    ("child of invalid counts", G, BAD,
     "revision.id in refused || revision.parents.any { it in refused }"),
    ("foreign parent counts", G, BAD,
     "Verdict.INVALID in parentVerdicts || revision.id in refused"),
    ("collided id counts", G, BAD,
     "Verdict.INVALID in parentVerdicts || revision.parents.any { it in refused }"),
    ("collided ids not refused", "Reconcile.kt", "foreign + store.collidedIds()", "foreign"),
    ("merge counts without parents", G,
     "here.size < revision.parents.size -> Verdict.NOT_YET",
     "here.size < revision.parents.size -> Verdict.VALID"),
    ("forged merge counts", G,
     "return if (followsParents) Verdict.VALID else Verdict.INVALID", "return Verdict.VALID"),
    ("wrong loser counts", G, LOSER, "true"),
    ("tombstone loser counts", G, LOSER, "(losingParent == null || losingParent == lower.id)"),
    ("invalid not listed", "Reconcile.kt", "graph.invalidEnds.map(::invalidRow)", "emptyList()"),
    ("collided copy listed as bad history", G,
     "it.id !in built && it.id !in refused && it.content is RevisionContent.Snapshot",
     "it.id !in built && it.content is RevisionContent.Snapshot"),
    ("collision: stored copy not listed", P, "                listCollision(stored)\n", ""),
    ("collision: new copy not listed", P, "                listCollision(revision)\n", ""),
    ("collision: stored note untouched", P, "                touched += stored.noteId\n", ""),
    ("malformed text dropped", C, "dto.toDropped()?.let { dropped += it }", ""),
    ("merge hash is not checked", C,
     'require(mergeRevisionId(revision) == revision.id) { "Merge $id does not match its hash" }',
     "mergeRevisionId(revision)"),
    ("content hash is not checked", C,
     "if (expectedHash != null && expectedHash != contentHash(json)) {", "if (false) {"),
    ("hash not passed to decode", P,
     "seams.codec.decode(bytes, file.hash)", "seams.codec.decode(bytes)"),
    ("hash checked before format", C, FORMAT_CHECK + HASH_CHECK, HASH_CHECK + FORMAT_CHECK),
    ("read tracked by number", P,
     "files.filter { (device to it.name) !in read }",
     "files.filter { f -> read.none { it.first == device "
     "&& it.second.take(12) == f.name.take(12) } }"),
    ("own folder not read", E, "puller.pull(folders)", "puller.pull(folders - store.deviceId)"),
    ("read not recorded", P, "store.recordRead(backendId, item.device, item.file.name)", ""),
    ("own file not recorded as read", U, "            store.recordRead(backendId, device, name)\n", ""),
    ("revisions not tied to their file", P,
     "        store.markOnBackend(backendId, revision.id, item.device, item.file.name)\n", ""),
    ("hole not reported", P, "if (number > last + 1) {", "if (false) {"),
    ("lost segment not repaired", U, "!it.isPublished || it.name !in names", "!it.isPublished"),
    ("existing file is not compared", U,
     "if (!holdsSegment(path, seams.codec.hash(segment))) {", "if (false) {"),
    ("existing file is never sound", U,
     "if (!holdsSegment(path, seams.codec.hash(segment))) {", "if (true) {"),
    ("existing file: hash not given", U,
     "seams.codec.decode(blobs.read(path), hash)", "seams.codec.decode(blobs.read(path))"),
    ("existing path without a file fails", U,
     "        } catch (_: BlobNotFoundException) {\n            false\n", ""),
    ("given-up number is used again", U, "                highestSeen = maxOf(highestSeen, number)\n", ""),
    ("given-up segment keeps its revisions", S,
     "            queries.deleteRevisionsOfSegment(backend, number)\n", ""),
    ("number ignores the folder", U,
     "val number = maxOf(written, highestSeen) + 1", "val number = written + 1"),
    ("list failure reads as empty", E,
     "val files = blobs.list(BackendLayout.DEVICES)",
     "val files = try { blobs.list(BackendLayout.DEVICES) } "
     "catch (_: BlobStoreException) { emptyList() }"),
    ("no reconcile of untouched notes", P,
     "(touched + store.notesWithSeveralHeads()).forEach { noteId ->", "touched.forEach { noteId ->"),
    ("late parent does not touch child", P,
     "store.childrenOf(revision.id).mapNotNullTo(touched) { store.revision(it)?.noteId }", ""),
    ("merge picks text by argument order", "NoteMerge.kt",
     "val higher = if (a.stamp > b.stamp) first else second", "val higher = first"),
    ("edit does not beat deletion", "NoteMerge.kt",
     "SideMerge(first.backInTrash(b), losingParent = null)",
     "SideMerge(RevisionContent.Tombstone, losingParent = null)"),
    ("tags are a plain union", "NoteMerge.kt",
     "onlyFirst.filter { it !in base || it in addedFirst } +", "onlyFirst +"),
    ("merge may pass the cap", "NoteMerge.kt",
     "title == null || body == null || title.length + body.length > MAX_NOTE_CHARS",
     "title == null || body == null"),
    ("cap not enforced", "Text.kt",
     "if (chars > MAX_NOTE_CHARS) throw NoteTooLargeException(chars)", ""),
    ("lost source not re-published", E, "                forgetLostFiles(target.id, folders)\n", ""),
    ("lost file keeps its revisions", S,
     "        queries.deleteRevisionsReadFrom(backend, device.toString(), name)\n", ""),
    ("lost file stays read", S,
     "        queries.deleteRead(backend, device.toString(), name)\n", ""),
    ("forget keeps what was read", S, "            queries.deleteBackendReads(backend)\n", ""),
    ("forget keeps own segments", S, "            queries.deleteBackendSegments(backend)\n", ""),
    ("forget keeps backend revisions", S, "            queries.deleteBackendRevisions(backend)\n", ""),
    ("hidden heads not recorded", "Reconcile.kt",
     "graph.hiddenHeads.map { hiddenHead(graph, it) }", "emptyList()"),
    ("hidden beside conflict settled", R,
     "                throw HiddenHeadWaitsForConflictException(headId)\n",
     "                println(headId)\n"),
    ("settling builds on the hidden head", R,
     'checkNotNull(graph.shownHead()) { "A hidden head has a shown one" }',
     "checkNotNull(graph[headId])"),
    ("KeepLosing keeps the shown text", R,
     "shown.copy(title = losing.title, body = losing.body)", "shown"),
    ("head of a conflict can be a tombstone", R,
     "headsDescendingFrom(mergeId).first { it.content is RevisionContent.Snapshot }",
     "headsDescendingFrom(mergeId).first()"),
    ("edited resolution has no cap", R,
     "                    requireNoteSize(resolution.title, resolution.body)\n", ""),
    ("KeepBoth drops the text", R,
     "store.notes.create(losing.title, losing.body, shown.tags)", "losing.title"),
    ("conflict row not written", "Reconcile.kt",
     "graph.openConflicts.mapNotNull { openConflict(graph, it) }", "emptyList()"),
    ("resolver ignores refused ids", R,
     "storedGraph(store.sync, conflict.noteId)",
     "RevisionGraph(store.sync.revisions(conflict.noteId))"),
    ("answer on an older shown version written", R,
     "        if (head.id == expectedHead) {\n", "        if (true) {\n"),
    ("answer keeps its draft", R, "        store.sync.deleteDraft(closed)\n", ""),
    ("saving set-aside text twice makes two notes", R,
     "            if (store.sync.isDismissed(text)) {\n", "            if (false) {\n"),
    ("dismissed text still listed", S,
     "queries.selectShownInvalidRevisions(::invalidOfRow).executeAsList()",
     "queries.selectInvalidRevisions(::invalidOfRow).executeAsList()"),
]

RESULTS = ("core/sync/build/test-results/jvmTest/"
           "TEST-io.github.sameerkrmishra.jotter.core.sync.ConvergenceTest.xml")
PER_MUTANT_SECONDS = 900


class Stopped(Exception):
    """A signal came: restore the file and leave."""


def stop(_signal, _frame):
    raise Stopped()


def source(name):
    return glob.glob("core/*/src/commonMain/**/" + name, recursive=True)[0]


def is_clean():
    state = subprocess.run(["git", "status", "--porcelain", "--", "core"],
                           capture_output=True, text=True, check=True)
    return not state.stdout.strip()


def first_seeds():
    try:
        with open(RESULTS) as report:
            return sorted(set(re.findall(r"Seed (\d+)", report.read())), key=int)[:3]
    except OSError:
        return []


def run_one(entry, env, out):
    name, file, old, new = entry
    path = source(file)
    with open(path) as original_file:
        original = original_file.read()
    if old not in original:
        print("NOT-APPLIED |", name, file=out, flush=True)
        return
    try:
        with open(path, "w") as mutated:
            mutated.write(original.replace(old, new))
        run = subprocess.run(
            ["./gradlew", "--console=plain", "--continue", "--no-configuration-cache",
             "core:sync:jvmTest", "core:storage:jvmTest"],
            env=env, capture_output=True, text=True, timeout=PER_MUTANT_SECONDS)
        text = run.stdout + run.stderr
        failed = sorted(set(re.findall(r"^(\w+)\[jvm\] > (\w+)\[jvm\] FAILED", text, re.M)))
        sim = [test for owner, test in failed if owner == "ConvergenceTest"]
        rest = [test for owner, test in failed if owner not in ("ConvergenceTest", "PinnedRunTest")]
        if re.search(r"^e: file", text, re.M):
            status = "COMPILE-ERROR"
        else:
            status = "KILLED" if failed else "SURVIVED"
        seeds = first_seeds() if sim else []
        print(status, "|", name, "| sim", sim, seeds, "| unit", len(rest), rest[:2],
              file=out, flush=True)
    except subprocess.TimeoutExpired:
        print("KILLED-TIMEOUT |", name, file=out, flush=True)
    finally:
        with open(path, "w") as restored:
            restored.write(original)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("names", nargs="*", help="run only these")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--first", type=int, default=0, help="index to start from")
    parser.add_argument("--budget-minutes", type=float, default=100)
    parser.add_argument("--cases", default="3000", help="simulator seeds per mutation")
    args = parser.parse_args()
    if args.list:
        for index, entry in enumerate(M):
            print(index, entry[0])
        return 0
    if not is_clean():
        print("core/ has changes: commit them first, so that a mutation cannot hide in them")
        return 1
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    env = dict(os.environ, JOTTER_SIM_CASES=args.cases)
    env.setdefault("ANDROID_HOME", os.path.expanduser("~/Android/Sdk"))
    deadline = time.monotonic() + args.budget_minutes * 60
    chosen = [(i, e) for i, e in enumerate(M)
              if (e[0] in args.names if args.names else i >= args.first)]
    with open("build/mutants.out", "a") as out:
        print("START at", chosen[0][0] if chosen else "-", "cases", args.cases, file=out, flush=True)
        try:
            for index, entry in chosen:
                if time.monotonic() + PER_MUTANT_SECONDS > deadline:
                    print("STOPPED: go on with --first", index, file=out, flush=True)
                    return 0
                run_one(entry, env, out)
            print("DONE", len(M), "mutations in the list", file=out, flush=True)
        except Stopped:
            print("STOPPED by a signal: rerun the last one", file=out, flush=True)
            return 1
        finally:
            print("TREE", "clean" if is_clean() else "DIRTY", file=out, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
