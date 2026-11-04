#!/usr/bin/env bash
# Builds the desktop app's AppImage, with the CLI inside (docs/adr/0020-linux-desktop-package.md).
#
#   app/desktop/scripts/appimage.sh [OUT_DIR]
#
# Writes OUT_DIR/Jotter-VERSION-x86_64.AppImage (default OUT_DIR: build/dist in the repository),
# VERSION being jotter.versionName in gradle.properties. Gradle assembles the AppDir
# (:app:desktop:appImageDir); this script packs it with mksquashfs and puts the AppImage runtime in
# front of it. That is all appimagetool would do, without its own, unpinned, copy of both.
#
# Needs mksquashfs (squashfs-tools 4.6 or newer, with zstd) on PATH, or in MKSQUASHFS. The runtime,
# AppImage's type2-runtime at a fixed release, is downloaded once into build/appimage-runtime and
# checked against its SHA-256; JOTTER_APPIMAGE_RUNTIME names a copy to use instead (offline), which
# must have the same hash. JOTTER_GRADLE_ARGS adds arguments to the Gradle run, split at spaces.
#
# Deterministic: the same commit, the same JDK 21 build (the toolchain Gradle uses, whose jlink
# image the AppImage bundles) and the same squashfs-tools version give the same bytes. The image's
# file times are all SOURCE_DATE_EPOCH (default 0), owners root, modes normalized by Gradle, and
# entries sorted by mksquashfs.

set -euo pipefail

runtime_release=20251108
runtime_sha256=2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d
runtime_url=https://github.com/AppImage/type2-runtime/releases/download/$runtime_release/runtime-x86_64

root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null) ||
    root=$(cd -- "$(dirname "$0")/../../.." && pwd -P)
out=${1:-$root/build/dist}
version=$(sed -n 's/^jotter\.versionName=//p' "$root/gradle.properties")
mksquashfs=${MKSQUASHFS:-mksquashfs}
appdir=$root/app/desktop/build/appimage/Jotter.AppDir
epoch=${SOURCE_DATE_EPOCH:-0}

if ! command -v "$mksquashfs" >/dev/null; then
    echo "mksquashfs not found: install squashfs-tools, or set MKSQUASHFS" >&2
    exit 2
fi

# The runtime.
runtime=${JOTTER_APPIMAGE_RUNTIME:-$root/build/appimage-runtime/runtime-x86_64-$runtime_release}
if [[ ! -f $runtime ]]; then
    mkdir -p "$(dirname "$runtime")"
    curl -fsSL --retry 3 -o "$runtime.part" "$runtime_url"
    mv "$runtime.part" "$runtime"
fi
if ! echo "$runtime_sha256  $runtime" | sha256sum -c --status; then
    echo "$runtime is not type2-runtime $runtime_release (SHA-256 differs)" >&2
    exit 1
fi

# shellcheck disable=SC2086
(cd "$root" && ./gradlew --quiet ${JOTTER_GRADLE_ARGS:-} :app:desktop:appImageDir)

mkdir -p "$out"
image=$out/Jotter-$version-x86_64.AppImage
squashfs=$out/.Jotter.squashfs
rm -f "$image" "$squashfs"
"$mksquashfs" "$appdir" "$squashfs" -quiet -no-progress -noappend \
    -comp zstd -Xcompression-level 19 -b 1M \
    -all-root -no-xattrs -mkfs-time "$epoch" -all-time "$epoch" >/dev/null
cat "$runtime" "$squashfs" >"$image"
rm -f "$squashfs"
chmod 755 "$image"
echo "$image"
