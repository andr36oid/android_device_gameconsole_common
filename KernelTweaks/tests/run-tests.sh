#!/bin/bash
# Runs andr36oid-ktweaks.sh in mksh against a fake /sys and /proc, with getprop/setprop
# stubs that keep properties in files. Usage: tests/run-tests.sh [path to mksh]
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
MKSH=${1:-/andr36oid/work/tmp/o2-shellcheck/mksh/mksh}
[ -x "$MKSH" ] || MKSH=$(command -v mksh || command -v bash)
TOYBOX=${TOYBOX:-/andr36oid/src/prebuilts/build-tools/linux-x86/bin/toybox}
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
	echo '100 100' >"$R"/sys/devices/platform/odroidgo3-joypad/stick_sensitivity
	echo 10 >"$R"/sys/devices/platform/odroidgo3-joypad/poll_interval
	printf '4\t4\t1\t7\n' >"$R"/proc/sys/kernel/printk
	echo 7 >"$R"/proc/sys/kernel/panic
	echo 0 >"$R"/proc/sys/kernel/panic_on_oops
	echo 60 >"$R"/sys/module/rcupdate/parameters/rcu_cpu_stall_timeout
	echo 0 >"$R"/sys/module/rk817_charger/parameters/dbg_level
	echo 0 >"$R"/sys/module/rk817_battery/parameters/dbg_level
	# no mmcblk1, no hp_det_flip, no codec: not on this "console"
	# the command line boot.ini gives (no serial console), BOOT as a folder
	echo "fw_devlink=permissive rootwait loglevel=4 printk.time=1 vt.global_cursor_default=0 fiq_debugger.disable=1" >"$R"/proc/cmdline
	rm -rf "${T:?}/boot"
	mkdir -p "$T/boot"

	cat >"$T/bin/getprop" <<EOF
#!/bin/sh
cat "$T/props/\$1" 2>/dev/null
EOF
	cat >"$T/bin/setprop" <<EOF
#!/bin/sh
[ "\${#2}" -lt 92 ] || { echo "\$1" >>"$T/long-props"; exit 1; }
printf '%s\n' "\$2" >"$T/props/\$1"
EOF
	printf '#!/bin/sh\nexit 0\n' >"$T/bin/log"
	# the real sync can hang for minutes on a busy build host
	printf '#!/bin/sh\nexit 0\n' >"$T/bin/sync"
	chmod +x "$T/bin/"*
	if [ -x "$TOYBOX" ]; then
		[ -e "$T/lib64" ] || ln -s "$(dirname "$TOYBOX")/../lib64" "$T/lib64"
		for cmd in cat cp cut date dd grep head mkdir mv rm rmdir sed tr wc; do
			ln -s "$TOYBOX" "$T/bin/$cmd"
		done
	fi
}

prop() { cat "$T/props/$1" 2>/dev/null; }
setp() { printf '%s\n' "$2" >"$T/props/$1"; }
# TMPDIR points nowhere: on Android there is no /tmp and init sets no TMPDIR
run() { TMPDIR=$T/no-such-dir KT_BOOT=$T/boot KT_ROOT=$R PATH="$T/bin:$PATH" "$MKSH" "$HERE/../helper/andr36oid-ktweaks.sh" "$@"; }

# The stub rejects oversized values, as Android's property service does.
setup
value=$(printf '%092d' 0)
PATH="$T/bin:$PATH" setprop sys.test "$value" && echo "unexpected property success"
check "property stub refuses 92 bytes" no "$([ -e "$T/props/sys.test" ] && echo yes || echo no)"
rm -f "$T/long-props"

# --- boot notes the defaults
setup
run boot
check "default bracket" deep "$(prop sys.andr36oid.ktd.sleep_type)"
check "default pm_test" none "$(prop sys.andr36oid.ktd.sleep_test)"
check "default cores" 4 "$(prop sys.andr36oid.ktd.cores)"
check "default thermal zone by type" 85000 "$(prop sys.andr36oid.ktd.heat_limit)"
check "default printk first field" 4 "$(prop sys.andr36oid.ktd.log_level)"
check "default axes" "0 0 1 0" "$(prop sys.andr36oid.ktd.stick_invert)"
check "default stick sensitivity" "100 100" "$(prop sys.andr36oid.ktd.stick_sensitivity)"
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
setp persist.andr36oid.kt.stick_sensitivity "80 100"
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
check "stick sensitivity written" "80 100" "$(cat "$R"/sys/devices/platform/odroidgo3-joypad/stick_sensitivity)"
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

