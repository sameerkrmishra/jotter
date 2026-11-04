#!/bin/sh
# Runs the device tests on the connected emulator, as the `android-device` CI job does, and runs
# once more the device tests of each module that reported no test case at all.
#
# Why: on 2026-11-04 (run 41812379147, API 26) the test runner's own adb call for the device's API
# level failed (exit code 255, no output) before core:sync's test APK was installed. AGP logged
# "AndroidTestRunner failed", wrote no results and still passed the task, so only the zero-test
# guard caught it. core:sync had passed in the 25 API 26 runs before, and passed every time on a
# local API 26 emulator with the job's RAM and cores, alone and with all the modules. A module whose tests did not run gets one more attempt; a module whose
# tests ran and failed does not. Run it from the repository root; the job's next step,
# device-test-results.sh, still checks every module afterwards.
set -u

./gradlew connectedAndroidDeviceTest :app:android:connectedDebugAndroidTest --continue
status=$?

# device-test-results.sh prints "<module>: <cases> test cases, <failures> failed".
tasks=$(.github/scripts/device-test-results.sh | awk '$2 == 0 { sub(":$", "", $1); print $1 }' |
    while read -r module; do
        if [ "$module" = app/android ]; then
            echo :app:android:connectedDebugAndroidTest --rerun
        else
            echo ":$(echo "$module" | tr / :):connectedAndroidDeviceTest --rerun"
        fi
    done)

if [ -n "$tasks" ]; then
    echo "No test case ran in: $tasks. Running them once more."
    # shellcheck disable=SC2086 # one word per task and option
    ./gradlew $tasks --continue || status=1
fi
exit $status
