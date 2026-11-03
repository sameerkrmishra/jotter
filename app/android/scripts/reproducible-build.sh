#!/usr/bin/env bash
# Builds the unsigned release APK twice from one commit and fails unless both are byte-identical
# (docs/adr/0019-release-builds.md).
#
#   app/android/scripts/reproducible-build.sh [REV]
#
# REV defaults to HEAD; uncommitted changes are not built. Each build gets its own copy of the
# sources from `git archive`, in its own directory, with its own Gradle project cache and no
# build cache, so the second build cannot reuse anything from the first. The two builds also
# differ in time zone, locale and umask, and run at different times. Both use the Gradle user
# home and Android SDK of the caller (dependencies, not outputs).
#
# Environment:
#   ANDROID_HOME       the Android SDK (required, as for any build)
#   JOTTER_REPRO_DIR   where the copies, logs and APKs go (default: build/reproducible in the
#                      repository; removed and made again on each run)
#   JOTTER_JDK_A, JOTTER_JDK_B
#                      a JDK 21 for each build (default: the one Gradle chooses). Setting
#                      them to two different JDK 21 builds checks that the JDK's vendor and
#                      update do not change the APK.
#
# On a difference it runs diffoscope if it is installed, otherwise it lists the entries whose
# contents or zip metadata differ. Exit status: 0 identical, 1 different, 2 a build failed.

set -euo pipefail

if [[ -z "${ANDROID_HOME:-}" ]]; then
    echo "ANDROID_HOME must point to the Android SDK" >&2
    exit 2
fi

root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
rev=$(git -C "$root" rev-parse --verify "${1:-HEAD}^{commit}")
out=${JOTTER_REPRO_DIR:-$root/build/reproducible}
apk_path=app/android/build/outputs/apk/release/android-release-unsigned.apk

rm -rf "$out"
mkdir -p "$out"
echo "Building $rev twice in $out"

# build NAME TZ LOCALE UMASK JDK
build() {
    local name=$1 tz=$2 locale=$3 mask=$4 jdk=$5
    local src=$out/$name/jotter
    mkdir -p "$src"
    git -C "$root" archive "$rev" | tar -x -C "$src"
    local -a jdk_args=()
    if [[ -n "$jdk" ]]; then
        # Only this JDK may run the daemon and compile.
        jdk_args=(
            -Porg.gradle.java.installations.auto-detect=false
            -Porg.gradle.java.installations.auto-download=false
            "-Porg.gradle.java.installations.paths=$jdk"
        )
    fi
    echo "[$name] $(date -u +%FT%TZ) TZ=$tz LC_ALL=$locale umask $mask JDK ${jdk:-chosen by Gradle}"
    if ! (
        cd "$src"
        umask "$mask"
        export TZ=$tz LC_ALL=$locale
        if [[ -n "$jdk" ]]; then export JAVA_HOME=$jdk; fi
        ./gradlew --no-daemon --no-build-cache --no-configuration-cache \
            -Pkotlin.compiler.execution.strategy=in-process \
            "${jdk_args[@]}" :app:android:assembleRelease
    ) >"$out/$name.log" 2>&1; then
        echo "[$name] build failed, see $out/$name.log" >&2
        exit 2
    fi
    cp "$src/$apk_path" "$out/$name.apk"
    echo "[$name] $(sha256sum "$out/$name.apk" | cut -d' ' -f1)"
}

build a UTC C.UTF-8 022 "${JOTTER_JDK_A:-}"
build b Pacific/Kiritimati POSIX 077 "${JOTTER_JDK_B:-}"

if cmp -s "$out/a.apk" "$out/b.apk"; then
    echo "Identical: $(sha256sum "$out/a.apk" | cut -d' ' -f1) ($(stat -c %s "$out/a.apk") bytes)"
    exit 0
fi

echo "The two APKs differ" >&2
if command -v diffoscope >/dev/null; then
    diffoscope "$out/a.apk" "$out/b.apk" >&2 || true
else
    # Entry metadata (times, order, sizes, attributes), then the contents.
    diff <(zipinfo -v "$out/a.apk" | sed 1,2d) <(zipinfo -v "$out/b.apk" | sed 1,2d) \
        | head -50 >&2 || true
    for name in a b; do
        mkdir -p "$out/$name-unzipped"
        unzip -q "$out/$name.apk" -d "$out/$name-unzipped"
    done
    diff -rq "$out/a-unzipped" "$out/b-unzipped" >&2 || true
fi
exit 1
