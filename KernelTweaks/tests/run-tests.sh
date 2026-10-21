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
printf '%s\n' "\$2" >"$T/props/\$1"
EOF
	printf '#!/bin/sh\nexit 0\n' >"$T/bin/log"
	# the real sync can hang for minutes on a busy build host
	printf '#!/bin/sh\nexit 0\n' >"$T/bin/sync"
	chmod +x "$T/bin/"*
}

prop() { cat "$T/props/$1" 2>/dev/null; }
setp() { printf '%s\n' "$2" >"$T/props/$1"; }
# TMPDIR points nowhere: on Android there is no /tmp and init sets no TMPDIR
run() { TMPDIR=$T/no-such-dir KT_BOOT=$T/boot KT_ROOT=$R PATH="$T/bin:$PATH" "$MKSH" "$HERE/../helper/andr36oid-ktweaks.sh" "$@"; }

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

# --- start options: defaults from the command line, kernel-tweaks.txt for the next start
setup
run boot
check "serial default off" off "$(prop sys.andr36oid.ktd.serial_console)"
check "loglevel default" 4 "$(prop sys.andr36oid.ktd.boot_log_level)"
check "cma default" default "$(prop sys.andr36oid.ktd.cma_size)"
check "cpu idle default on" on "$(prop sys.andr36oid.ktd.cpu_idle)"
check "usb autosuspend default on" on "$(prop sys.andr36oid.ktd.usb_autosuspend)"
check "boot timing default off" 0 "$(prop sys.andr36oid.ktd.boot_timing)"
check "no file without tweaks" no "$([ -e "$T/boot/kernel-tweaks.txt" ] && echo yes || echo no)"
setp persist.andr36oid.kt.serial_console on
setp persist.andr36oid.kt.cpu_idle off
setp persist.andr36oid.kt.cma_size 96
run apply
check "file written" "fiq_debugger.disable=0 console=ttyS2,115200n8 androidboot.console=ttyS2 cma=96M cpuidle.off=1" \
	"$(sed -n 's/^kt_args=//p' "$T/boot/kernel-tweaks.txt")"
check "args reported" "fiq_debugger.disable=0 console=ttyS2,115200n8 androidboot.console=ttyS2 cma=96M cpuidle.off=1" \
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
check "started with" "$args" "$(prop sys.andr36oid.kt.started_with)"

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

# --- the app: every tweak in Tweaks.java has a risk level (the app throws without one)
J=$HERE/../src/org/andr36oid/kerneltweaks/Tweaks.java
check "every tweak has a level" "$(grep -c '^ *\.add(' "$J")" \
	"$(grep -c '^ *\.level(Tweak\.\(SAFE\|LAST_RESORT\|RISKY\))' "$J")"

echo "passed $pass, failed $fail (shell: $MKSH)"
[ "$fail" = 0 ]
