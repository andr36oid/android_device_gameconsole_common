#!/bin/bash
# Resource linking and javac only, using the locally built Android 11 framework.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
ANDROID=${ANDROID:-/andr36oid/src}
JDK=${JDK:-$ANDROID/prebuilts/jdk/jdk11/linux-x86/bin}
AAPT2=${AAPT2:-$ANDROID/out/host/linux-x86/bin/aapt2}
FRAMEWORK=${FRAMEWORK:-$ANDROID/out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/combined/framework-minus-apex.jar}
RES=${RES:-$ANDROID/out/target/product/r36s/system/framework/framework-res.apk}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/hints/org/andr36oid/hints"
"$AAPT2" compile --dir "$HERE/res" -o "$T/resources.zip"
COMPAT=${COMPAT:-$ANDROID/out/soong/.intermediates/external/setupcompat/setupcompat/android_common/package-res.apk}
DESIGN=${DESIGN:-$ANDROID/out/soong/.intermediates/external/setupdesign/setupdesign/android_common/package-res.apk}
"$AAPT2" compile --dir "$HERE/../SetupTheme/res" -o "$T/theme.zip"
"$AAPT2" link --static-lib --no-static-lib-packages --auto-add-overlay -I "$COMPAT" -I "$DESIGN" -R "$T/theme.zip" -I "$RES" --manifest "$HERE/AndroidManifest.xml" --java "$T" \
	-o "$T/res.apk" "$T/resources.zip"
# HintBar's resource IDs do not matter for a javac check.
cat >"$T/hints/org/andr36oid/hints/R.java" <<'JAVA'
package org.andr36oid.hints;
public final class R {
    public static final class string {
        public static final int andr36oid_hint_select=1, andr36oid_hint_back=2;
    }
    public static final class drawable { public static final int andr36oid_hint_key=1; }
}
JAVA
NETD=${NETD:-$ANDROID/out/soong/.intermediates/system/netd/server/netd_aidl_interface-java/android_common/javac/netd_aidl_interface-java.jar}
"$JDK/javac" -encoding UTF-8 -cp "$FRAMEWORK:$NETD" -d "$T/classes" \
	"$T/org/andr36oid/usbmode/R.java" "$T/hints/org/andr36oid/hints/R.java" \
	"$HERE/../HintBar/src/org/andr36oid/hints/HintBar.java" \
	"$HERE"/src/org/andr36oid/usbmode/*.java
printf 'All UsbMode Java sources and resources passed\n'
