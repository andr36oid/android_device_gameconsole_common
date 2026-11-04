#!/bin/bash
set -eu
task_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_output=$(mktemp -d)
trap 'rm -rf "$test_output"' EXIT
javac -d "$test_output" \
    "$task_dir/src/org/andr36oid/subsystemsetup/BootLogReader.java" \
    "$task_dir/src/org/andr36oid/subsystemsetup/OptimizeProgress.java" \
    "$task_dir/tests/BootLogReaderTest.java" \
    "$task_dir/tests/OptimizeProgressTest.java"
java -cp "$test_output" org.andr36oid.subsystemsetup.BootLogReaderTest
java -cp "$test_output" org.andr36oid.subsystemsetup.OptimizeProgressTest "$task_dir/res/values/strings.xml"