# --- start options: defaults from the command line, kernel-tweaks.txt for the next start
setup
run boot
check "serial default off" off "$(prop sys.andr36oid.ktd.serial_console)"
check "loglevel default" 4 "$(prop sys.andr36oid.ktd.boot_log_level)"
check "cma default" default "$(prop sys.andr36oid.ktd.cma_size)"
check "cpu idle default on" on "$(prop sys.andr36oid.ktd.cpu_idle)"
check "boot boost default on" on "$(prop sys.andr36oid.ktd.boot_boost)"
check "usb autosuspend default on" on "$(prop sys.andr36oid.ktd.usb_autosuspend)"
check "boot timing default off" 0 "$(prop sys.andr36oid.ktd.boot_timing)"
check "no file without tweaks" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"
setp persist.andr36oid.kt.serial_console on
setp persist.andr36oid.kt.cpu_idle off
setp persist.andr36oid.kt.cma_size 96
run apply
check "file written" "fiq_debugger.disable=0 console=ttyS2,115200n8 androidboot.console=ttyS2 cma=96M cpuidle.off=1" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
check "args reported within property limit" "fiq_debugger.disable=0 console=ttyS2,115200n8 androidboot.console=ttyS2 cma=96M cpuidle.off" \
	"$(prop sys.andr36oid.kt.args)"
check "current stays until a restart" off "$(prop sys.andr36oid.ktc.serial_console)"
check "start options never fail" "" "$(prop sys.andr36oid.kt.failed | tr -d ' ')"

# --- the next start: U-Boot added the file's args, the defaults stay the console's own
args=$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")
echo "$(cat "$R"/proc/cmdline) $args" >"$R"/proc/cmdline
run boot
check "default without the added part" off "$(prop sys.andr36oid.ktd.serial_console)"
check "current with it" on "$(prop sys.andr36oid.ktc.serial_console)"
check "cma current" 96 "$(prop sys.andr36oid.ktc.cma_size)"
check "cpu idle current" off "$(prop sys.andr36oid.ktc.cpu_idle)"
check "started with" "${args:0:91}" "$(prop sys.andr36oid.kt.started_with)"

# --- back to the defaults: the file goes
setp persist.andr36oid.kt.serial_console ""
setp persist.andr36oid.kt.cpu_idle ""
setp persist.andr36oid.kt.cma_size ""
run apply
check "file removed" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"

# --- paused: the start options go too, so a start that broke starts clean
setp persist.andr36oid.kt.timer_tick off
run apply
check "timer tick written" "nohz=off" "$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
setp persist.andr36oid.kt.paused crash
run apply
check "paused removes the file" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"

# --- extra refresh rates (Settings > Display > Panel overclocking)
setup
run boot
check "extra rates default none" none "$(prop sys.andr36oid.ktd.extra_rates)"
setp persist.andr36oid.kt.extra_rates "70,77.5,90"
run apply
check "extra rates written" "panel_simple.extra_rates=70,77.5,90" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
args=$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")
echo "$(cat "$R"/proc/cmdline) $args" >"$R"/proc/cmdline
run boot
check "extra rates current" "70,77.5,90" "$(prop sys.andr36oid.ktc.extra_rates)"
check "extra rates default stays none" none "$(prop sys.andr36oid.ktd.extra_rates)"
check "extra rates count as a tweak" 1 "$(prop persist.andr36oid.kt.tries)"
setp persist.andr36oid.kt.extra_rates "75 quiet init=/bin/sh"
run apply
check "odd extra rates left off" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"
setp persist.andr36oid.kt.extra_rates "75"
setp persist.andr36oid.kt.cpu_idle off
run apply
check "extra rates with another option" "cpuidle.off=1 panel_simple.extra_rates=75" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
setp persist.andr36oid.kt.extra_rates none
setp persist.andr36oid.kt.cpu_idle ""
run apply
check "extra rates removed" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"
setp persist.andr36oid.kt.boot_boost off
run apply
check "boot boost off" "androidboot.andr36oid_boot_boost=0" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
setp persist.andr36oid.kt.boot_boost ""
run apply

# --- extra buttons (Controls > Buttons on this console > Find an unknown button)
setup
run boot
check "extra buttons default none" none "$(prop sys.andr36oid.ktd.extra_buttons)"
setp persist.andr36oid.kt.extra_buttons "2A5,3C1H"
run apply
check "extra buttons written" "odroidgo3_joypad.extra_gpios=2A5,3C1H" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
args=$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")
echo "$(cat "$R"/proc/cmdline) $args" >"$R"/proc/cmdline
run boot
check "extra buttons current" "2A5,3C1H" "$(prop sys.andr36oid.ktc.extra_buttons)"
check "extra buttons default stays none" none "$(prop sys.andr36oid.ktd.extra_buttons)"
for bad in "2A5 init=/bin/sh" "2a5" "2E1" "2A5;reboot" "2A5,X"; do
	setp persist.andr36oid.kt.extra_buttons "$bad"
	run apply
	check "odd extra buttons '$bad' left off" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"
done
setp persist.andr36oid.kt.extra_buttons "1D0L"
setp persist.andr36oid.kt.extra_rates "75"
run apply
check "extra buttons with extra rates" "panel_simple.extra_rates=75 odroidgo3_joypad.extra_gpios=1D0L" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
setp persist.andr36oid.kt.extra_buttons none
setp persist.andr36oid.kt.extra_rates ""
run apply
check "extra buttons removed" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"

