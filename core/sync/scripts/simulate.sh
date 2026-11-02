#!/bin/sh
# Runs a range of simulator seeds and proves that it ran: the test report must hold the line
# ConvergenceTest prints for exactly that range. Run it from the repository root.
#
# Usage: core/sync/scripts/simulate.sh START CASES [jvm|linuxX64]
set -eu

start=$1
cases=$2
target=${3:-jvm}
results=core/sync/build/test-results/${target}Test

# A report left by an earlier run must not pass for this one.
rm -f "$results"/*ConvergenceTest.xml

JOTTER_SIM_START=$start JOTTER_SIM_CASES=$cases \
    ./gradlew ":core:sync:${target}Test" --rerun --tests ConvergenceTest

expected="Simulated seeds $start until $((start + cases)):"
if ! grep -h "$expected" "$results"/*ConvergenceTest.xml; then
    echo "The report does not say that seeds $start until $((start + cases)) ran" >&2
    exit 1
fi
