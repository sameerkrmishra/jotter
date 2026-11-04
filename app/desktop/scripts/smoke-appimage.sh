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
#   - the CLI runs from inside the package: `cli add`, then `cli list`, which must show the note;
#   - AppRun chooses by name: started as `jotter` it runs the CLI; started as the desktop entry's
#     Exec name, or any other name, the app;
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

# -Xshare:on fails instead of going on without the runtime's base class data archive.
run env JAVA_OPTS=-Xshare:on "$launcher" cli list >/dev/null ||
    fail "the runtime's class data archive (lib/server/classes.jsa) is missing or unusable"
echo "ok: the runtime's class data archive is used"

# run_as NAME ARGS...: the package started under NAME, as through a symlink on PATH or the desktop
# entry. An AppImage gets a symlink, and its runtime sets ARGV0; an AppDir gets ARGV0 directly.
run_as() {
    local name=$1
    shift
    if [[ -d $target ]]; then
        run env ARGV0="$tmp/bin/$name" "$launcher" "$@"
    else
        ln -sfn "$target" "$tmp/bin/$name"
        run "$tmp/bin/$name" "$@"
    fi
}

# AppRun's choice for each name: `jotter` is the CLI, anything else the app.
listing=$(run_as jotter list --tag smoke) || fail "jotter list exited with $?"
grep -q "^$id"$'\t' <<<"$listing" || fail "jotter (symlink) list did not show the note: $listing"
echo "ok: started as jotter, the CLI ran"

# The desktop entry's Exec must start the app, not the CLI.
entry=io.github.sameerkrmishra.jotter.desktop
if [[ -d $target ]]; then
    cp "$target/$entry" "$tmp/"
else
    (cd "$tmp" && "$target" --appimage-extract "$entry" >/dev/null && cp "squashfs-root/$entry" .)
fi
exec_name=$(sed -n 's/^Exec=\([^ ]*\).*/\1/p' "$tmp/$entry")
[[ -n $exec_name && $exec_name != jotter ]] || fail "the desktop entry's Exec is '$exec_name'"
out=$(run_as "$exec_name" --version) || fail "$exec_name --version exited with $?"
[[ $out == "jotter $version" ]] || fail "$exec_name --version printed '$out': not the app"
echo "ok: started as $exec_name (the desktop entry's Exec), the app ran"

out=$(run_as Jotter.AppImage --version) || fail "Jotter.AppImage --version exited with $?"
[[ $out == "jotter $version" ]] || fail "Jotter.AppImage --version printed '$out': not the app"
echo "ok: started under another name, the app ran"

[[ -s $tmp/notes.db ]] || fail "no database at JOTTER_DB"

if [[ ${JOTTER_SMOKE_GUI:-} == 1 ]]; then
    command -v xvfb-run >/dev/null || fail "JOTTER_SMOKE_GUI=1 needs xvfb-run"
    # xvfb-run sets DISPLAY to its own server; the caller's is never passed on.
    # The timeout is on the app itself, inside xvfb-run, so that a hung app is killed rather than
    # left holding the output open. Output goes to a file, to be shown whatever happens.
    status=0
    env -i PATH=/usr/bin:/bin HOME="$tmp/home" TMPDIR="$tmp/tmp" \
        XDG_DATA_HOME="$tmp/data" XDG_CONFIG_HOME="$tmp/config" XDG_CACHE_HOME="$tmp/cache" \
        JOTTER_DB="$tmp/notes.db" APPIMAGE_EXTRACT_AND_RUN=1 \
        xvfb-run -a timeout -k 10 90 "$launcher" --autoclose >"$tmp/gui.log" 2>&1 || status=$?
    shown=no
    if grep -q "window shown, closing" "$tmp/gui.log"; then shown=yes; fi
    [[ $status == 0 && $shown == yes ]] ||
        fail "--autoclose: exit status $status, window shown: $shown; output: $(cat "$tmp/gui.log")"
    echo "ok: window shown on a virtual display, and closed"
fi

echo "All checks passed"
