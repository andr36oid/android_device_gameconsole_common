#!/bin/bash
# Runs andr36oid-ktweaks.sh in mksh against a fake /sys and /proc, with getprop/setprop
# stubs that keep properties in files. Usage: tests/run-tests.sh [path to mksh]
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

setup() {
	rm -rf "${T:?}/root" "${T:?}/props" "${T:?}/bin"
	mkdir -p "$T/props" "$T/bin"
	R=$T/root
	mkdir -p "$R"/sys/power "$R"/sys/module/printk/parameters "$R"/proc/sys/vm \
		"$R"/proc/sys/kernel "$R"/sys/block/mmcblk0/queue "$R"/sys/class/thermal/thermal_zone0 \
		"$R"/sys/class/thermal/thermal_zone1 "$R"/sys/devices/platform/odroidgo3-joypad \
		"$R"/sys/module/rcupdate/parameters "$R"/sys/module/rk817_charger/parameters \
		"$R"/sys/module/rk817_battery/parameters
	for c in 1 2 3; do
		mkdir -p "$R"/sys/devices/system/cpu/cpu$c
		echo 1 >"$R"/sys/devices/system/cpu/cpu$c/online
	done
	echo 's2idle [deep]' >"$R"/sys/power/mem_sleep
	echo '[none] core processors platform devices freezer' >"$R"/sys/power/pm_test
	echo 1 >"$R"/sys/power/pm_print_times
	echo 0 >"$R"/sys/power/pm_debug_messages
	echo Y >"$R"/sys/module/printk/parameters/console_suspend
	echo 100 >"$R"/proc/sys/vm/swappiness
	echo 100 >"$R"/proc/sys/vm/vfs_cache_pressure
	echo 3 >"$R"/proc/sys/vm/page-cluster
	echo '[mq-deadline] none' >"$R"/sys/block/mmcblk0/queue/scheduler
	echo 128 >"$R"/sys/block/mmcblk0/queue/read_ahead_kb
	echo gpu-thermal >"$R"/sys/class/thermal/thermal_zone0/type
	echo soc-thermal >"$R"/sys/class/thermal/thermal_zone1/type
	echo 85000 >"$R"/sys/class/thermal/thermal_zone1/trip_point_1_temp
	echo '0 0 1 0' >"$R"/sys/devices/platform/odroidgo3-joypad/stick_invert
	echo 10 >"$R"/sys/devices/platform/odroidgo3-joypad/poll_interval
	printf '4\t4\t1\t7\n' >"$R"/proc/sys/kernel/printk
	echo 7 >"$R"/proc/sys/kernel/panic
	echo 0 >"$R"/proc/sys/kernel/panic_on_oops
	echo 60 >"$R"/sys/module/rcupdate/parameters/rcu_cpu_stall_timeout
	echo 0 >"$R"/sys/module/rk817_charger/parameters/dbg_level
	echo 0 >"$R"/sys/module/rk817_battery/parameters/dbg_level
	# no mmcblk1, no hp_det_flip, no codec: not on this "console"

	cat >"$T/bin/getprop" <<EOF
#!/bin/sh
cat "$T/props/\$1" 2>/dev/null
EOF
	cat >"$T/bin/setprop" <<EOF
#!/bin/sh
printf '%s\n' "\$2" >"$T/props/\$1"
EOF
	printf '#!/bin/sh\nexit 0\n' >"$T/bin/log"
	chmod +x "$T/bin/"*
}

prop() { cat "$T/props/$1" 2>/dev/null; }
setp() { printf '%s\n' "$2" >"$T/props/$1"; }
run() { KT_ROOT=$R PATH="$T/bin:$PATH" "$MKSH" "$HERE/../helper/andr36oid-ktweaks.sh" "$@"; }

# --- boot notes the defaults
setup
run boot
check "default bracket" deep "$(prop sys.andr36oid.ktd.sleep_type)"
check "default pm_test" none "$(prop sys.andr36oid.ktd.sleep_test)"
check "default cores" 4 "$(prop sys.andr36oid.ktd.cores)"
check "default thermal zone by type" 85000 "$(prop sys.andr36oid.ktd.heat_limit)"
check "default printk first field" 4 "$(prop sys.andr36oid.ktd.log_level)"
check "default axes" "0 0 1 0" "$(prop sys.andr36oid.ktd.stick_invert)"
check "default bool param" Y "$(prop sys.andr36oid.ktd.sleep_console)"
check "missing knob has no default" "" "$(prop sys.andr36oid.ktd.sd2_readahead)"
check "missing param has no default" "" "$(prop sys.andr36oid.ktd.hp_det_flip)"
check "ready" 1 "$(prop sys.andr36oid.kt.ready)"
check "no tweaks, no try counted" "" "$(prop persist.andr36oid.kt.tries)"

