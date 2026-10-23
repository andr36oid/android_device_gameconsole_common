#!/bin/bash
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=$1
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
mkdir -p "$T"
"$JDK/javac" -encoding UTF-8 -d "$T" \
    "$HERE"/tests/ui-stubs/android/content/*.java \
    "$HERE"/tests/ui-stubs/android/view/*.java \
    "$HERE"/tests/ui-stubs/android/view/inputmethod/*.java \
    "$HERE"/tests/ui-stubs/android/widget/*.java \
    "$HERE/src/org/andr36oid/usbmode/UsbTextInput.java" \
    "$HERE/src/org/andr36oid/usbmode/UsbPageFocus.java" \
    "$HERE/src/org/andr36oid/usbmode/UsbRequest.java" \
    "$HERE/src/org/andr36oid/usbmode/UsbImageCreate.java" \
    "$HERE/src/org/andr36oid/usbmode/UsbFunctions.java" \
    "$HERE/src/org/andr36oid/usbmode/UsbDrive.java" \
    "$HERE/tests/controller/RequestTest.java" "$HERE/tests/controller/ImageCreateTest.java" \
    "$HERE/tests/controller/TextInputTest.java" "$HERE/tests/controller/PageFocusTest.java"
"$JDK/java" -cp "$T" org.andr36oid.usbmode.TextInputTest
"$JDK/java" -cp "$T" org.andr36oid.usbmode.PageFocusTest
"$JDK/java" -cp "$T" org.andr36oid.usbmode.RequestTest
"$JDK/java" -cp "$T" org.andr36oid.usbmode.ImageCreateTest