# --- always start with the boot log (Settings > Personalization): a start option
setup
run boot
check "boot log default off" off "$(prop sys.andr36oid.ktd.boot_log)"
setp persist.andr36oid.kt.boot_log on
run apply
check "boot log on written" "console=tty0 ignore_loglevel printk.devkmsg=on androidboot.bootlog=1" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
args=$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")
echo "$(cat "$R"/proc/cmdline) $args" >"$R"/proc/cmdline
run boot
check "boot log current on" on "$(prop sys.andr36oid.ktc.boot_log)"
check "boot log default stays off" off "$(prop sys.andr36oid.ktd.boot_log)"
setp persist.andr36oid.kt.boot_log off
run apply
check "boot log off removes the file" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"

# --- second SD card slot (Settings > Storage) and Wi-Fi/Bluetooth drivers: start options
setup
run boot
check "sd2 slot default on" on "$(prop sys.andr36oid.ktd.sd2_slot)"
check "radios default on" on "$(prop sys.andr36oid.ktd.radios)"
setp persist.andr36oid.kt.sd2_slot off
run apply
check "sd2 slot off written" "dw_mmc_rockchip.second_slot=0" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
setp persist.andr36oid.kt.radios off
run apply
check "radios off with sd2 off" "module.no_radios=1 dw_mmc_rockchip.second_slot=0" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
args=$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")
echo "$(cat "$R"/proc/cmdline) $args" >"$R"/proc/cmdline
run boot
check "sd2 slot current off" off "$(prop sys.andr36oid.ktc.sd2_slot)"
check "radios current off" off "$(prop sys.andr36oid.ktc.radios)"
check "sd2 slot default stays on" on "$(prop sys.andr36oid.ktd.sd2_slot)"
setp persist.andr36oid.kt.sd2_slot on
setp persist.andr36oid.kt.radios ""
run apply
check "sd2 slot and radios back on" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"
# switched off before the slot had a kernel option: carried over at start
setup
setp persist.andr36oid.sd2_mount 0
run boot
check "old sd2 switch carried over" off "$(prop persist.andr36oid.kt.sd2_slot)"
check "old sd2 switch written" "dw_mmc_rockchip.second_slot=0" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
setup
setp persist.andr36oid.sd2_mount 0
setp persist.andr36oid.kt.sd2_slot on
run boot
check "sd2 switch set since is kept" on "$(prop persist.andr36oid.kt.sd2_slot)"

# --- built-in eMMC: a start option only where the device tree marks it
setup
run boot
check "eMMC not offered without the DT mark" "" "$(prop sys.andr36oid.ktd.builtin_emmc)"
check "eMMC speed not offered without the DT mark" "" "$(prop sys.andr36oid.ktd.emmc_speed)"
check "eMMC power not offered without the DT mark" "" "$(prop sys.andr36oid.ktd.emmc_power)"
setp persist.andr36oid.kt.builtin_emmc on
run apply
check "eMMC not written without the DT mark" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"
setup
mkdir -p "$R/proc/device-tree/dwmmc@ff390000"
: >"$R/proc/device-tree/dwmmc@ff390000/andr36oid,builtin-emmc"
run boot
check "eMMC default off" off "$(prop sys.andr36oid.ktd.builtin_emmc)"
setp persist.andr36oid.kt.builtin_emmc on
run apply
check "eMMC on written" "dw_mmc_rockchip.builtin_emmc=1" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
args=$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")
echo "$(cat "$R"/proc/cmdline) $args" >"$R"/proc/cmdline
run boot
check "eMMC current on" on "$(prop sys.andr36oid.ktc.builtin_emmc)"
check "eMMC default stays off" off "$(prop sys.andr36oid.ktd.builtin_emmc)"
check "eMMC speed default" hs "$(prop sys.andr36oid.ktd.emmc_speed)"
setp persist.andr36oid.kt.emmc_speed hs200
run apply
check "eMMC speed written" "dw_mmc_rockchip.builtin_emmc=1 dw_mmc_rockchip.emmc_speed=hs200" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
setp persist.andr36oid.kt.emmc_speed hs
check "eMMC power default" off "$(prop sys.andr36oid.ktd.emmc_power)"
setp persist.andr36oid.kt.emmc_power on
run apply
check "eMMC power written" "dw_mmc_rockchip.builtin_emmc=1 dw_mmc_rockchip.emmc_power=1" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
setp persist.andr36oid.kt.emmc_power off
run apply
# boot.ini puts the boot log options (or just a space) after kernel-tweaks.txt's
echo "$(cat "$R"/proc/cmdline) loglevel=7 " >"$R"/proc/cmdline
run boot
check "eMMC default off, options not last" off "$(prop sys.andr36oid.ktd.builtin_emmc)"
check "eMMC still written, options not last" "dw_mmc_rockchip.builtin_emmc=1" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
setp persist.andr36oid.kt.builtin_emmc off
run apply
check "eMMC back off" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"
# an eMMC install: its boot.ini switches the eMMC on for good, no switch for it here
setup
mkdir -p "$R/proc/device-tree/dwmmc@ff390000"
: >"$R/proc/device-tree/dwmmc@ff390000/andr36oid,builtin-emmc"
echo "$(cat "$R"/proc/cmdline) dw_mmc_rockchip.builtin_emmc=system" >"$R"/proc/cmdline
run boot
check "eMMC switch not offered on an eMMC install" "" "$(prop sys.andr36oid.ktd.builtin_emmc)"
check "eMMC speed still offered on an eMMC install" hs "$(prop sys.andr36oid.ktd.emmc_speed)"
setp persist.andr36oid.kt.builtin_emmc on
setp persist.andr36oid.kt.emmc_speed hs200
run apply
check "eMMC switch never written on an eMMC install" "dw_mmc_rockchip.emmc_speed=hs200" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
# fresh data with kernel-tweaks.txt on BOOT (an eMMC install's first start): kept
setup
mkdir -p "$R/proc/device-tree/dwmmc@ff390000"
: >"$R/proc/device-tree/dwmmc@ff390000/andr36oid,builtin-emmc"
mkdir -p "$T/boot"
printf 'kt_args=dw_mmc_rockchip.emmc_speed=hs200\n' >"$T/boot/kernel-tweaks.txt"
echo "$(cat "$R"/proc/cmdline) dw_mmc_rockchip.emmc_speed=hs200 dw_mmc_rockchip.builtin_emmc=system" >"$R"/proc/cmdline
run boot
check "fresh data takes kernel-tweaks.txt over" hs200 "$(prop persist.andr36oid.kt.emmc_speed)"
check "fresh data keeps kernel-tweaks.txt" "dw_mmc_rockchip.emmc_speed=hs200" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
# with tweaks saved, the saved ones win as before
setp persist.andr36oid.kt.emmc_speed ddr52
run boot
check "saved tweaks win over the file" "dw_mmc_rockchip.emmc_speed=ddr52" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
rm -rf "$R/proc/device-tree/dwmmc@ff390000"

