# Releasing the Android app

How the maintainer makes a release that F-Droid can verify
([ADR-0019](adr/0019-release-builds.md)). Nothing here runs in CI: the signing key exists only
on the maintainer's machine.

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

1. **Raise the version** in `gradle.properties`: `jotter.versionName` to the new
   `MAJOR.MINOR.PATCH` and `jotter.versionCode` by one. Add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (at most 500 characters).
   Merge that to `main` and wait for its `reproducible` run to pass.
2. **Tag** the commit: `git tag -a v0.1.0 -m "Jotter 0.1.0" && git push origin v0.1.0`. The tag's `reproducible`
   run builds it twice again.
3. **Build and sign** from a clean checkout of the tag, with the password typed at a prompt
   rather than kept in the shell's history:

   ```
   git switch --detach v0.1.0 && git status --short   # must print nothing
   read -rs JOTTER_KEYSTORE_PASSWORD && export JOTTER_KEYSTORE_PASSWORD
   JOTTER_KEYSTORE=~/keys/jotter-release.p12 \
       ./gradlew --no-configuration-cache --no-build-cache clean :app:android:assembleRelease
   unset JOTTER_KEYSTORE_PASSWORD
   ```

   `--no-configuration-cache` keeps the password out of Gradle's configuration cache; with
   `JOTTER_KEYSTORE` set, the build refuses to run without it. Set
   `JOTTER_KEY_ALIAS` and `JOTTER_KEY_PASSWORD` if the key's alias is not `jotter` or its
   password differs from the store's. The signed APK is
   `app/android/build/outputs/apk/release/android-release.apk`.
4. **Check it** before publishing:

   ```
   apksigner verify --verbose --print-certs app/android/build/outputs/apk/release/android-release.apk
   ```

   It must say `Verifies`, with v2 and no v1 signature, and the certificate's SHA-256 digest must
   be the one in F-Droid's recipe (`AllowedAPKSigningKeys`). To check what F-Droid will check,
   build the same tag unsigned (`app/android/scripts/reproducible-build.sh v0.1.0` leaves it in
   `build/reproducible/a.apk`) and run [apksigcopier](https://github.com/obfusk/apksigcopier):

   ```
   apksigcopier compare android-release.apk --unsigned build/reproducible/a.apk
   ```

   It copies the signature onto the unsigned build and verifies it; silence and exit status 0
   mean F-Droid's build will match.
5. **Publish** a GitHub release for the tag with the APK attached as `jotter-0.1.0.apk`: the
   recipe's `Binaries` URL expects that name.

F-Droid's checker notices the tag, builds the commit without a signature, downloads the
`Binaries` APK, copies its signature onto its own build and publishes the result if it verifies.
If it does not, F-Droid publishes nothing for that version, and its build log says which file
differs: compare with `reproducible-build.sh` and diffoscope.

## Submitting to F-Droid

The first submission is a merge request to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) with
[docs/fdroid/io.github.sameerkrmishra.jotter.yml](fdroid/io.github.sameerkrmishra.jotter.yml),
after the steps in [docs/fdroid/README.md](fdroid/README.md).
