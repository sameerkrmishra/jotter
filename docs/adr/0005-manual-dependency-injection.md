# 0005. Manual constructor injection with one composition root per app

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

Jotter has three entry points (Android, desktop, CLI) and a peer server, all
sharing core modules ([PLAN.md §3.2](../PLAN.md#32-module-layout-gradle)).
Classes such as `NoteService`, `SyncEngine` and `BackendRegistry` need their
collaborators supplied from outside so they can be tested with fakes. The
object graph is small: a database, a few repositories and services, a
registry of backends, and a logger.

Readability is a core goal. A reviewer should be able to see where an object
comes from by reading code, without knowing a framework.

## Decision

Use plain constructor injection. Classes take their dependencies as
constructor parameters and do not look them up.

Each app has exactly one **composition root**: a single place, close to
`main()` or the Android `Application`, that builds the object graph by calling
constructors. Shared wiring that every app needs may live in a plain Kotlin
factory function in a core module. No DI framework, annotations or service
locator is used.

We will revisit this decision if the graph grows enough that the composition
roots become hard to read or maintain.

## Consequences

### Positive

- No magic. Where an object comes from is visible in ordinary code, and
  "go to definition" works.
- Missing dependencies are compile errors, not runtime failures.
- Tests build objects with fakes by calling constructors directly.
- No extra dependency, annotation processor or code generation, which keeps
  builds simpler and suits reproducible F-Droid builds.
- Nothing new for contributors to learn.

### Negative

- Composition roots are hand-written and grow with the graph. Adding a
  dependency deep in the graph means updating every caller up to the root.
- Lifecycle and scoping (for example per-screen view models on Android) must
  be handled explicitly instead of by framework scopes.
- Some wiring will be repeated across the three apps unless factored into
  shared factory functions.

## Alternatives considered

- **Koin:** popular in KMP and easy to start with, but resolves dependencies
  at runtime by default, so wiring mistakes tend to show up as crashes, and
  the lookup DSL hides the graph.
- **Hilt (Dagger):** compile-time safe, but Android-only, annotation-heavy and
  adds generated code that is hard to read.
- **kotlin-inject:** compile-time and multiplatform, but adds KSP code
  generation and annotations for a graph small enough to wire by hand.
