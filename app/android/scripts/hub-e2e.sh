#!/bin/sh
# The Android app and a PC converge through the PC hub: an end-to-end test across an emulator
# and this machine. See app/android/README.md.
#
#   ANDROID_HOME=~/Android/Sdk app/android/scripts/hub-e2e.sh emulator-5554
#
# The PC's half is app:sync's HubForAndroidTest, on this machine: a hub on 127.0.0.1 and the PC
# syncing with its folder. The phone's half is app:android's HubEndToEndTest, on the emulator or
# device SERIAL. `adb reverse` makes the emulator's 127.0.0.1:PORT this machine's, so the phone
# sends its token to loopback, which the hub client allows (ADR-0015); 10.0.2.2, the emulator's
# name for this machine, is refused like any other LAN address.
#
# It installs the app and its tests on SERIAL, adds and later removes one `adb reverse` rule
# there, and leaves its files in build/hub-e2e.*. Exits 0 only if both halves passed.
set -eu

serial=${1:?usage: hub-e2e.sh SERIAL, such as emulator-5554}
root=$(cd "$(dirname "$0")/../../.." && pwd)
adb="${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}/platform-tools/adb"
apks="$root/app/android/build/outputs/apk"

cd "$root"
mkdir -p build
dir=$(mktemp -d "$root/build/hub-e2e.XXXXXX")
echo "hub-e2e: files in $dir"

./gradlew --console=plain -q :app:android:assembleDebug :app:android:assembleDebugAndroidTest
"$adb" -s "$serial" install -r -t "$apks/debug/android-debug.apk" > /dev/null
"$adb" -s "$serial" install -r -t "$apks/androidTest/debug/android-debug-androidTest.apk" > /dev/null

# The PC's half waits for the phone; it starts the hub and writes `port` and `token`.
JOTTER_HUB_E2E_DIR="$dir" ./gradlew --console=plain :app:sync:jvmTest --rerun \
    > "$dir/pc.log" 2>&1 &
pc=$!

port=""
finish() {
    # The PC's half checks the result once the phone is done, also when it failed.
    touch "$dir/phone-done"
    if [ -n "$port" ]; then
        "$adb" -s "$serial" reverse --remove "tcp:$port" 2> /dev/null || true
    fi
}
trap finish EXIT

waited=0
while [ ! -s "$dir/port" ]; do
    if ! kill -0 "$pc" 2> /dev/null; then
        echo "hub-e2e: the PC's half ended before its hub was ready, see $dir/pc.log" >&2
        exit 1
    fi
    waited=$((waited + 1))
    if [ "$waited" -gt 600 ]; then
        echo "hub-e2e: no hub after 10 minutes" >&2
        exit 1
    fi
    sleep 1
done
port=$(cat "$dir/port")
token=$(cat "$dir/token")

"$adb" -s "$serial" reverse "tcp:$port" "tcp:$port" > /dev/null
"$adb" -s "$serial" shell am instrument -w \
    -e class io.github.sameerkrmishra.jotter.app.android.HubEndToEndTest \
    -e hubPort "$port" -e hubToken "$token" \
    io.github.sameerkrmishra.jotter.test/androidx.test.runner.AndroidJUnitRunner \
    | tr -d '\r' > "$dir/phone.log"
phone=0
grep -q '^OK (1 test)$' "$dir/phone.log" || phone=1

finish
trap - EXIT
pcstatus=0
wait "$pc" || pcstatus=$?

if [ "$phone" -ne 0 ]; then
    echo "hub-e2e: the phone's half failed, see $dir/phone.log" >&2
    cat "$dir/phone.log" >&2
fi
if [ "$pcstatus" -ne 0 ] || [ "$(cat "$dir/result" 2> /dev/null)" != "ok" ]; then
    echo "hub-e2e: the PC's half failed, see $dir/pc.log" >&2
    exit 1
fi
[ "$phone" -eq 0 ] || exit 1
echo "hub-e2e: the phone and the PC converged through the hub"