# --- storage layer: sdcardfs by default, FUSE as a start option
setup
run boot
check "storage default sdcardfs" sdcardfs "$(prop sys.andr36oid.ktd.storage)"
setp persist.andr36oid.kt.storage fuse
run apply
check "storage fuse written" "androidboot.storage_fuse=1" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
echo "$(cat "$R"/proc/cmdline) androidboot.storage_fuse=1" >"$R"/proc/cmdline
run boot
check "storage current fuse" fuse "$(prop sys.andr36oid.ktc.storage)"
setp persist.andr36oid.kt.storage sdcardfs
run apply
check "storage back to sdcardfs" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"

# --- FUSE driver: on by default, off as a start option, never off with FUSE storage
setup
run boot
check "fuse default on" on "$(prop sys.andr36oid.ktd.fuse)"
setp persist.andr36oid.kt.fuse off
run apply
check "fuse off written" "fuse.disable=1" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
echo "$(cat "$R"/proc/cmdline) fuse.disable=1" >"$R"/proc/cmdline
run boot
check "fuse current off" off "$(prop sys.andr36oid.ktc.fuse)"
check "fuse default stays on" on "$(prop sys.andr36oid.ktd.fuse)"
setp persist.andr36oid.kt.storage fuse
run apply
check "fuse stays on with FUSE storage" "androidboot.storage_fuse=1" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
setp persist.andr36oid.kt.storage ""
run apply
check "fuse off again with sdcardfs" "fuse.disable=1" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
setp persist.andr36oid.kt.fuse ""
run apply
check "fuse back on" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"
# a console whose own command line has FUSE storage: fuse off isn't written either
setup
echo "$(cat "$R"/proc/cmdline) androidboot.storage_fuse=1" >"$R"/proc/cmdline
run boot
setp persist.andr36oid.kt.fuse off
run apply
check "fuse stays on with FUSE storage by default" no \
	"$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"

# --- crash log: two knobs while running, on/off and the split as start options
setup
mkdir -p "$R"/sys/module/ramoops/parameters
echo 1 >"$R"/sys/module/ramoops/parameters/dump_oops
echo N >"$R"/sys/module/printk/parameters/always_kmsg_dump
run boot
check "crash oops default" 1 "$(prop sys.andr36oid.ktd.crash_oops)"
check "restart log default" N "$(prop sys.andr36oid.ktd.crash_restart_log)"
check "crash log default on" on "$(prop sys.andr36oid.ktd.crash_log)"
check "crash split default balanced" balanced "$(prop sys.andr36oid.ktd.crash_split)"
setp persist.andr36oid.kt.crash_oops 0
setp persist.andr36oid.kt.crash_restart_log Y
run apply
check "dump_oops written" 0 "$(cat "$R"/sys/module/ramoops/parameters/dump_oops)"
check "always_kmsg_dump written" Y "$(cat "$R"/sys/module/printk/parameters/always_kmsg_dump)"
check "no start options for them" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"
setp persist.andr36oid.kt.crash_split kernel
run apply
check "more kernel log written" "ramoops.record_size=0x40000 ramoops.console_size=0x80000 ramoops.pmsg_size=0x20000" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
args=$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")
echo "$(cat "$R"/proc/cmdline) $args" >"$R"/proc/cmdline
# a restart: the kernel starts with its own values again
echo 1 >"$R"/sys/module/ramoops/parameters/dump_oops
echo N >"$R"/sys/module/printk/parameters/always_kmsg_dump
run boot
check "runtime tweaks applied again" 0 "$(cat "$R"/sys/module/ramoops/parameters/dump_oops)"
check "split current kernel" kernel "$(prop sys.andr36oid.ktc.crash_split)"
check "split default stays balanced" balanced "$(prop sys.andr36oid.ktd.crash_split)"
setp persist.andr36oid.kt.crash_split android
setp persist.andr36oid.kt.crash_log off
run apply
check "more android log and off written" \
	"ramoops.disable=1 ramoops.record_size=0x20000 ramoops.console_size=0x40000 ramoops.pmsg_size=0x80000" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
