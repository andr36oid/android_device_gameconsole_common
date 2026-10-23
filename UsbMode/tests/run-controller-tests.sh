#!/bin/bash
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=$1
mkdir -p "$T"
T=$(cd "$T" && pwd)
export TMPDIR=$T
CXX=${CXX:-c++}
MOTION=("$HERE/controller/Flipper.cpp" "$HERE/controller/TextFiles.cpp" "$HERE/controller/Protocols.cpp" "$HERE/controller/Services.cpp" "$HERE/controller/Keyboard.cpp" "$HERE/../joyMouse/src/PointerModel.cpp" "$HERE/../joyMouse/src/ScrollModel.cpp")
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "$HERE/tests/controller/CoreTest.cpp" -o "$T/core"
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "${MOTION[@]}" "$HERE/tests/controller/DaemonTest.cpp" -o "$T/native"
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "${MOTION[@]}" "$HERE/tests/controller/KeyboardDaemonTest.cpp" -o "$T/keyboard-native"
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "${MOTION[@]}" "$HERE/tests/controller/CompositeTest.cpp" -o "$T/composite-native"
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "$HERE/controller/Protocols.cpp" "$HERE/controller/Services.cpp" \
    "$HERE/controller/TextFiles.cpp" "$HERE/tests/controller/MoreTest.cpp" -o "$T/more-native"
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "$HERE/controller/Protocols.cpp" "$HERE/controller/Services.cpp" \
    "$HERE/controller/TextFiles.cpp" "$HERE/tests/controller/RoundFiveTest.cpp" -o "$T/round-five"
"$CXX" -std=c++17 -Wall -Wextra -Werror -I"$HERE/controller" \
    "$HERE/controller/Controller.cpp" "${MOTION[@]}" "$HERE/tests/controller/FlipperTest.cpp" -o "$T/flipper-native"
"$T/flipper-native" "$T"
"$T/round-five" "$T"
python3 "$HERE/soundboard/synthesize.py" --check
python3 "$HERE/tests/controller/round-five-resources.py"
"$T/more-native" "$HERE/tests/controller"
"$T/composite-native"
"$T/keyboard-native"
"$T/native"
"$CXX" -std=c++17 -Wall -Wextra -Werror \
    "$HERE/controller/Controller.cpp" "${MOTION[@]}" "$HERE/controller/main.cpp" -o "$T/daemon"
"$T/core"
python3 "$HERE/tests/controller/parse-hid.py" "$T/daemon"
python3 "$HERE/tests/controller/more-descriptors.py" "$T/daemon"
for arg in invalid ';reboot' /dev/hidg0; do
    if "$T/daemon" "$arg"; then echo 'Invalid mode accepted'; exit 1; fi
done
python3 "$HERE/tests/controller/check-kernel.py" "$T"
python3 "$HERE/tests/controller/check-flipper-kernel.py" "${CONTROLLER_KERNEL:-/andr36oid/work/wt/xinput-kernel}" "$T" "$HERE/kernel/flipper-functionfs.patch"
python3 "$HERE/tests/controller/check-eject.py" "${CONTROLLER_KERNEL:-/andr36oid/work/wt/xinput-kernel}" "$T"
python3 "$HERE/tests/controller/check-webusb.py" "${CONTROLLER_KERNEL:-/andr36oid/work/wt/xinput-kernel}" "$T"
JDK=${JDK:-/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin}
"$JDK/javac" -encoding UTF-8 -d "$T/classes" "$HERE/src/org/andr36oid/usbmode/ControllerMode.java" \
    "$HERE/src/org/andr36oid/usbmode/MacroProfiles.java" "$HERE/src/org/andr36oid/usbmode/SoundMixer.java" "$HERE/tests/controller/PadModelsTest.java" "$HERE/tests/controller/ModeTest.java" "$HERE/src/org/andr36oid/usbmode/KeyboardModel.java" \
    "$HERE/src/org/andr36oid/usbmode/TextTyper.java" "$HERE/tests/controller/KeyboardTest.java" "$HERE/src/org/andr36oid/usbmode/UsbFunctions.java" \
    "$HERE/src/org/andr36oid/usbmode/FlipperLease.java" "$HERE/tests/controller/FlipperLeaseTest.java" "$HERE/src/org/andr36oid/usbmode/UsbPeer.java" "$HERE/tests/controller/FunctionsTest.java" "$HERE/tests/controller/TextTyperTest.java"
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.PadModelsTest
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.ModeTest
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.TextTyperTest
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.KeyboardTest
"$T/daemon" --policies >"$T/policies"
"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.FunctionsTest "$T/policies"

"$JDK/java" -cp "$T/classes" org.andr36oid.usbmode.FlipperLeaseTest

ANDROID_JAR=${ANDROID_JAR:-/opt/android-sdk/platforms/android-35/android.jar}
"$JDK/javac" -encoding UTF-8 -cp "$ANDROID_JAR" -d "$T/service-classes" \
    "$HERE/tests/service-stubs/android/os/SystemProperties.java" \
    "$HERE/tests/service-stubs/org/andr36oid/usbmode/UsbDeviceControl.java" \
    "$HERE/tests/service-stubs/org/andr36oid/usbmode/UsbDriveControl.java" \
    "$HERE/src/org/andr36oid/usbmode/UsbFunctions.java" \
    "$HERE/src/org/andr36oid/usbmode/FlipperLease.java" \
    "$HERE/src/org/andr36oid/usbmode/FlipperUsbService.java"
