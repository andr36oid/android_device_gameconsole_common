#!/bin/bash
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=$1
mkdir -p "$T"
T=$(cd "$T" && pwd)
export TMPDIR=$T
CXX=${CXX:-c++}
MOTION=("$HERE/controller/Keyboard.cpp" "$HERE/../joyMouse/src/PointerModel.cpp" "$HERE/../joyMouse/src/ScrollModel.cpp")
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "$HERE/tests/controller/CoreTest.cpp" -o "$T/core"
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "${MOTION[@]}" "$HERE/tests/controller/DaemonTest.cpp" -o "$T/native"
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "${MOTION[@]}" "$HERE/tests/controller/KeyboardDaemonTest.cpp" -o "$T/keyboard-native"
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "${MOTION[@]}" "$HERE/tests/controller/CompositeTest.cpp" -o "$T/composite-native"
"$T/composite-native"
"$T/keyboard-native"
"$T/native"
"$CXX" -std=c++17 -Wall -Wextra -Werror \
    "$HERE/controller/Controller.cpp" "${MOTION[@]}" "$HERE/controller/main.cpp" -o "$T/daemon"
"$T/core"
python3 "$HERE/tests/controller/parse-hid.py" "$T/daemon"
for arg in invalid ';reboot' /dev/hidg0; do
    if "$T/daemon" "$arg"; then echo 'Invalid mode accepted'; exit 1; fi
done
python3 "$HERE/tests/controller/check-kernel.py" "$T"
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
"$JDK/javac" -encoding UTF-8 -d "$T/classes" "$HERE/src/org/andr36oid/usbmode/ControllerMode.java" \
    "$HERE/tests/controller/ModeTest.java" "$HERE/src/org/andr36oid/usbmode/KeyboardModel.java" \
    "$HERE/tests/controller/KeyboardTest.java" "$HERE/src/org/andr36oid/usbmode/UsbFunctions.java" \
    "$HERE/tests/controller/FunctionsTest.java"
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.ModeTest
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.KeyboardTest
"$T/daemon" --policies >"$T/policies"
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.FunctionsTest "$T/policies"
