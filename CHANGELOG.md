# Changelog

What changed in each release, for the people who use Jotter. The release workflow copies a
version's section into its GitHub release, and fails without one ([RELEASING.md](RELEASING.md)).
A section starts with `## MAJOR.MINOR.PATCH (YYYY-MM-DD)`. The Android app's own, shorter,
changelog for F-Droid is in `fastlane/metadata/android/en-US/changelogs/`.

## Unreleased

- Linux: the desktop app as an AppImage, with its own Java runtime and the `jotter` command
  inside.
- Android: the app, for Android 8.0 and later, with F-Droid metadata and a reproducible build.
- Sync through a folder (such as a USB stick), a WebDAV server, or the PC as a hub over Tailscale.
- Markdown notes with tags, pinning, archive, trash and full-text search; import from Google Keep
  and export to Markdown.
