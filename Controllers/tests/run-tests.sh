#!/bin/bash
# Runs andr36oid-gpio-scan.sh in mksh against a fake /sys and /data, with getprop/setprop
# stubs that keep properties in files and a pause stub that moves on to the next fake
# reading. Usage: tests/run-tests.sh [path to mksh]
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
MKSH=${1:-/andr36oid/work/tmp/o2-shellcheck/mksh/mksh}
[ -x "$MKSH" ] || MKSH=$(command -v mksh || command -v bash)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
pass=0
fail=0

check() { # name expected actual
	if [ "$2" = "$3" ]; then
		pass=$((pass + 1))
	else
		fail=$((fail + 1))
		echo "FAIL $1: expected '$2', got '$3'"
	fi
}

MUX=00000000000000000000000000000000
PULL=uuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu

setup() {
	rm -rf "${T:?}/root" "${T:?}/props" "${T:?}/bin" "${T:?}/samples"
	mkdir -p "$T/props" "$T/bin" "$T/samples"
	R=$T/root
	mkdir -p "$R"/sys/devices/platform/pinctrl "$R"/data/system \
		"$R"/sys/bus/iio/devices/iio:device0
	echo ff288000.saradc >"$R"/sys/bus/iio/devices/iio:device0/name
	cat >"$T/bin/getprop" <<EOF
#!/bin/sh
cat "$T/props/\$1" 2>/dev/null
EOF
	cat >"$T/bin/setprop" <<EOF
#!/bin/sh
printf '%s\n' "\$2" >"$T/props/\$1"
EOF
	# sleep: the next fake reading (samples/N.levels and N.adc<chan>), if there is one
	cat >"$T/bin/sleep" <<EOF
#!/bin/sh
n=\$(cat "$T/samples/at" 2>/dev/null || echo 0)
n=\$((n + 1))
echo \$n >"$T/samples/at"
[ -e "$T/samples/\$n.levels" ] && cp "$T/samples/\$n.levels" "$R/sys/devices/platform/pinctrl/gpio_levels"
for f in "$T/samples/\$n".adc*; do
	[ -e "\$f" ] || continue
	c=\${f##*.adc}
	cp "\$f" "$R/sys/bus/iio/devices/iio:device0/in_voltage\${c}_raw"
done
exit 0
EOF
	chmod +x "$T/bin/"*
}

prop() { cat "$T/props/$1" 2>/dev/null; }
setp() { printf '%s\n' "$2" >"$T/props/$1"; }
# levels <n> <bank0> <bank1> <bank2> <bank3> ; a fake gpio_levels reading
levels() {
	f=$T/samples/$1.levels
	[ "$1" = 0 ] && f=$R/sys/devices/platform/pinctrl/gpio_levels
	: >"$f"
	b=0
	shift
	for v in "$@"; do
		echo "gpio$b level=0x$v dir=0x00000000 mux=$MUX pull=$PULL" >>"$f"
		b=$((b + 1))
	done
}
# adc <n> <chan> <value>
adc() {
	f=$T/samples/$1.adc$2
	[ "$1" = 0 ] && f=$R/sys/bus/iio/devices/iio:device0/in_voltage$2_raw
	echo "$3" >"$f"
}
run() { KT_ROOT=$R GS_SAMPLES=${N:-3} GS_SLEEP=$T/bin/sleep PATH="$T/bin:$PATH" "$MKSH" "$HERE/../helper/andr36oid-gpio-scan.sh"; }
line() { grep "^$1 " "$R/data/system/andr36oid-gpio-scan-$2.txt" | cut -d' ' -f2-4; }

# --- no kernel file
setup
setp sys.andr36oid.gscan.request 5
setp sys.andr36oid.gscan.phase idle
run
check "no gpio_levels" "5 missing" "$(prop sys.andr36oid.gscan.result)"

# --- wrong phase
setup
levels 0 00000000 00000000 00000000 00000000
setp sys.andr36oid.gscan.request 6
setp sys.andr36oid.gscan.phase "idle; reboot"
run
check "bad phase" "6 invalid" "$(prop sys.andr36oid.gscan.result)"
check "bad phase writes nothing" "" "$(ls "$R"/data/system)"

# --- steady pins, one that changes, top bits (mksh counts in 32-bit signed numbers)
setup
levels 0 ffffffff 00000000 80000001 0000ff00
levels 1 ffffffff 00000000 80000001 0000ff00
levels 2 ffffffff 00000020 80000001 0000ff00
adc 0 0 1023
adc 0 1 512
adc 1 1 600
adc 2 1 400
setp sys.andr36oid.gscan.request 7
setp sys.andr36oid.gscan.phase held
run
check "scan answered" "7 ok" "$(prop sys.andr36oid.gscan.result)"
check "bank 0 all high" "low=0x00000000 high=0xffffffff changing=0x00000000" "$(line gpio0 held)"
check "bank 1 one pin changing" "low=0xffffffdf high=0x00000000 changing=0x00000020" "$(line gpio1 held)"
check "bank 2 top and bottom bit" "low=0x7ffffffe high=0x80000001 changing=0x00000000" "$(line gpio2 held)"
check "bank 3" "low=0xffff00ff high=0x0000ff00 changing=0x00000000" "$(line gpio3 held)"
check "mux and pull kept" "dir=0x00000000 mux=$MUX pull=$PULL" \
	"$(grep '^gpio3 ' "$R/data/system/andr36oid-gpio-scan-held.txt" | cut -d' ' -f5-7)"
check "adc steady" "adc ff288000.saradc 0 min=1023 max=1023" \
	"$(grep '^adc .* 0 ' "$R/data/system/andr36oid-gpio-scan-held.txt")"
check "adc moving" "adc ff288000.saradc 1 min=400 max=600" \
	"$(grep '^adc .* 1 ' "$R/data/system/andr36oid-gpio-scan-held.txt")"
check "readable by the app" "-rw-r--r--" "$(stat -c %A "$R/data/system/andr36oid-gpio-scan-held.txt")"
check "no temp file left" "andr36oid-gpio-scan-held.txt" "$(ls "$R"/data/system)"

# --- junk lines in the kernel file are skipped
setup
printf 'gpio0 level=0xZZ dir=0x0 mux=0 pull=u\nbank; reboot level=0x00000000\ngpio1 level=0x0000000f dir=0x00000000 mux=%s pull=%s\n' \
	"$MUX" "$PULL" >"$R"/sys/devices/platform/pinctrl/gpio_levels
setp sys.andr36oid.gscan.request 8
setp sys.andr36oid.gscan.phase idle
N=1 run
check "junk skipped" "8 ok" "$(prop sys.andr36oid.gscan.result)"
check "only the good bank" "gpio1" "$(grep -c '^gpio' "$R/data/system/andr36oid-gpio-scan-idle.txt" | sed 's/^1$/gpio1/')"

echo "passed $pass, failed $fail (shell: $MKSH)"
[ "$fail" = 0 ]
