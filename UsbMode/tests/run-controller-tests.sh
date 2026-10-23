#!/bin/bash
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=$1
mkdir -p "$T"
T=$(cd "$T" && pwd)
export TMPDIR=$T
CXX=${CXX:-c++}
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "$HERE/tests/controller/CoreTest.cpp" -o "$T/core"
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "$HERE/tests/controller/DaemonTest.cpp" -o "$T/native"
"$T/native"
"$CXX" -std=c++17 -Wall -Wextra -Werror \
    "$HERE/controller/Controller.cpp" "$HERE/controller/main.cpp" -o "$T/daemon"
"$T/core"
python3 "$HERE/tests/controller/parse-hid.py" "$T/daemon"
for arg in invalid ';reboot' /dev/hidg0; do
    if "$T/daemon" "$arg"; then echo 'Invalid mode accepted'; exit 1; fi
done
python3 "$HERE/tests/controller/check-kernel.py" "$T"
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
"$JDK/javac" -d "$T/classes" "$HERE/src/org/andr36oid/usbmode/ControllerMode.java" \
    "$HERE/tests/controller/ModeTest.java"
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.ModeTest
