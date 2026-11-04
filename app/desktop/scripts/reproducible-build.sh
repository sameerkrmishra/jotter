#!/usr/bin/env bash
# Builds the AppImage and the CLI's zip twice from one commit and fails unless each pair is
# byte-identical (docs/adr/0020-linux-desktop-package.md). The desktop counterpart of
# app/android/scripts/reproducible-build.sh, and built the same way:
#
#   app/desktop/scripts/reproducible-build.sh [REV]
#
# REV defaults to HEAD; uncommitted changes are not built. Each build gets its own `git archive`
# copy, its own Gradle project cache and no build cache; the second a new, empty Gradle user home
# that reads only downloaded dependencies from the caller's, and shares its JDKs. The two differ in
# time zone, locale and umask, and run at different times.
#
# Unlike the APK's check, both builds use the same JDK: the AppImage bundles the JDK's own jlink
# image, so another JDK build gives another AppImage by design. Both use the same mksquashfs too.
#
# Environment:
#   JOTTER_REPRO_DIR        where the copies, logs and packages go (default: build/reproducible-desktop
#                           in the repository; removed and made again on each run)
#   MKSQUASHFS              as for appimage.sh
#   JOTTER_APPIMAGE_RUNTIME as for appimage.sh; otherwise it is downloaded once for both builds
#
# On a difference it unpacks both AppImages with unsquashfs, if installed, and lists the files that
# differ. Exit status: 0 identical, 1 different, 2 a build failed.

set -euo pipefail

root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
rev=$(git -C "$root" rev-parse --verify "${1:-HEAD}^{commit}")
out=${JOTTER_REPRO_DIR:-$root/build/reproducible-desktop}
caller_home=${GRADLE_USER_HOME:-$HOME/.gradle}
version=$(git -C "$root" show "$rev:gradle.properties" | sed -n 's/^jotter\.versionName=//p')
image=Jotter-$version-x86_64.AppImage
zip_path=app/cli/build/distributions/jotter.zip

rm -rf "$out"
mkdir -p "$out"
echo "Building $rev twice in $out"

# Both builds read the caller's copy of the AppImage runtime, if it has one; otherwise each
# downloads it. appimage.sh checks its hash either way.
if [[ -z ${JOTTER_APPIMAGE_RUNTIME:-} ]]; then
    release=$(sed -n 's/^runtime_release=//p' "$root/app/desktop/scripts/appimage.sh")
    if [[ -f $root/build/appimage-runtime/runtime-x86_64-$release ]]; then
        export JOTTER_APPIMAGE_RUNTIME=$root/build/appimage-runtime/runtime-x86_64-$release
    fi
fi

fresh_home() {
    local home=$1 dir
    mkdir -p "$home"
    for dir in wrapper jdks; do
        if [[ -d "$caller_home/$dir" ]]; then ln -s "$caller_home/$dir" "$home/$dir"; fi
    done
}

# build NAME TZ LOCALE UMASK [GRADLE_HOME]
build() {
    local name=$1 tz=$2 locale=$3 mask=$4 home=${5:-}
    local src=$out/$name/jotter
    mkdir -p "$src"
    git -C "$root" archive "$rev" | tar -x -C "$src"
    echo "[$name] $(date -u +%FT%TZ) TZ=$tz LC_ALL=$locale umask $mask Gradle home ${home:-$caller_home}"
    if ! (
        cd "$src"
        umask "$mask"
        export TZ=$tz LC_ALL=$locale
        if [[ -n "$home" ]]; then
            export GRADLE_USER_HOME=$home GRADLE_RO_DEP_CACHE=$caller_home/caches
        fi
        export JOTTER_GRADLE_ARGS="--no-daemon --no-build-cache --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process"
        # shellcheck disable=SC2086
        ./gradlew $JOTTER_GRADLE_ARGS :app:cli:distZip
        app/desktop/scripts/appimage.sh "$src/build/dist"
    ) >"$out/$name.log" 2>&1; then
        echo "[$name] build failed, see $out/$name.log; its end:" >&2
        tail -n 40 "$out/$name.log" >&2
        exit 2
    fi
    # Each build must have made its own package, in its own copy: once, appimage.sh found the
    # enclosing checkout through git and packed that twice, which compared equal by construction.
    if [[ ! -d $src/app/desktop/build/appimage/Jotter.AppDir || ! -f $src/build/dist/$image ]]; then
        echo "[$name] the copy in $src did not build its own AppImage" >&2
        exit 2
    fi
    cp "$src/build/dist/$image" "$out/$name.AppImage"
    cp "$src/$zip_path" "$out/$name-cli.zip"
    echo "[$name] $(sha256sum "$out/$name.AppImage" | cut -d' ' -f1)  AppImage"
    echo "[$name] $(sha256sum "$out/$name-cli.zip" | cut -d' ' -f1)  CLI zip"
}

build a UTC C.UTF-8 022
# The second build starts in a later minute than the first ended, so that a time that leaks into
# the package (such as a zip entry's, at two-second resolution) cannot match by chance.
sleep $((61 - $(date +%-S)))
fresh_home "$out/b/gradle-home"
build b Pacific/Kiritimati POSIX 077 "$out/b/gradle-home"

status=0
if cmp -s "$out/a-cli.zip" "$out/b-cli.zip"; then
    echo "Identical CLI zip: $(sha256sum "$out/a-cli.zip" | cut -d' ' -f1) ($(stat -c %s "$out/a-cli.zip") bytes)"
else
    echo "The two CLI zips differ" >&2
    diff <(zipinfo -v "$out/a-cli.zip" | sed 1,2d) <(zipinfo -v "$out/b-cli.zip" | sed 1,2d) | head -50 >&2 || true
    status=1
fi

if cmp -s "$out/a.AppImage" "$out/b.AppImage"; then
    echo "Identical AppImage: $(sha256sum "$out/a.AppImage" | cut -d' ' -f1) ($(stat -c %s "$out/a.AppImage") bytes)"
else
    echo "The two AppImages differ" >&2
    unsquashfs=${MKSQUASHFS:+$(dirname "$MKSQUASHFS")/unsquashfs}
    unsquashfs=${unsquashfs:-unsquashfs}
    if command -v "$unsquashfs" >/dev/null; then
        for name in a b; do
            # The runtime prints where the image starts, without mounting it.
            offset=$("$out/$name.AppImage" --appimage-offset)
            "$unsquashfs" -q -n -o "$offset" -d "$out/$name-unpacked" "$out/$name.AppImage" >/dev/null
        done
        diff -rq "$out/a-unpacked" "$out/b-unpacked" >&2 || true
        # Modes and types, which diff -r does not compare.
        diff <(cd "$out/a-unpacked" && find . -printf '%m %y %p\n' | sort -k3) \
            <(cd "$out/b-unpacked" && find . -printf '%m %y %p\n' | sort -k3) >&2 || true
    fi
    status=1
fi
exit $status