# --- saved tweaks are applied at boot and counted
setup
setp persist.andr36oid.kt.swappiness 30
setp persist.andr36oid.kt.cores 2
setp persist.andr36oid.kt.heat_limit 90000
setp persist.andr36oid.kt.power_chip_log 1
setp persist.andr36oid.kt.stick_invert "1 0 1 0"
run boot
check "plain written" 30 "$(cat "$R"/proc/sys/vm/swappiness)"
check "default kept from before the write" 100 "$(prop sys.andr36oid.ktd.swappiness)"
check "current reported" 30 "$(prop sys.andr36oid.ktc.swappiness)"
check "cpu1 on" 1 "$(cat "$R"/sys/devices/system/cpu/cpu1/online)"
check "cpu2 off" 0 "$(cat "$R"/sys/devices/system/cpu/cpu2/online)"
check "cpu3 off" 0 "$(cat "$R"/sys/devices/system/cpu/cpu3/online)"
check "cores reported" 2 "$(prop sys.andr36oid.ktc.cores)"
check "thermal written to soc zone" 90000 "$(cat "$R"/sys/class/thermal/thermal_zone1/trip_point_1_temp)"
check "multi path 1" 1 "$(cat "$R"/sys/module/rk817_charger/parameters/dbg_level)"
check "multi path 2" 1 "$(cat "$R"/sys/module/rk817_battery/parameters/dbg_level)"
check "axes written" "1 0 1 0" "$(cat "$R"/sys/devices/platform/odroidgo3-joypad/stick_invert)"
check "try counted" 1 "$(prop persist.andr36oid.kt.tries)"
check "nothing failed" "" "$(prop sys.andr36oid.kt.failed | tr -d ' ')"

# --- apply puts a cleared tweak back to the default and reports done
setp persist.andr36oid.kt.swappiness ""
setp persist.andr36oid.kt.cores ""
setp sys.andr36oid.kt.request 42
run apply
check "back to default" 100 "$(cat "$R"/proc/sys/vm/swappiness)"
check "cores back" 1 "$(cat "$R"/sys/devices/system/cpu/cpu3/online)"
check "done" 42 "$(prop sys.andr36oid.kt.done)"

# --- a value the kernel won't keep is reported
# (the fake bracket file holds the written word, so reading it back finds no [..])
setp persist.andr36oid.kt.sleep_type s2idle
run apply
check "bracket written" s2idle "$(cat "$R"/sys/power/mem_sleep)"
check "failed reported" sleep_type "$(prop sys.andr36oid.kt.failed | tr -d ' ')"

# --- once knobs ignore persist and use sys.*
setup
run boot
setp persist.andr36oid.kt.sleep_test core
run apply
check "once ignores persist" '[none] core processors platform devices freezer' "$(cat "$R"/sys/power/pm_test)"

# --- two starts that didn't finish pause the tweaks
setup
setp persist.andr36oid.kt.swappiness 30
setp persist.andr36oid.kt.tries 2
run boot
check "paused by crash" crash "$(prop persist.andr36oid.kt.paused)"
check "tries reset" 0 "$(prop persist.andr36oid.kt.tries)"
check "not applied while paused" 100 "$(cat "$R"/proc/sys/vm/swappiness)"
check "tweak kept for later" 30 "$(prop persist.andr36oid.kt.swappiness)"

# --- paused by hand: defaults; back on: applied
setp persist.andr36oid.kt.paused 1
run apply
check "paused by hand" 100 "$(cat "$R"/proc/sys/vm/swappiness)"
setp persist.andr36oid.kt.paused 0
run apply
check "back on" 30 "$(cat "$R"/proc/sys/vm/swappiness)"

echo "passed $pass, failed $fail (shell: $MKSH)"
[ "$fail" = 0 ]
