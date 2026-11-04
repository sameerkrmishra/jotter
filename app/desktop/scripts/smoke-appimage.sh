#!/usr/bin/env bash
# Checks a Jotter AppImage, or the AppDir it is made of, without a display and without the user's
# notes (docs/adr/0020-linux-desktop-package.md).
#
#   app/desktop/scripts/smoke-appimage.sh PATH [VERSION]
#
# PATH is a .AppImage file or a Jotter.AppDir directory. Every run gets an empty environment with a
# temporary HOME, XDG directories, TMPDIR and JOTTER_DB, all removed at the end. An AppImage runs
# with APPIMAGE_EXTRACT_AND_RUN=1, so FUSE is not needed. It checks that:
#
#   - the desktop app's launcher starts the bundled runtime: `--version` prints `jotter VERSION`
#     (VERSION defaults to jotter.versionName in gradle.properties);
#   - the CLI runs from inside the package: `cli add`, then `cli list` and, for an AppImage, `list`
#     through a symlink named `jotter`, which must show the note;
#   - with JOTTER_SMOKE_GUI=1 and xvfb-run installed, the window opens on a virtual display and
#     the app closes itself (`--autoclose`). Never on the caller's display.
#
# Exit status 0 if every check passed.

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "usage: $0 PATH [VERSION]" >&2
    exit 2
fi
target=$(realpath -- "$1")
version=${2:-$(sed -n 's/^jotter\.versionName=//p' "$(dirname "$0")/../../../gradle.properties")}

if [[ -d $target ]]; then
    launcher=$target/AppRun
else
    launcher=$target
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/jotter-smoke.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/home" "$tmp/tmp" "$tmp/bin"

# run COMMAND...: in an empty environment of its own.
run() {
    env -i PATH=/usr/bin:/bin LANG=C.UTF-8 HOME="$tmp/home" TMPDIR="$tmp/tmp" \
        XDG_DATA_HOME="$tmp/data" XDG_CONFIG_HOME="$tmp/config" XDG_CACHE_HOME="$tmp/cache" \
        JOTTER_DB="$tmp/notes.db" APPIMAGE_EXTRACT_AND_RUN=1 "$@"
}

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

out=$(run "$launcher" --version) || fail "--version exited with $?"
[[ $out == "jotter $version" ]] || fail "--version printed '$out', expected 'jotter $version'"
echo "ok: --version: $out"

id=$(run "$launcher" cli add "smoke test note" --tag smoke) || fail "cli add exited with $?"
[[ $id =~ ^[0-9a-f-]{20,}$ ]] || fail "cli add printed '$id', not an id"
echo "ok: cli add: $id"

listing=$(run "$launcher" cli list) || fail "cli list exited with $?"
grep -q "^$id"$'\t'".*"$'\t'"smoke"$'\t'"smoke test note$" <<<"$listing" ||
    fail "cli list did not show the note: $listing"
echo "ok: cli list"

if [[ ! -d $target ]]; then
    ln -s "$target" "$tmp/bin/jotter"
    listing=$(run "$tmp/bin/jotter" list --tag smoke) || fail "jotter list exited with $?"
    grep -q "^$id"$'\t' <<<"$listing" || fail "jotter (symlink) list did not show the note: $listing"
    echo "ok: symlink named jotter runs the CLI"
fi

[[ -s $tmp/notes.db ]] || fail "no database at JOTTER_DB"

if [[ ${JOTTER_SMOKE_GUI:-} == 1 ]]; then
    command -v xvfb-run >/dev/null || fail "JOTTER_SMOKE_GUI=1 needs xvfb-run"
    # xvfb-run sets DISPLAY to its own server; the caller's is never passed on.
    out=$(env -i PATH=/usr/bin:/bin HOME="$tmp/home" TMPDIR="$tmp/tmp" \
        XDG_DATA_HOME="$tmp/data" XDG_CONFIG_HOME="$tmp/config" XDG_CACHE_HOME="$tmp/cache" \
        JOTTER_DB="$tmp/notes.db" APPIMAGE_EXTRACT_AND_RUN=1 \
        timeout 120 xvfb-run -a "$launcher" --autoclose 2>&1) || fail "--autoclose: $out"
    grep -q "window shown, closing" <<<"$out" || fail "--autoclose printed: $out"
    echo "ok: window shown on a virtual display, and closed"
fi

echo "All checks passed"