args=$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")
echo "fw_devlink=permissive rootwait loglevel=4 $args" >"$R"/proc/cmdline
echo 1 >"$R"/sys/module/ramoops/parameters/dump_oops
echo N >"$R"/sys/module/printk/parameters/always_kmsg_dump
run boot
check "crash log current off" off "$(prop sys.andr36oid.ktc.crash_log)"
check "split current android" android "$(prop sys.andr36oid.ktc.crash_split)"
setp persist.andr36oid.kt.crash_split ""
setp persist.andr36oid.kt.crash_log ""
setp persist.andr36oid.kt.crash_oops ""
setp persist.andr36oid.kt.crash_restart_log ""
run apply
check "crash options removed" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"
check "dump_oops back" 1 "$(cat "$R"/sys/module/ramoops/parameters/dump_oops)"
check "always_kmsg_dump back" N "$(cat "$R"/sys/module/printk/parameters/always_kmsg_dump)"
# sizes boot.ini put there itself: shown as they are, none of the three
echo "rootwait ramoops.record_size=0x10000" >"$R"/proc/cmdline
run boot
check "other sizes are custom" custom "$(prop sys.andr36oid.ktd.crash_split)"
echo "rootwait ramoops.disable=Y" >"$R"/proc/cmdline
run boot
check "disable=Y is off" off "$(prop sys.andr36oid.ktd.crash_log)"

# --- an older boot.ini with the serial console on
setup
echo "rootwait console=ttyS2,115200n8 loglevel=4 androidboot.console=ttyS2" >"$R"/proc/cmdline
run boot
check "old boot.ini: serial on" on "$(prop sys.andr36oid.ktd.serial_console)"
echo "rootwait" >"$R"/proc/cmdline; run boot
check "no loglevel means 7" 7 "$(prop sys.andr36oid.ktd.boot_log_level)"

# --- the 5 second refresh rate preview passes the rate on and answers
hzp() { KT_ROOT=$R PATH="$T/bin:$PATH" "$MKSH" "$HERE/../helper/andr36oid-hz-preview.sh"; }
setup
setp sys.andr36oid.hzp.request 11
setp sys.andr36oid.hzp.rate 77.5
hzp
check "preview without the kernel file" "11 missing" "$(prop sys.andr36oid.hzp.result)"
mkdir -p "$R"/sys/devices/platform/ff450000.dsi
: >"$R"/sys/devices/platform/ff450000.dsi/preview_rate
hzp
check "preview answered" "11 ok" "$(prop sys.andr36oid.hzp.result)"
check "preview rate written" 77.5 "$(cat "$R"/sys/devices/platform/ff450000.dsi/preview_rate)"
for bad in "" abc 7.7.7 .5 "75; reboot" "-1"; do
	: >"$R"/sys/devices/platform/ff450000.dsi/preview_rate
	setp sys.andr36oid.hzp.rate "$bad"
	hzp
	check "preview refuses '$bad'" "11 invalid" "$(prop sys.andr36oid.hzp.result)"
	check "preview leaves '$bad' out" "" "$(cat "$R"/sys/devices/platform/ff450000.dsi/preview_rate)"
done
setp sys.andr36oid.hzp.request 12
setp sys.andr36oid.hzp.rate 0
hzp
check "preview ended" "12 ok" "$(prop sys.andr36oid.hzp.result)"
rm "$R"/sys/devices/platform/ff450000.dsi/preview_rate
mkdir "$R"/sys/devices/platform/ff450000.dsi/preview_rate
setp sys.andr36oid.hzp.rate 90
hzp
check "preview write that fails" "12 failed" "$(prop sys.andr36oid.hzp.result)"

