# 0019. Release builds: one version source, signing outside the repository, reproducible APKs

- **Status:** Proposed
- **Date:** 2026-11-04

## Context

M5 ships the Android app through F-Droid ([PLAN.md §8 and §10](../PLAN.md#10-milestones)).
F-Droid builds every app from source on its own server. It can then publish either its own
build, signed with F-Droid's key, or the developer's APK, if its build is the same APK without
the signature ("reproducible builds"). The second is better for users: the app has one
signature whether it came from F-Droid or from GitHub, so they can switch sources and update
without uninstalling. It only works if two builds of one commit, on different machines, at
different times, give the same bytes.

Three things decide that, and they are not obvious from the build files:

- **The version.** F-Droid finds new releases from git tags and reads `versionName` and
  `versionCode` from the source. Anything computed from the clock or the commit count makes
  each build different, and F-Droid's update checker cannot read it.
- **The signature.** A keystore or its password must never be in the repository, CI or F-Droid.
  Signing also adds things to the APK: Google's dependency metadata block, encrypted with
  Google's key, which F-Droid refuses; and, with an old `minSdk`, JAR signature files inside the
  zip, which change its contents.
- **The toolchain.** The APK depends on Gradle, the Android Gradle plugin (D8, aapt2 and its own
  zip writer), Kotlin and its Compose compiler, the libraries and the JDK.

## Decision

**The version lives in `gradle.properties`**, as two literals: `jotter.versionName`
(`MAJOR.MINOR.PATCH`) and `jotter.versionCode`. `app/android` and `app/desktop` read them; the
build fails if the name is not three numbers or the code is not positive. A release raises the
code by one and tags the commit `vMAJOR.MINOR.PATCH`. Nothing reads the clock, the commit or the
machine. Two literals rather than a code computed from the name, because F-Droid's update
checker reads each with a regular expression (`UpdateCheckData` in the
[draft recipe](../fdroid/io.github.sameerkrmishra.jotter.yml)) and cannot compute.

**Release builds are unsigned unless the maintainer's keystore is given**
(`app/android/build.gradle.kts`). With `JOTTER_KEYSTORE` set to a keystore's path, the release
APK is signed with it; the passwords come from `JOTTER_KEYSTORE_PASSWORD` and
`JOTTER_KEY_PASSWORD` (the store's password if unset), the alias from `JOTTER_KEY_ALIAS`
(`jotter` if unset). Without it, as in CI and on F-Droid's server, the APK is unsigned. With
`minSdk` 26, AGP signs with APK Signature Scheme v2 only, which sits in front of the zip's
central directory and leaves its entries alone, so the signed APK is the unsigned one plus a
signing block. [docs/releasing.md](../releasing.md) has the maintainer's steps.

**The release APK depends on the sources only.** `dependenciesInfo` is off (no Google metadata
block), and `vcsInfo` is off (no `META-INF/version-control-info.textproto`, which differs
between a clone and an exported tree). The release is not shrunk: R8 would need keep rules for
Ktor's and kotlinx's reflection and a release build tested on devices, which CI's device tests
(debug builds) are not; turning it on later needs only the same reproducibility check. The
baseline profile that AGP merges from the libraries (`assets/dexopt/baseline.prof`) is in the
APK and is deterministic; the `.dm` files beside the APK are not part of it.

**What is pinned**, and so the same on every machine: Gradle by the wrapper, with the
distribution's checksum; AGP, Kotlin, Compose and every library by the version catalog
(`gradle/libs.versions.toml`); the compile SDK by `compileSdk`. AGP brings its own D8, aapt2
(from Maven) and zip writer, and the SDK build-tools version it asks for is fixed per AGP
version, so pinning AGP pins them. The JDK is pinned to **21**, its major
version (`gradle/gradle-daemon-jvm.properties`, `jvmToolchain(21)`), not to a vendor or an
update: F-Droid uses Debian's OpenJDK, CI Temurin, and the check below builds with two different
JDK 21 builds to show that they give the same APK.

**The check.** `app/android/scripts/reproducible-build.sh` builds the unsigned release APK twice
from one commit, each from its own `git archive` copy in its own directory, without the build
cache, in different time zones, locales and umasks, minutes apart, and fails unless the two are
byte-identical (diffoscope, or `zipinfo` and a file comparison, shows a difference). CI's
`reproducible` workflow runs it with Temurin for one build and Zulu for the other on every push
to `main`, on tags, and on pull requests that change the build. On 2026-11-04 four builds of the
same sources (two local with Temurin 21.0.12 in a worktree and in exported trees, two in CI with
Temurin 21.0.13 and Zulu 21.0.13) gave the same APK, SHA-256 `06981c62…fdab0061`. The release
build needs no Kotlin/Native toolchain.

## Consequences

### Positive

- F-Droid can publish the maintainer's own signed APK, so users can move between F-Droid and
  GitHub releases.
- A change that makes the build non-deterministic fails on `main` (or on its pull request, if it
  touches the build), not when F-Droid's verification fails weeks later.
- No signing secret exists outside the maintainer's machine.

### Negative

- A release is two edits in `gradle.properties`, and a forgotten `versionCode` bump is only
  caught by Android refusing the update (or F-Droid's checker).
- The APK is about 16 MB, unshrunk.
- A change in the app's code alone does not run the check on its pull request, only after the
  merge; such changes are unlikely to break determinism, but can (for instance a new library).
- Signing with Gradle stores the passwords in Gradle's configuration cache (encrypted) unless
  the build runs with `--no-configuration-cache`, as docs/releasing.md says.
- The check runs on one OS (Linux, x86_64); F-Droid's build server is the same, a maintainer on
  macOS would have to try.

## Alternatives considered

- **`versionCode` from the commit count or the date:** differs between shallow and full clones
  or between days, and F-Droid cannot read it.
- **`versionCode` computed from `versionName`** (such as `MAJOR*10000 + MINOR*100 + PATCH`): one
  number fewer to edit, but F-Droid's checker would need the code written out anyway.
- **F-Droid signs with its own key:** simpler for the maintainer, but users of F-Droid's build
  and of GitHub's could not switch without uninstalling, and F-Droid's key is then the app's.
- **Signing with `apksigner` outside Gradle:** works as well (the result is the same kind of v2
  signature) and keeps passwords out of Gradle entirely; Gradle's `signingConfig` was chosen so
  that one command gives the release APK.
- **Pinning the JDK's vendor and update:** not needed (the check shows it), and F-Droid could not
  follow it.
- **Running the check on every pull request:** about twice the release build's time on each, for
  failures that almost only build changes cause.
