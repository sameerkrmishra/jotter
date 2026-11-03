#!/bin/sh
# Checks that the device tests of every module that applies jotter.android-device-test ran: a
# module whose runner arguments filtered out every test would otherwise pass without a word.
# Prints the number of test cases per module. Run it from the repository root after
# `./gradlew connectedAndroidDeviceTest`.
set -eu

modules=$(grep -rl --include=build.gradle.kts 'id("jotter.android-device-test")' \
    --exclude-dir=build --exclude-dir=build-logic . | sed 's|^\./||; s|/build.gradle.kts$||' | sort)
test -n "$modules"

status=0
for module in $modules; do
    results=$module/build/outputs/androidTest-results/connected
    cases=$(find "$results" -name 'TEST-*.xml' -exec grep -c '<testcase ' {} + 2>/dev/null |
        awk -F: '{ n += $NF } END { print n + 0 }')
    failures=$(find "$results" -name 'TEST-*.xml' -exec grep -c '<failure' {} + 2>/dev/null |
        awk -F: '{ n += $NF } END { print n + 0 }')
    echo "$module: $cases test cases, $failures failed"
    if [ "$cases" -eq 0 ] || [ "$failures" -ne 0 ]; then
        status=1
    fi
done
exit $status