# --- compressed memory: size and compressor change with zram off and reset
setup
mkdir -p "$R"/sys/block/zram0
printf 'MemTotal:        1000000 kB\n' >"$R"/proc/meminfo
echo $((750000 * 1024)) >"$R"/sys/block/zram0/disksize
echo 'lzo [lz4] deflate' >"$R"/sys/block/zram0/comp_algorithm
: >"$R"/sys/block/zram0/reset
SWAPS_HEAD='Filename Type Size Used Priority'
printf '%s\n/dev/block/zram0 partition 750000 0 -2\n' "$SWAPS_HEAD" >"$R"/proc/swaps
: >"$T/zram.log"
printf '#!/bin/sh\necho "swapoff $1" >>"%s"\necho "%s" >"%s"\n' "$T/zram.log" "$SWAPS_HEAD" "$R/proc/swaps" >"$T/bin/swapoff"
printf '#!/bin/sh\necho "mkswap $1" >>"%s"\n' "$T/zram.log" >"$T/bin/mkswap"
printf '#!/bin/sh\necho "swapon $1" >>"%s"\necho "$1 partition 0 0 -2" >>"%s"\n' "$T/zram.log" "$R/proc/swaps" >"$T/bin/swapon"
chmod +x "$T/bin/swapoff" "$T/bin/mkswap" "$T/bin/swapon"
run boot
check "zram size default" 75 "$(prop sys.andr36oid.ktd.zram_size)"
check "zram compressor default" lz4 "$(prop sys.andr36oid.ktd.zram_algorithm)"
check "zram left alone without tweaks" "" "$(cat "$T/zram.log")"
setp sys.boot_completed 1
setp persist.andr36oid.kt.zram_size 50
run apply
check "zram resized" $((500000 * 1024)) "$(cat "$R"/sys/block/zram0/disksize)"
check "zram off, reset, on" "swapoff /dev/block/zram0 mkswap /dev/block/zram0 swapon /dev/block/zram0" \
	"$(tr '\n' ' ' <"$T/zram.log" | sed 's/ $//')"
check "zram reset" 1 "$(cat "$R"/sys/block/zram0/reset)"
check "zram size reported" 50 "$(prop sys.andr36oid.ktc.zram_size)"
setp persist.andr36oid.kt.zram_algorithm lzo
run apply
check "zram compressor" lzo "$(cat "$R"/sys/block/zram0/comp_algorithm)"
check "zram compressor reported" lzo "$(prop sys.andr36oid.ktc.zram_algorithm)"
check "zram size kept with the compressor" $((500000 * 1024)) "$(cat "$R"/sys/block/zram0/disksize)"
setp persist.andr36oid.kt.zram_size 0
run apply
check "zram off" 0 "$(grep -c zram0 "$R"/proc/swaps)"
check "zram off reported" 0 "$(prop sys.andr36oid.ktc.zram_size)"
check "zram nothing failed" "" "$(prop sys.andr36oid.kt.failed)"
setp persist.andr36oid.kt.zram_size ""
setp persist.andr36oid.kt.zram_algorithm ""
run apply
check "zram back to default" "75 lz4 1" "$(prop sys.andr36oid.ktc.zram_size) $(cat "$R"/sys/block/zram0/comp_algorithm) $(grep -c zram0 "$R"/proc/swaps)"
setup
run boot
check "no zram: no tweak" "" "$(prop sys.andr36oid.ktd.zram_size)"

# --- the app: every tweak in Tweaks.java has a risk level (the app throws without one)
J=$HERE/../src/org/andr36oid/kerneltweaks/Tweaks.java
check "every tweak has a level" "$(grep -c '^ *\.add(' "$J")" \
	"$(grep -c '^ *\.level(Tweak\.\(SAFE\|LAST_RESORT\|RISKY\))' "$J")"

# --- profile export and bounded import snapshots
setup
mkdir -p "$T/profiles" "$T/easyroms" "$T/download" "$T/builtin" \
	"$R/proc/device-tree/dsi@ff450000/panel@0"
printf 'Example clone\0' >"$R/proc/device-tree/model"
printf 'example,clone\0rockchip,rk3326\0' >"$R/proc/device-tree/compatible"
printf 'example,panel8\0' >"$R/proc/device-tree/dsi@ff450000/panel@0/compatible"
printf 'Experimental/EE-V12-Var1-Panel8\r\n' >"$T/boot/andr36oid-profile.txt"
printf 'Kernel Tweaks profile, no personal data.\n' >"$T/profiles/readme"
setp ro.andr36oid.version test-version
setp persist.andr36oid.kt.swappiness 30
setp persist.andr36oid.kt.stick_invert '1 0 1 0'
setp persist.andr36oid.kt.cpu_idle off
setp persist.andr36oid.kt.extra_rates 70,77.5
setp persist.andr36oid.kt.paused crash
setp persist.andr36oid.kt.tries 2
setp sys.andr36oid.kt.sleep_test core

profile_run() {
	printf '%s\n' "$1" >"$T/profiles/request"
	KT_PROFILE_DIR=$T/profiles KT_PROFILE_EASYROMS=$T/easyroms \
		KT_PROFILE_DOWNLOAD=$T/download KT_PROFILE_BUILTIN=$T/builtin \
		KT_BOOT=${2-$T/boot} KT_BOOT_DEVS=$T/no-dev KT_BOOT_LOCK=$T/profile-lock \
		KT_BOOT_MNT=$T/profile-mount KT_ROOT=$R PATH="$T/bin:$PATH" \
		"$MKSH" "$HERE/../helper/andr36oid-ktweaks.sh" profiles
}

