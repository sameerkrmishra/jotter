# Contributing to Jotter

Thanks for your interest in Jotter. This document explains how the project is
built, what a good change looks like, and how changes are reviewed. The plan
in [docs/PLAN.md](docs/PLAN.md) is the source of truth for scope and design.

## Principles

- **Local-first.** The local database is the source of truth. Sync runs in the
  background and never blocks the user.
- **Dumb backends.** Backends only list, read and write files. All merge logic
  runs on the devices.
- **Append-only.** A device never modifies a sync file once written.
- **Deterministic merges.** The same inputs give the same result everywhere.
- **Dependencies point inwards.** UI → application services → domain. The
  domain has no I/O.
- **Readable over clever.** Code should be understandable in one pass.

The reasons behind these are recorded in [docs/adr/](docs/adr/README.md).

## Prerequisites

- **JDK.** The build provisions JDK 21 through Gradle toolchains. Any recent
  JDK that can start Gradle is enough to bootstrap it.
- **Android SDK** with platform 36 and build-tools 36.1.0 installed. Set
  `ANDROID_HOME` to the SDK location.
- **Gradle.** No system Gradle is needed. Always use the wrapper, `./gradlew`.

## Everyday commands

The exact task names are finalised by the build-skeleton task in milestone M0.
The intended interface is:

| Command | What it does |
|---|---|
| `./gradlew check` | Compiles, runs all tests, ktlint, detekt and the 95% Kover coverage gate |
| `./gradlew ktlintFormat` | Reformats Kotlin sources to the project style |

Run `./gradlew check` before every commit. CI runs the same checks, plus
Android Lint and the APK and desktop builds.

## Code style

- ktlint with the `ktlint_official` code style and a 100-column line limit.
  The settings live in [.editorconfig](.editorconfig).
- detekt with strict thresholds (complexity, long methods, magic numbers).
- KDoc on every public class, interface, function and property. Keep it
  accurate when code changes.
- Kotlin explicit API mode: public declarations state their visibility and
  return types. Keep internal details `internal`.
- No compiler or lint warnings. Android Lint warnings are errors.
- Each module has one job, a small public API and a `README.md`.

## Testing

- The coverage gate is 95% line and branch coverage. Generated code
  (SQLDelight) and thin platform entry points (Activity, `main()`) are
  excluded.
- Tests must be meaningful. They should check behaviour and edge cases, not
  just execute lines. Tests written only to raise coverage will be rejected in
  review.
- Sync and merge code needs property-based tests (Kotest): simulate several
  devices with random offline edits and check that they always converge.
- Compose UI is covered with Compose UI tests. Android code that needs the
  framework uses Robolectric.
- Never use real user data in tests or fixtures. Use synthetic data.

## Commit messages

- Subject line in the imperative mood ("Add tag filter", not "Added tag
  filter"), at most 72 characters, no trailing period.
- A blank line, then a body that explains *why* the change is needed and any
  trade-offs. The diff already shows *what* changed.
- One logical change per commit.

## Review process

Every commit is reviewed before it reaches `main`:

1. The author makes sure `./gradlew check` passes locally.
2. A different reviewer, either a reviewer agent with fresh context or a
   human, reviews the commit. Nobody reviews their own work.
3. If changes are requested, the author fixes them and a *fresh* reviewer
   reviews the fixes, so no reviewer anchors on an earlier opinion.
4. Once approved, the change goes to `main` through a pull request. A pull
   request can only be merged when CI is green.

Reviewers check: correctness and edge cases; readability; naming; fit with the
module boundaries and ADRs; meaningful tests; present and accurate KDoc; no
dead code and no premature abstraction.

## Proposing an architectural change

Changes to architecture, module boundaries, data formats, the sync protocol or
major dependencies need an ADR. Copy
[docs/adr/0000-template.md](docs/adr/0000-template.md), fill it in with the
context, decision, consequences and alternatives, and open a pull request with
status Proposed. If the change replaces an accepted decision, the new ADR
supersedes the old one. See [docs/adr/README.md](docs/adr/README.md).

## Licensing of contributions

Jotter is licensed under the [Apache License 2.0](LICENSE). By submitting a
contribution you agree that it is licensed under the same terms
(inbound = outbound, as described in section 5 of the license). Do not remove
or alter the [NOTICE](NOTICE) file, and keep existing copyright and license
notices intact. Only contribute code you have the right to license this way.
