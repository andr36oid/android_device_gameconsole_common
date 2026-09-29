#!/bin/sh
# Host test for overlay-audit.py: a fake tree (base app, Lineage product
# overlay, our overlays, device.mk) under mktemp -d, source layers only
# (--no-apk). Checks each kind of loser is found and that the fixed order passes.
set -u
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
if [ -z "$tmp" ] || [ ! -d "$tmp" ]; then echo "mktemp failed"; exit 1; fi
trap 'rm -rf "$tmp"' EXIT INT TERM
fail=0

w() { mkdir -p "$(dirname "$1")"; cat > "$1"; }

src=$tmp/src
res=frameworks/base/core/res/res
w "$src/$res/values/config.xml" <<'EOF'
<resources>
  <bool name="config_a">true</bool>
  <integer name="config_home">0</integer>
  <integer name="config_cols">4</integer>
  <integer name="config_night">1</integer>
  <integer name="config_big">1</integer>
</resources>
EOF
w "$src/$res/values-sw600dp/config.xml" <<'EOF'
<resources><integer name="config_home">0</integer></resources>
EOF
w "$src/$res/values-land/config.xml" <<'EOF'
<resources><integer name="config_cols">5</integer></resources>
EOF
w "$src/$res/values-night/config.xml" <<'EOF'
<resources><integer name="config_night">2</integer></resources>
EOF
w "$src/$res/values-large/config.xml" <<'EOF'
<resources><integer name="config_big">2</integer></resources>
EOF
w "$src/vendor/lineage/overlay/common/$res/values/config.xml" <<'EOF'
<resources><bool name="config_a">true</bool></resources>
EOF
mkdir -p "$src/vendor/crowdin/overlay"

common=$src/device/gameconsole/common
w "$common/overlay/$res/values/config.xml" <<'EOF'
<resources>
  <bool name="config_a">false</bool>
  <integer name="config_home">1</integer>
  <integer name="config_cols">6</integer>
  <integer name="config_night">3</integer>
  <integer name="config_big">3</integer>
</resources>
EOF
mkdir -p "$src/device/gameconsole/r36s/overlay"
: > "$src/device/gameconsole/r36s/device.mk"

old_mk() {
	w "$common/device.mk" <<'EOF'
$(call inherit-product, vendor/lineage/config/common_mini_tablet_wifionly.mk)
DEVICE_PACKAGE_OVERLAYS += \
	device/gameconsole/common/overlay
EOF
}
new_mk() {
	w "$common/device.mk" <<'EOF'
PRODUCT_PACKAGE_OVERLAYS += \
	device/gameconsole/common/overlay
$(call inherit-product, vendor/lineage/config/common_mini_tablet_wifionly.mk)
EOF
}

run() { python3 "$here/overlay-audit.py" --src "$src" --no-apk "$@" > "$tmp/out" 2>&1; echo $? > "$tmp/rc"; }
expect() {  # expect <grep pattern> <description>
	if grep -q -- "$1" "$tmp/out"; then echo "PASS $2"; else echo "FAIL $2"; fail=1; cat "$tmp/out"; fi
}
expect_not() {
	if grep -q -- "$1" "$tmp/out"; then echo "FAIL $2"; fail=1; cat "$tmp/out"; else echo "PASS $2"; fi
}
expect_rc() {
	if [ "$(cat "$tmp/rc")" = "$1" ]; then echo "PASS exit $1"; else echo "FAIL exit $(cat "$tmp/rc") (want $1)"; fail=1; fi
}

old_mk
run
expect 'LOSES-LAYER .*bool/config_a' 'device overlay loses to Lineage product overlay'
expect 'LOSES-QUALIFIER .*integer/config_cols' 'values-land beats our values/'
expect 'LOSES-QUALIFIER .*integer/config_night' 'values-night beats our values/ (dark theme default)'
expect 'LOSES to (night) .*(night only)' 'night loss named as night only'
expect_not 'config_home.*LOSES\|LOSES.*config_home' 'values-sw600dp does not apply at 431 dp'
expect_not 'LOSES.*config_big' 'values-large does not apply at 575x431 dp'
expect_rc 1

run --profile square178
expect 'LOSES-QUALIFIER .*integer/config_home' 'values-sw600dp applies on 720x720 at 178'

new_mk
# the fix for the other two: the matching qualifier folders
w "$common/overlay/$res/values-land/config.xml" <<'EOF'
<resources><integer name="config_cols">6</integer></resources>
EOF
w "$common/overlay/$res/values-night/config.xml" <<'EOF'
<resources><integer name="config_night">3</integer></resources>
EOF
run
expect '1\. device/gameconsole/common/overlay .*product' 'product overlay before Lineage comes first'
expect '0 lose' 'fixed tree has no losers'
expect_rc 0

# a region folder of the base app hides our default string
w "$src/$res/values-en-rGB/strings.xml" <<'EOF'
<resources><string name="about">About tablet</string></resources>
EOF
w "$common/overlay/$res/values/strings.xml" <<'EOF'
<resources><string name="about">About console</string></resources>
EOF
run
expect 'LOSES-LOCALE .*string/about' 'en-GB region folder found'
run --skip-locale
expect_rc 0

[ "$fail" = 0 ] && echo "all passed"
exit "$fail"