profile_run export
check "profile exported" 'done 0' "$(cat "$T/profiles/status")"
saved=$(cat "$T/profiles/saved")
check "profile BOOT preferred" BOOT "${saved%%/*}"
exported=$T/boot/${saved#*/}
check "profile saved file" yes "$(test -f "$exported" && echo yes)"
check "profile format" 'format=andr36oid-ktprofile-1' "$(sed -n '2p' "$exported")"
check "profile model" 'model=Example clone' "$(sed -n '/^model=/p' "$exported")"
check "profile compatible" 'compatible=example,clone rockchip,rk3326 ' "$(sed -n '/^compatible=/p' "$exported")"
check "profile BOOT identity" 'profile=Experimental/EE-V12-Var1-Panel8' "$(sed -n '/^profile=/p' "$exported")"
check "profile version" 'version=test-version' "$(sed -n '/^version=/p' "$exported")"
check "profile panel" 'panel=example,panel8 ' "$(sed -n '/^panel=/p' "$exported")"
check "profile only set persistent knobs" 4 "$(grep -c '^persist\.' "$exported")"
check "profile no counters/temporary/defaults" '' "$(grep -E '(paused|tries|sleep_test|heat_limit)=' "$exported")"
check "profile absolute axes" 'persist.andr36oid.kt.stick_invert=1 0 1 0' "$(grep '^persist.*stick_invert=' "$exported")"
check "profile other settings page knob" 'persist.andr36oid.kt.extra_rates=70,77.5' "$(grep '^persist.*extra_rates=' "$exported")"
check "profile README" '# Kernel Tweaks profile, no personal data.' "$(head -n 1 "$exported")"
cp "$exported" "$T/profiles/exported.txt"

touch "$T/boot/.readonly"
setp andr36oid.info.panel Experimental/EE-V12-Var1-Panel8
profile_run export
check "profile read-only BOOT fallback" EASYROMS "$(cut -d/ -f1 "$T/profiles/saved")"
check "profile panel from DTB hint" 'panel=Experimental/EE-V12-Var1-Panel8' \
	"$(grep '^panel=' "$T/profiles/identity")"
# A symlinked destination must not be followed, even if it is writable.
rm -rf "$T/easyroms/andr36oid-ktprofiles"
ln -s "$T/boot" "$T/easyroms/andr36oid-ktprofiles"
profile_run export
check "profile Download fallback" Download "$(cut -d/ -f1 "$T/profiles/saved")"
rm -rf "$T/download"
profile_run export
check "profile no writable destination" 'error copy' "$(cat "$T/profiles/status")"
mkdir -p "$T/download/andr36oid-ktprofiles"
# With no BOOT device, never mount anything on the host and release the shared lock.
printf '#!/bin/sh\nexit 1\n' >"$T/bin/mount"
printf '#!/bin/sh\nexit 0\n' >"$T/bin/umount"
chmod +x "$T/bin/mount" "$T/bin/umount"
profile_run export ''
check "profile missing BOOT fallback" Download "$(cut -d/ -f1 "$T/profiles/saved")"
check "profile missing BOOT identity empty" 'profile=' "$(grep '^profile=' "$T/profiles/identity")"
check "profile missing BOOT releases lock" no "$(test -d "$T/profile-lock" && echo yes || echo no)"
rm -f "$T/download/andr36oid-ktprofiles/"*.txt
rm "$T/easyroms/andr36oid-ktprofiles"
mkdir -p "$T/easyroms/andr36oid-ktprofiles"
cp "$exported" "$T/easyroms/andr36oid-ktprofiles/shared.TXT"
cp "$exported" "$T/download/andr36oid-ktprofiles/shared.txt"
cp "$exported" "$T/builtin/example.txt"
ln -s "$exported" "$T/boot/andr36oid-ktprofiles/link.txt"
printf x >"$T/boot/andr36oid-ktprofiles/bad name.txt"
: >"$T/boot/andr36oid-ktprofiles/empty.txt"
dd if=/dev/zero of="$T/boot/andr36oid-ktprofiles/large.txt" bs=65537 count=1 2>/dev/null
profile_run scan
check "profile rejects links/names/empty/large" 'done 4' "$(cat "$T/profiles/status")"
check "profile scans all roots and built-ins" 'builtin BOOT EASYROMS Download' \
	"$(cut -f2 "$T/profiles/list" | tr '\n' ' ' | sed 's/ $//')"
check "profile staging snapshot" "$(cat "$exported")" "$(cat "$T/profiles/in/2.txt")"
printf 'changed after scan\n' >"$exported"
check "profile staged file independent" 'format=andr36oid-ktprofile-1' \
	"$(sed -n '2p' "$T/profiles/in/2.txt")"
check "profile scan never changes properties" '30 off crash 2 core' \
	"$(prop persist.andr36oid.kt.swappiness) $(prop persist.andr36oid.kt.cpu_idle) $(prop persist.andr36oid.kt.paused) $(prop persist.andr36oid.kt.tries) $(prop sys.andr36oid.kt.sleep_test)"
check "profile scan never writes boot args" no "$(test -e "$T/boot/kernel-tweaks.txt" && echo yes || echo no)"
rm -rf "$T/builtin" "$T/boot/andr36oid-ktprofiles" "$T/easyroms/andr36oid-ktprofiles" "$T/download/andr36oid-ktprofiles"
mkdir -p "$T/builtin"
for n in $(seq 1 102); do printf 'format=andr36oid-ktprofile-1\n' >"$T/builtin/test$n.txt"; done
profile_run scan
check "profile file count bounded" 100 "$(wc -l <"$T/profiles/list" | tr -d ' ')"
check "profile reports excess files" 'done 2' "$(cat "$T/profiles/status")"
check "profile stale copies removed" no "$(test -e "$T/profiles/in/101.txt" && echo yes || echo no)"
profile_run unknown
check "profile rejects unknown request" 'error request' "$(cat "$T/profiles/status")"

# Exercise the real volume lookup too; the path overrides above bypass it.
if command -v bwrap >/dev/null; then
	mkdir -p "$T/mnt/media_rw/LEGACY" "$T/mnt/media_rw/SELECTED"
	cat >"$T/bin/sm" <<EOF
#!/bin/sh
[ "\$*" = "list-volumes public" ] || exit 1
cat "$T/volumes"
EOF
	chmod +x "$T/bin/sm"
	for mode in legacy selected absent waiting; do
		rm -f "$T/props/sys.andr36oid.easyroms."*
		printf 'public:179,7 mounted LEGACY\npublic:179,103 mounted SELECTED\n' >"$T/volumes"
		case $mode in
		legacy) card=LEGACY ;;
		*)
			setp sys.andr36oid.easyroms.ready 1
			card=
			if [ "$mode" != absent ]; then
				setp sys.andr36oid.easyroms.id public:179,103
				card=SELECTED
			fi
			;;
		esac
		if [ "$mode" = waiting ]; then
			printf 'public:179,7 mounted LEGACY\npublic:179,103 checking SELECTED\n' >"$T/volumes"
			card=
		fi
		for drive in LEGACY SELECTED; do
			rm -rf "$T/mnt/media_rw/$drive/andr36oid-ktprofiles"
		done
		echo export >"$T/profiles/request"
		KT_PROFILE_DIR=$T/profiles KT_PROFILE_DOWNLOAD=$T/download KT_BOOT=$T/boot \
			KT_ROOT=$R bwrap --ro-bind / / --bind "$T" "$T" --bind "$T/mnt" /mnt --dev /dev \
			--setenv PATH "$T/bin:$PATH" --unsetenv KT_PROFILE_EASYROMS \
			"$MKSH" "$HERE/../helper/andr36oid-ktweaks.sh" profiles
		if [ -n "$card" ]; then
			check "profile $mode volume" EASYROMS "$(cut -d/ -f1 "$T/profiles/saved")"
			check "profile $mode card" 1 "$(find "$T/mnt/media_rw/$card" -name 'ktprofile-*.txt' | wc -l)"
		else
			check "profile $mode fallback" Download "$(cut -d/ -f1 "$T/profiles/saved")"
			check "profile $mode cards untouched" 0 "$(find "$T/mnt/media_rw" -name 'ktprofile-*.txt' | wc -l)"
		fi
	done
