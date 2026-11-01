# 0010. Compose UI stays outside the linuxX64 rule

- **Status:** Accepted
- **Date:** 2026-11-01

## Context

[ADR-0006](0006-native-compatible-shared-modules.md) says that every shared
library module builds and tests for `linuxX64`, and that a module which only
compiles for the JVM fails the build. Its purpose is to keep a native CLI cheap.
It names `core/*`, `backend/*` and `interop/*`, and it says that the apps stay
on the JVM, but it does not name `app/shared-ui`.

`app/shared-ui` is a library that two apps share: the Compose Multiplatform
screens and view models for Android and the desktop
([PLAN.md §3.2](../PLAN.md#32-module-layout-gradle)). It does not fit ADR-0006's
rule, because Compose Multiplatform has no `linuxX64` target. The desktop target
runs on the JVM only ([spike 0002](../spikes/0002-desktop-packaging.md)), so with
`jotter.kmp-library` the module cannot build at all.

## Decision

`app/shared-ui` builds for `jvm` and Android, not for `linuxX64`. This is an
exemption from ADR-0006 for this one module, for that one reason.

- The build gets a plugin without the target. `jotter.kmp-base` holds everything
  but `linuxX64`, and `jotter.kmp-library` is `jotter.kmp-base` plus `linuxX64`.
  Only a module with a dependency that has no `linuxX64` variant may apply
  `jotter.kmp-base` directly, and that needs an ADR like this one.
- The rule still protects what a native CLI needs. The dependency points one way:
  `app/shared-ui` depends on `core/*`, and nothing in `core/*`, `backend/*` or
  `interop/*` may depend on it. The CLI does not use it.
- So logic that the CLI could want does not belong in `app/shared-ui`, even if the
  UI is its first user. It goes into a `jotter.kmp-library` module.

ADR-0006 otherwise stands.

## Consequences

### Positive

- The shared UI can use Compose, as PLAN.md §3.4 chooses.
- The rule stays strict for every other module: a new library gets `linuxX64` by
  default, and leaving it out is a visible choice.

### Negative

- View models in `app/shared-ui` could quietly use JVM-only APIs in `commonMain`
  without the build noticing, until the Android target rejects them. Android
  compiles the same `commonMain`, which catches most of them.
- Its tests run on the JVM only, because the UI tests need Skiko. Android compiles
  and lints the module but runs no tests of it.
- One more convention plugin.

## Alternatives considered

- **View models in a `linuxX64` module and screens in `app/shared-ui`:** keeps the
  view models free of JVM-only code. But they hold Compose snapshot state, so a
  text field sees its value in the same frame, and the runtime would then need a
  `linuxX64` variant too. No app on `linuxX64` would use them.
- **An exemption inside `jotter.kmp-library`, by a flag or by module name:** one
  plugin, but the rule would then be easy to switch off in a build script without
  anyone reading this record.
- **`app/shared-ui` as a JVM-only module:** simpler now, but Android (M5) needs it,
  and it would have to become multiplatform then.
