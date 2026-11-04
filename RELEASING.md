# Releasing

How the maintainer makes a release: the Android APK, which F-Droid can verify
([ADR-0019](docs/adr/0019-release-builds.md)), and the Linux AppImage with the `jotter` command
inside, plus the command alone as a zip ([ADR-0020](docs/adr/0020-linux-desktop-package.md)).

CI builds and checks everything except the APK's signature, and puts it on a **draft** release:
the `release` workflow (`.github/workflows/release.yml`), which runs only when a `v*` tag is
pushed or when started by hand. It never publishes. The signing key exists only on the
maintainer's machine, so signing and publishing are the maintainer's.

| File on the release | Made by | What it is |
|---|---|---|
| `Jotter-X.Y.Z-x86_64.AppImage` | CI | The desktop app and the CLI, with a Java runtime |
| `jotter-cli-X.Y.Z.zip` | CI | The CLI alone, for Java 21 or newer |
| `jotter-X.Y.Z-unsigned.apk` | CI | The release APK without a signature: Android will not install it; it is there to check builds against |
| `jotter-X.Y.Z.apk` | The maintainer | The signed APK, the one users install. F-Droid's recipe expects this name |
| `SHA256SUMS` | CI, then the maintainer | The SHA-256 of every file |
| `BUILDINFO.txt` | CI | The commit, runner image, JDK and squashfs-tools that built the files |

## Once: the signing key

Make a keystore outside the repository and keep a backup of it, with its password, somewhere
safe. A lost key means users must uninstall to update; a leaked one lets anyone ship updates.

```
keytool -genkeypair -keystore ~/keys/jotter-release.p12 -storetype PKCS12 \
    -alias jotter -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Jotter"
```

Keep it outside the working tree. `.gitignore` also ignores `*.jks`, `*.keystore`, `*.p12` and
`keystore.properties`, but not a keystore under another name. Nothing in the build reads a file
in the repository for it.

## Each release

The examples release 1.0.0; use the new version everywhere.