else
	echo "skip profile volume lookup: no bwrap"
fi

# Compile the real tweak catalog and parser against a small Context/R stub.
mkdir -p "$T/java/android/content" "$T/java/org/andr36oid/kerneltweaks" "$T/classes"
cat >"$T/java/android/content/Context.java" <<'EOF'
package android.content;
public class Context {
    public String getString(int id, Object... args) { return "string"; }
}
EOF
python3 - "$HERE/../res/values/strings.xml" "$T/java/org/andr36oid/kerneltweaks/R.java" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
with open(sys.argv[2], 'w') as out:
    out.write('package org.andr36oid.kerneltweaks; final class R {\n')
    for tag in ('string', 'plurals'):
        out.write('static final class ' + tag + ' {\n')
        for i, node in enumerate(root.findall(tag), 1):
            out.write('static final int %s = %d;\n' % (node.attrib['name'], i))
        out.write('}\n')
    out.write('}\n')
PY
SRC=$HERE/../src/org/andr36oid/kerneltweaks
if javac -encoding UTF-8 -d "$T/classes" "$T/java/android/content/Context.java" \
	"$T/java/org/andr36oid/kerneltweaks/R.java" "$SRC/Tweak.java" "$SRC/Tweaks.java" \
	"$SRC/Profile.java" "$HERE/ProfileTest.java" &&
	java -cp "$T/classes" org.andr36oid.kerneltweaks.ProfileTest "$T/profiles/exported.txt"; then
	check "profile Java tests" passed passed
else
	check "profile Java tests" passed failed
fi

check "no oversized property writes" no "$([ -s "$T/long-props" ] && echo yes || echo no)"
echo "passed $pass, failed $fail (shell: $MKSH)"
[ "$fail" = 0 ]
