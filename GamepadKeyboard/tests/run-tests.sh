#!/bin/bash
# Host tests for the gamepad keyboard's layout, navigation and stick logic (no Android needed).
# Usage: GamepadKeyboard/tests/run-tests.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JDK/javac" -encoding UTF-8 -d "$OUT" \
    "$HERE"/../src/org/andr36oid/keyboard/core/*.java "$HERE/KeyboardTest.java"
"$JDK/java" -cp "$OUT" KeyboardTest