1. **Raise the version and write the changelog**, in one pull request:

   - `gradle.properties`: `jotter.versionName` to the new `MAJOR.MINOR.PATCH`, and
     `jotter.versionCode` up by one. Both apps read them; nothing else holds a version.
   - `CHANGELOG.md`: rename `## Unreleased` to `## 1.0.0 (YYYY-MM-DD)` and start a new, empty
     `## Unreleased` above it. The workflow copies that section into the release and fails
     without it.
   - `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: at most 500 characters,
     for F-Droid.

   Merge it to `main` and wait for its `CI` and `Reproducible` runs (both the `reproducible` and
   the `desktop` job) to pass.

2. **A dry run**, if anything in the build or the workflow changed since the last release. It
   builds and checks every file, uploads them as a workflow artifact and creates nothing:

   ```
   gh workflow run release.yml --ref main -f dry-run=true
   gh run watch "$(gh run list --workflow release.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
   gh run download --name jotter-1.0.0 --dir /tmp/jotter-dry-run
   ```

   The run warns, and does not fail, while `CHANGELOG.md` has no section for the version.

3. **Tag** the merged commit and push the tag:

   ```
   git switch main && git pull --ff-only
   git tag -a v1.0.0 -m "Jotter 1.0.0" && git push origin v1.0.0
   ```

   The tag must be `v` and `jotter.versionName`, or the workflow fails. It builds the files on
   `ubuntu-24.04`, starts the AppImage's CLI and window headless (`smoke-appimage.sh`, on Xvfb),
   and creates a draft release named "Jotter 1.0.0" with the files above. The tag's
   `Reproducible` run builds the APK and the AppImage twice again. Never move a pushed tag:
   F-Droid may already have seen it. For a mistake, release the next patch version.

4. **Build and sign the APK** from a clean checkout of the tag, with the password typed at a
   prompt rather than kept in the shell's history:

   ```
   git switch --detach v1.0.0 && git status --short   # must print nothing
   read -rs JOTTER_KEYSTORE_PASSWORD && export JOTTER_KEYSTORE_PASSWORD
   JOTTER_KEYSTORE=~/keys/jotter-release.p12 \
       ./gradlew --no-configuration-cache --no-build-cache clean :app:android:assembleRelease
   unset JOTTER_KEYSTORE_PASSWORD
   cp app/android/build/outputs/apk/release/android-release.apk /tmp/jotter-1.0.0.apk
   ```

   `--no-configuration-cache` keeps the password out of Gradle's configuration cache; with
   `JOTTER_KEYSTORE` set, the build refuses to run without it. Set `JOTTER_KEY_ALIAS` and
   `JOTTER_KEY_PASSWORD` if the key's alias is not `jotter` or its password differs from the
   store's.

5. **Check the signed APK** against CI's unsigned one:

   ```
   apksigner verify --verbose --print-certs /tmp/jotter-1.0.0.apk
   gh release download v1.0.0 --pattern 'jotter-1.0.0-unsigned.apk' --dir /tmp
   apksigcopier compare /tmp/jotter-1.0.0.apk --unsigned /tmp/jotter-1.0.0-unsigned.apk
   ```

   `apksigner` must say `Verifies`, with v2 and no v1 signature, and the certificate's SHA-256
   digest must be the one in F-Droid's recipe (`AllowedAPKSigningKeys`).
   [apksigcopier](https://github.com/obfusk/apksigcopier) copies the signature onto CI's build
   and verifies it: silence and exit status 0 mean that an APK built on another machine, as
   F-Droid's will be, matches the signed one. If it fails, compare the two with
   `app/android/scripts/reproducible-build.sh v1.0.0` and diffoscope before publishing anything.

6. **Check the draft release** on GitHub (Releases, "Draft"), or with `gh release view v1.0.0`:

   - the notes are the changelog's section, and the files are the CI rows of the table above;
   - the hashes hold, and the AppImage passes the smoke test here too (headless, temporary
     directories only):

     ```
     mkdir /tmp/jotter-1.0.0 && cd /tmp/jotter-1.0.0
     gh release download v1.0.0
     sha256sum -c SHA256SUMS
     ~/src/jotter/app/desktop/scripts/smoke-appimage.sh Jotter-1.0.0-x86_64.AppImage 1.0.0
     ```

   - `BUILDINFO.txt` names the tag's commit (`git rev-parse v1.0.0^{commit}`).

7. **Add the signed APK** and its hash, then **publish**:

   ```
   cd /tmp/jotter-1.0.0 && cp /tmp/jotter-1.0.0.apk .
   sha256sum jotter-1.0.0.apk >> SHA256SUMS && sha256sum -c SHA256SUMS
   gh release upload v1.0.0 jotter-1.0.0.apk
   gh release upload v1.0.0 SHA256SUMS --clobber
   gh release edit v1.0.0 --draft=false --latest
   ```

F-Droid's checker notices the tag, builds the commit without a signature, downloads the
`Binaries` APK (`jotter-1.0.0.apk` from the release), copies its signature onto its own build and
publishes the result if it verifies. If it does not, F-Droid publishes nothing for that version,
and its build log says which file differs: compare with `reproducible-build.sh` and diffoscope.

## Submitting to F-Droid

The first submission is a merge request to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) with
[docs/fdroid/io.github.sameerkrmishra.jotter.yml](docs/fdroid/io.github.sameerkrmishra.jotter.yml),
after the first published release and the steps in [docs/fdroid/README.md](docs/fdroid/README.md):
fill in the recipe's `commit` with the tag's commit and `AllowedAPKSigningKeys` with the
certificate digest from step 5, and run `fdroid lint` and `fdroid build`. Later releases need
nothing there: the recipe's `UpdateCheckMode: Tags` finds each new `vX.Y.Z` tag and reads the
version from `gradle.properties`.

## Verifying reproducibility

Each script builds one commit twice, from two `git archive` copies, in different time zones,
locales and umasks, and exits 0 only if the results are byte-identical. CI's `Reproducible`
workflow runs both on `main`, on tags and on pull requests that change the build.

```
ANDROID_HOME=~/Android/Sdk app/android/scripts/reproducible-build.sh v1.0.0
app/desktop/scripts/reproducible-build.sh v1.0.0      # needs mksquashfs
```

- **The APK** does not depend on the JDK's vendor or update (ADR-0019): any JDK 21 must give the
  hash of CI's `jotter-1.0.0-unsigned.apk`, and `apksigcopier compare` (step 5) checks the signed
  one.
- **The AppImage and the CLI zip** are deterministic, but the AppImage holds a JDK's own jlink
  image and mksquashfs's output, so the same bytes as CI's need the same JDK build and the same
  squashfs-tools version, both named in `BUILDINFO.txt` (ADR-0020). The CLI zip holds no JDK.
  To compare an AppImage built elsewhere with CI's regardless of
  mksquashfs, compare their contents:

  ```
  for f in a b; do
      unsquashfs -q -d "$f" -o "$(./$f.AppImage --appimage-offset)" "$f.AppImage"
  done
  diff -r a b      # only lib/runtime differs if the JDK build does
  ```
