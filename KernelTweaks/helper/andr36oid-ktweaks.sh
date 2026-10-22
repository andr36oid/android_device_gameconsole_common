#!/system/bin/sh
# Settings > System > Kernel Tweaks: the root part. Writes the tweaks to the kernel.
#
#   boot   (andr36oid-ktweaks-boot, once persist props are loaded): note every knob's
#          default in sys.andr36oid.ktd.<key>, then apply the saved tweaks
#   apply  (andr36oid-ktweaks, ctl.start from the app): apply again, until
#          sys.andr36oid.kt.request stops changing
#   profiles (andr36oid-ktprofiles): export or stage profile files for the app
#
# A tweak is persist.andr36oid.kt.<key> (kept after a restart) or, for the "once" knobs,
# sys.andr36oid.kt.<key> (this start only). Empty means the default. After a run every
# knob's value is in sys.andr36oid.ktc.<key>, keys that wouldn't take the value in
# sys.andr36oid.kt.failed, and the handled request in sys.andr36oid.kt.done.
#
# Boot protection: persist.andr36oid.kt.tries counts starts with tweaks on and is reset by
# init at sys.boot_completed. When two starts in a row didn't finish, the tweaks are
# paused (persist.andr36oid.kt.paused=crash, "1" when switched off in the app) until
# switched back on in the app.

# tests point this at a fake /sys and /proc
R=${KT_ROOT:-}

# key|kind|once|paths
#   plain    write the value to every path, read the first one that exists
#   bracket  "a [b] c" lists: read the bracketed one
#   first    first number of the file (kernel.printk)
#   cores    how many cores are online (cpu0 always is)
#   thermal  trip point <path> of the soc-thermal zone, in m°C
#   prop     a system property, not a kernel file (the value, 0 while unset)
#   zram     compressed memory: "size" in percent of RAM (0 off) or "algorithm"; a change
#            switches it off, resets it and switches it back on (zram_write)
#   boot     an option on the kernel command line (see boot_read/boot_args): it takes
#            effect at the next start, through BOOT/kernel-tweaks.txt
# extra_rates isn't in the Kernel Tweaks list: Settings > Display > Panel overclocking
# sets it, a comma list of Hz such as "70,77.5", "none" for none.
# extra_buttons isn't in the list either: Settings > Controls > Buttons on this console >
# Find an unknown button sets it, a comma list of pins such as "2A5,3C1H" (bank, port,
# pin, H when pressed is high), "none" for none.
# boot_log isn't in the list either: Settings > Personalization > Always start with the
# boot log sets it to "on": the kernel log on the screen from the kernel's start, and
# the boot animation's log screen (ro.boot.bootlog) instead of the animation.
# sd2_slot isn't in the list either: Settings > Storage > Second SD card off sets it to
# "off", and the kernel doesn't register the second card slot at the next start.
KNOBS='
sleep_type|bracket||/sys/power/mem_sleep
sleep_test|bracket|once|/sys/power/pm_test
sleep_console|plain||/sys/module/printk/parameters/console_suspend
sleep_log_times|plain||/sys/power/pm_print_times
sleep_log_debug|plain||/sys/power/pm_debug_messages
cores|cores||
heat_limit|thermal||1
perf_metrics_off|prop||sys.andr36oid.perf_metrics_off
boost_buttons|prop||persist.andr36oid.boost_off
boost_length|prop||persist.andr36oid.boost_ms
boost_launch|prop||persist.andr36oid.launch_boost_off
swappiness|plain||/proc/sys/vm/swappiness
cache_pressure|plain||/proc/sys/vm/vfs_cache_pressure
page_cluster|plain||/proc/sys/vm/page-cluster
zram_size|zram||size
zram_algorithm|zram||algorithm
sd1_scheduler|bracket||/sys/block/mmcblk0/queue/scheduler
sd1_readahead|plain||/sys/block/mmcblk0/queue/read_ahead_kb
sd2_scheduler|bracket||/sys/block/mmcblk1/queue/scheduler
sd2_readahead|plain||/sys/block/mmcblk1/queue/read_ahead_kb
stick_invert|plain||/sys/devices/platform/odroidgo3-joypad/stick_invert
stick_swap|plain||/sys/devices/platform/odroidgo3-joypad/stick_swap
stick_off|plain||/sys/devices/platform/odroidgo3-joypad/stick_off
max_volume|prop||sys.andr36oid.max_volume
poll_interval|plain||/sys/devices/platform/odroidgo3-joypad/poll_interval
hp_det_flip|plain||/sys/module/snd_soc_simple_card_utils/parameters/hp_det_flip
log_level|first||/proc/sys/kernel/printk
panic_restart|plain||/proc/sys/kernel/panic
oops_panic|plain||/proc/sys/kernel/panic_on_oops
stall_report|plain||/sys/module/rcupdate/parameters/rcu_cpu_stall_timeout
dc_det|plain||/sys/module/rk817_charger/parameters/dc_det_mode
input_nearest_step|plain||/sys/module/rk817_charger/parameters/input_nearest_step
power_chip_log|plain||/sys/module/rk817_charger/parameters/dbg_level /sys/module/rk817_battery/parameters/dbg_level /sys/module/snd_soc_rk817/parameters/dbg_level
crash_oops|plain||/sys/module/ramoops/parameters/dump_oops
crash_restart_log|plain||/sys/module/printk/parameters/always_kmsg_dump
serial_console|boot||serial_console
boot_log_level|boot||boot_log_level
boot_log|boot||boot_log
cma_size|boot||cma_size
cpu_idle|boot||cpu_idle
timer_tick|boot||timer_tick
boot_timing|boot||boot_timing
usb_autosuspend|boot||usb_autosuspend
extra_rates|boot||extra_rates
extra_buttons|boot||extra_buttons
crash_log|boot||crash_log
crash_split|boot||crash_split
radios|boot||radios
storage|boot||storage
fuse|boot||fuse
sd2_slot|boot||sd2_slot
builtin_emmc|boot||builtin_emmc
emmc_speed|boot||emmc_speed
emmc_power|boot||emmc_power
boot_boost|boot||boot_boost
'

# Start options. boot.ini reads kt_args=... from BOOT/kernel-tweaks.txt and puts it at
# the end of the kernel command line, where it wins over the same options before it.
# CMDLINE is what this start got, BASE the same without the part kernel-tweaks.txt
# added: the console's own default.
KT_FILE=kernel-tweaks.txt
BOOT_DEVS=${KT_BOOT_DEVS:-"/dev/block/by-name/BOOT /dev/block/platform/ff370000.dwmmc/by-name/BOOT /dev/block/mmcblk0p1"}
BOOT_MNT=${KT_BOOT_MNT:-/mnt/andr36oid-ktweaks-boot}
BOOT_LOCK=${KT_BOOT_LOCK:-/dev/andr36oid-boot.lock}
CMDLINE=$(cat "$R/proc/cmdline" 2>/dev/null)
BASE=$CMDLINE

# arg_last <name> <cmdline> ; the value of the last name=value
arg_last() {
	v=
	for a in $2; do
		case $a in "$1="*) v=${a#"$1="} ;; esac
	done
	echo "$v"
}

# arg_has <name> <cmdline> ; 1 when the bare option is there
arg_has() {
	for a in $2; do
		[ "$a" = "$1" ] && { echo 1; return; }
	done
	echo 0
}

# boot_read <key> <cmdline> ; the tweak's value as that command line has it
boot_read() {
	case $1 in
	serial_console)
		# off only when the debugger is switched off (older boot.ini had it on)
		[ "$(arg_last fiq_debugger.disable "$2")" = 1 ] && echo off || echo on ;;
	boot_log_level)
		v=$(arg_last loglevel "$2")
		echo "${v:-7}" ;;
	boot_log)
		[ "$(arg_last androidboot.bootlog "$2")" = 1 ] && echo on || echo off ;;
	cma_size)
		v=$(arg_last cma "$2")
		v=${v%[Mm]}
		echo "${v:-default}" ;;
	cpu_idle)
		[ "$(arg_last cpuidle.off "$2")" = 1 ] && echo off || echo on ;;
	timer_tick)
		[ "$(arg_last nohz "$2")" = off ] && echo off || echo on ;;
	boot_timing)
		arg_has initcall_debug "$2" ;;
	usb_autosuspend)
		v=$(arg_last usbcore.autosuspend "$2")
		[ "${v:-2}" -lt 0 ] 2>/dev/null && echo off || echo on ;;
	extra_rates)
		v=$(arg_last panel_simple.extra_rates "$2")
		echo "${v:-none}" ;;
	extra_buttons)
		v=$(arg_last odroidgo3_joypad.extra_gpios "$2")
		echo "${v:-none}" ;;
	radios)
		[ "$(arg_last module.no_radios "$2")" = 1 ] && echo off || echo on ;;
	storage)
		[ "$(arg_last androidboot.storage_fuse "$2")" = 1 ] && echo fuse || echo sdcardfs ;;
	fuse)
		[ "$(arg_last fuse.disable "$2")" = 1 ] && echo off || echo on ;;
	boot_boost)
		[ "$(arg_last androidboot.andr36oid_boot_boost "$2")" = 0 ] && echo off || echo on ;;
	sd2_slot)
		[ "$(arg_last dw_mmc_rockchip.second_slot "$2")" = 0 ] && echo off || echo on ;;
	builtin_emmc)
		# only consoles whose device tree has the eMMC with its pins free
		[ -e "$R/proc/device-tree/dwmmc@ff390000/andr36oid,builtin-emmc" ] || return 0
		case $(arg_last dw_mmc_rockchip.builtin_emmc "$2") in
		# andr36oid installed on it: always on (its boot.ini), no switch to offer
		system) return 0 ;;
		1 | [Yy]*) echo on ;;
		*) echo off ;;
		esac ;;
	emmc_speed)
		# the same consoles as builtin_emmc
		[ -e "$R/proc/device-tree/dwmmc@ff390000/andr36oid,builtin-emmc" ] || return 0
		case $(arg_last dw_mmc_rockchip.emmc_speed "$2") in
		ddr52) echo ddr52 ;;
		hs200) echo hs200 ;;
		*) echo hs ;;
		esac ;;
	emmc_power)
		# the same consoles as builtin_emmc
		[ -e "$R/proc/device-tree/dwmmc@ff390000/andr36oid,builtin-emmc" ] || return 0
		case $(arg_last dw_mmc_rockchip.emmc_power "$2") in
		1 | [Yy]*) echo on ;;
		*) echo off ;;
		esac ;;
	crash_log)
		case $(arg_last ramoops.disable "$2") in
		1 | [Yy]*) echo off ;;
		*) echo on ;;
		esac ;;
	crash_split)
		# no sizes: the device tree's, which is "balanced"
		case "$(arg_last ramoops.record_size "$2") $(arg_last ramoops.console_size "$2") $(arg_last ramoops.pmsg_size "$2")" in
		"  " | "$SPLIT_BALANCED") echo balanced ;;
		"$SPLIT_KERNEL") echo kernel ;;
		"$SPLIT_ANDROID") echo android ;;
		*) echo custom ;;
		esac ;;
	esac
}

# Crash log split: crash record, kernel log and Android log (pmsg) sizes in the 960 KB
# ramoops area of the device tree. ramoops rounds each down to a power of two (the DT's
# 320 KB Android log is 256 KB) and gives the crash record what is left, so balanced is
# 192+512+256, kernel 320+512+128 (the whole 256 KB kernel log fits the crash record) and
# android 192+256+512.
SPLIT_BALANCED="0x20000 0x80000 0x40000"
SPLIT_KERNEL="0x40000 0x80000 0x20000"
SPLIT_ANDROID="0x20000 0x40000 0x80000"

# split_args <sizes> ; "record console pmsg" as ramoops options
split_args() {
	# shellcheck disable=SC2086 # three sizes, split on spaces
	set -- $1
	echo "ramoops.record_size=$1 ramoops.console_size=$2 ramoops.pmsg_size=$3"
}

# boot_args <key> <value> ; the options that give the tweak this value
boot_args() {
	case $1:$2 in
	serial_console:on) echo "fiq_debugger.disable=0 console=ttyS2,115200n8 androidboot.console=ttyS2" ;;
	serial_console:off) echo "fiq_debugger.disable=1" ;;
	boot_log_level:*) echo "loglevel=$2" ;;
	# as Select held at power-on, without stopping on a crash
	boot_log:on) echo "console=tty0 ignore_loglevel printk.devkmsg=on androidboot.bootlog=1" ;;
	cma_size:default) ;;
	cma_size:*) echo "cma=$2M" ;;
	cpu_idle:off) echo "cpuidle.off=1" ;;
	timer_tick:off) echo "nohz=off" ;;
	boot_timing:1) echo "initcall_debug" ;;
	usb_autosuspend:off) echo "usbcore.autosuspend=-1" ;;
	extra_rates:none) ;;
	# only numbers, dots and commas get onto the command line
	extra_rates:*[!0-9.,]*) ;;
	extra_rates:*) echo "panel_simple.extra_rates=$2" ;;
	extra_buttons:none) ;;
	# only pin names (2A5, 3C1H) and commas get onto the command line
	extra_buttons:*[!0-9A-DLH,]*) ;;
	extra_buttons:*) echo "odroidgo3_joypad.extra_gpios=$2" ;;
	crash_log:off) echo "ramoops.disable=1" ;;
	radios:off) echo "module.no_radios=1" ;;
	storage:fuse) echo "androidboot.storage_fuse=1" ;;
	fuse:off) echo "fuse.disable=1" ;;
	boot_boost:off) echo "androidboot.andr36oid_boot_boost=0" ;;
	fuse:on) echo "fuse.disable=0" ;;
	sd2_slot:off) echo "dw_mmc_rockchip.second_slot=0" ;;
	builtin_emmc:on) echo "dw_mmc_rockchip.builtin_emmc=1" ;;
	emmc_speed:ddr52 | emmc_speed:hs | emmc_speed:hs200) echo "dw_mmc_rockchip.emmc_speed=$2" ;;
	emmc_power:on) echo "dw_mmc_rockchip.emmc_power=1" ;;
	crash_split:balanced) split_args "$SPLIT_BALANCED" ;;
	crash_split:kernel) split_args "$SPLIT_KERNEL" ;;
	crash_split:android) split_args "$SPLIT_ANDROID" ;;
	esac
}

# boot_lock <seconds> ; the same in andr36oid-report.sh, andr36oid-dtbhint.sh,
# andr36oid-ktweaks.sh and andr36oid-cardsetup.sh. The start-up helpers take turns
# with BOOT: Linux won't
# mount it a second time with the other ro/rw flag. The lock is a folder in /dev
# (a restart clears it) with the holder's pid; a holder that was killed is
# noticed by its pid, and its BOOT mount is taken away. False after waiting
# this long.
boot_lock() {
	n=0
	while ! mkdir "$BOOT_LOCK" 2>/dev/null; do
		pid=$(cat "$BOOT_LOCK/pid" 2>/dev/null)
		if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
			rm -rf "$BOOT_LOCK"
			continue
		fi
		n=$((n + 1))
		[ "$n" -gt $(($1 * 5)) ] && return 1
		sleep 0.2
	done
	echo $$ >"$BOOT_LOCK/pid"
	grep -o ' /mnt/andr36oid-[a-z]*-boot ' /proc/mounts 2>/dev/null | while read -r m; do
		umount "$m" 2>/dev/null
	done
	return 0
}

boot_unlock() {
	[ "$(cat "$BOOT_LOCK/pid" 2>/dev/null)" = "$$" ] && rm -rf "$BOOT_LOCK"
}

# boot_mount <ro|rw> ; BOOT at $BOOT, or a test folder (KT_BOOT)
BOOT=
boot_mount() {
	if [ -n "${KT_BOOT:-}" ]; then
		BOOT=$KT_BOOT
		return 0
	fi
	boot_lock 30 || return 1
	mkdir -p "$BOOT_MNT"
	for dev in $BOOT_DEVS; do
		[ -e "$dev" ] || continue
		mount -t vfat -o "$1,noatime" "$dev" "$BOOT_MNT" 2>/dev/null || continue
		BOOT=$BOOT_MNT
		return 0
	done
	boot_unlock
	return 1
}
boot_umount() {
	if [ -n "$BOOT" ] && [ -z "${KT_BOOT:-}" ]; then
		umount "$BOOT_MNT"
		boot_unlock
	fi
	BOOT=
}

# kt_args as kernel-tweaks.txt has it now
file_args() {
	sed -n 's/^kt_args=//p' "$BOOT/$KT_FILE" 2>/dev/null | tr -d '\r'
}

# write_boot_args <args> ; kernel-tweaks.txt for the next start, only when it changes
write_boot_args() {
	boot_mount rw || {
		log -t andr36oid-ktweaks "BOOT doesn't mount, start options not saved"
		return 1
	}
	if [ "$(file_args)" != "$1" ] || { [ -z "$1" ] && [ -e "$BOOT/$KT_FILE" ]; }; then
		if [ -z "$1" ]; then
			rm -f "$BOOT/$KT_FILE"
		else
			printf '# Settings > System > Kernel Tweaks: added to the kernel command line\n# at every start. Delete this file to start without them.\nkt_args=%s\n' \
				"$1" >"$BOOT/$KT_FILE"
		fi
		sync
	fi
	boot_umount
}

thermal_path() {
	for z in "$R"/sys/class/thermal/thermal_zone*; do
		if [ "$(cat "$z/type" 2>/dev/null)" = soc-thermal ]; then
			echo "${z#"$R"}/trip_point_$1_temp"
			return
		fi
	done
}

# Compressed memory (zram0). fstab.* sets it up with swapon_all on "on boot" (75% of
# RAM), init.rk30board.rc picks lz4; a size or compressor only changes while it is off
# and reset.
ZRAM=/sys/block/zram0
ZRAM_DEV=${KT_ZRAM_DEV:-/dev/block/zram0}

mem_kb() { sed -n 's/^MemTotal: *\([0-9]*\).*/\1/p' "$R/proc/meminfo"; }
zram_swapping() { grep -q '^/dev/block/zram0 ' "$R/proc/swaps" 2>/dev/null; }

# zram_wait ; for swapon_all to switch it on at a start (up to 30 s), so the
# defaults are the console's own and a change isn't undone by swapon_all after it
zram_wait() {
	[ -e "$R$ZRAM/disksize" ] || return 1
	n=0
	while ! zram_swapping && [ "$(getprop sys.boot_completed)" != 1 ] && [ $n -lt 150 ]; do
		sleep 0.2
		n=$((n + 1))
	done
	return 0
}

# zram_read size|algorithm
zram_read() {
	zram_wait || return 0
	case $1 in
	size)
		d=$(cat "$R$ZRAM/disksize" 2>/dev/null)
		m=$(mem_kb)
		if ! zram_swapping || [ "${d:-0}" = 0 ] || [ -z "$m" ]; then
			echo 0
		else
			echo $(((d / 1024 * 100 + m / 2) / m))
		fi
		;;
	algorithm)
		# "lzo [lz4] deflate": the bracketed one is in use
		v=$(sed -n 's/.*\[\([^]]*\)\].*/\1/p' "$R$ZRAM/comp_algorithm")
		echo "${v:-$(cat "$R$ZRAM/comp_algorithm" 2>/dev/null)}"
		;;
	esac
}

# zram_write size|algorithm value ; off, reset, the new size or compressor, on again
zram_write() {
	size=$(zram_read size)
	alg=$(zram_read algorithm)
	case $1 in
	size) size=$2 ;;
	algorithm) alg=$2 ;;
	esac
	case $size in '' | *[!0-9]*) return 1 ;; esac
	m=$(mem_kb)
	[ -n "$m" ] || return 1
	zram_swapping && { swapoff "$ZRAM_DEV" || return 1; }
	echo 1 >"$R$ZRAM/reset"
	[ -n "$alg" ] && echo "$alg" >"$R$ZRAM/comp_algorithm"
	[ "$size" -gt 0 ] || return 0
	echo $((m / 100 * size * 1024)) >"$R$ZRAM/disksize" &&
		mkswap "$ZRAM_DEV" >/dev/null && swapon "$ZRAM_DEV"
}

# read_knob kind paths
read_knob() {
	case $1 in
	cores)
		n=1
		for c in 1 2 3; do
			[ "$(cat "$R/sys/devices/system/cpu/cpu$c/online" 2>/dev/null)" = 1 ] && n=$((n + 1))
		done
		echo $n
		return
		;;
	thermal) set -- plain "$(thermal_path "$2")" ;;
	prop)
		v=$(getprop "$2")
		echo "${v:-0}"
		return
		;;
	boot)
		boot_read "$2" "$CMDLINE"
		return
		;;
	zram)
		zram_read "$2"
		return
		;;
	esac
	kind=$1
	shift
	for p in "$@"; do
		p=$R$p
		[ -e "$p" ] || continue
		case $kind in
		bracket) sed -n 's/.*\[\([^]]*\)\].*/\1/p' "$p" ;;
		first) read -r v _ <"$p" && echo "$v" ;;
		*) cat "$p" ;;
		esac
		return
	done
}

# write_knob kind value paths
write_knob() {
	kind=$1
	v=$2
	shift 2
	case $kind in
	cores)
		# switch cores on before others off, so there's never fewer than wanted
		for c in 1 2 3; do
			[ $c -lt "$v" ] && echo 1 >"$R/sys/devices/system/cpu/cpu$c/online"
		done
		for c in 3 2 1; do
			[ $c -ge "$v" ] && echo 0 >"$R/sys/devices/system/cpu/cpu$c/online"
		done
		return
		;;
	prop)
		setprop "$1" "$v"
		return
		;;
	zram)
		zram_write "$1" "$v"
		return
		;;
	thermal) set -- "$(thermal_path "$1")" ;;
	esac
	for p in "$@"; do
		[ -e "$R$p" ] && echo "$v" >"$R$p"
	done
}

# apply_all paused
apply_all() {
	failed=
	bootargs=
	storage_next=
	# fed by a here-document, not a pipe: the loop runs in this shell and keeps $failed
	while IFS='|' read -r key kind once paths; do
		[ -n "$key" ] || continue
		def=$(getprop "sys.andr36oid.ktd.$key")
		[ -n "$def" ] || continue
		if [ -n "$once" ]; then
			want=$(getprop "sys.andr36oid.kt.$key")
		elif [ "$1" = 1 ]; then
			want=
		else
			want=$(getprop "persist.andr36oid.kt.$key")
		fi
		[ -n "$want" ] || want=$def
		# shellcheck disable=SC2086 # paths is a space separated list
		cur=$(read_knob "$kind" $paths)
		# storage comes before fuse in KNOBS: FUSE storage needs the FUSE driver, so
		# fuse.disable=1 stays off the command line while the next start uses it
		[ "$key" = storage ] && storage_next=$want
		[ "$key" = fuse ] && [ "$storage_next" = fuse ] && want=on
		if [ "$kind" = boot ]; then
			# for the next start; the kernel keeps what it started with
			if [ "$want" != "$def" ]; then
				a=$(boot_args "$key" "$want")
				[ -n "$a" ] && bootargs="${bootargs:+$bootargs }$a"
			fi
		elif [ "$cur" != "$want" ]; then
			# shellcheck disable=SC2086
			write_knob "$kind" "$want" $paths 2>/dev/null
			# shellcheck disable=SC2086
			cur=$(read_knob "$kind" $paths)
			[ "$cur" = "$want" ] || {
				failed="$failed $key"
				log -t andr36oid-ktweaks "$key: wanted '$want', kernel kept '$cur'"
			}
		fi
		setprop "sys.andr36oid.ktc.$key" "$cur"
	done <<EOF
$KNOBS
EOF
	write_boot_args "$bootargs" || failed="$failed boot"
	# Property values allow 91 bytes; the BOOT file keeps the complete start options.
	setprop sys.andr36oid.kt.args "$(printf '%s' "$bootargs" | head -c 91)"
	setprop sys.andr36oid.kt.failed "${failed# }"
}

any_set() {
	echo "$KNOBS" | while IFS='|' read -r key kind once paths; do
		if [ -n "$key" ] && [ -z "$once" ] && [ -n "$(getprop "persist.andr36oid.kt.$key")" ]; then
			echo 1
			break
		fi
	done
}

# 1 while the tweaks are switched off
is_paused() {
	case $(getprop persist.andr36oid.kt.paused) in
	1 | crash) echo 1 ;;
	*) echo 0 ;;
	esac
}

# Profiles are plain data. Only the app validates and applies imported tweaks.
PROFILE_DIR=${KT_PROFILE_DIR:-/data/misc/andr36oid-ktprofiles}
PROFILE_BUILTIN=${KT_PROFILE_BUILTIN:-/system/etc/andr36oid-ktprofiles}
PROFILE_DOWNLOAD=${KT_PROFILE_DOWNLOAD:-/data/media/0/Download}
PROFILE_FOLDER=andr36oid-ktprofiles
PROFILE_MAX_BYTES=65536
PROFILE_MAX_FILES=100

profile_status() {
	printf '%s\n' "$*" >"$PROFILE_DIR/status.tmp"
	[ -n "${KT_PROFILE_DIR:-}" ] || chown system:system "$PROFILE_DIR/status.tmp"
	mv -f "$PROFILE_DIR/status.tmp" "$PROFILE_DIR/status"
}

profile_easyroms() {
	local selected id state uuid
	if [ -n "${KT_PROFILE_EASYROMS+set}" ]; then
		[ -d "${KT_PROFILE_EASYROMS:-}" ] && echo "$KT_PROFILE_EASYROMS"
		return
	fi
	selected=$(getprop sys.andr36oid.easyroms.id)
	if [ -z "$selected" ] && [ "$(getprop sys.andr36oid.easyroms.ready)" != 1 ]; then
		selected=public:179,7
	fi
	sm list-volumes public </dev/null 2>/dev/null | while read -r id state uuid; do
		[ "$id" = "$selected" ] && [ "$state" = mounted ] && [ -n "$uuid" ] &&
			[ -d "/mnt/media_rw/$uuid" ] && echo "/mnt/media_rw/$uuid"
	done
}

# Values are one line, also for device tree strings (compatible is a NUL list).
profile_identity() {
	printf 'model=%s\n' "$(tr -d '\0\r\n' <"$R/proc/device-tree/model" 2>/dev/null | head -c 1024)"
	printf 'compatible=%s\n' "$(tr '\0' ' ' <"$R/proc/device-tree/compatible" 2>/dev/null | tr -d '\r\n' | head -c 1024)"
	printf 'profile=%s\n' "$(if [ -n "$BOOT" ]; then head -c 1024 "$BOOT/andr36oid-profile.txt" 2>/dev/null | head -n 1 | tr -d '\r'; fi)"
	printf 'version=%s\n' "$(getprop ro.andr36oid.version)"
	local f panel
	panel=$(getprop andr36oid.info.panel)
	if [ -n "$panel" ] && [ "$panel" != custom ]; then
		printf 'panel=%s\n' "$panel"
		return
	fi
	for f in "$R"/proc/device-tree/*/panel*/compatible "$R"/proc/device-tree/panel*/compatible; do
		[ -f "$f" ] || continue
		printf 'panel=%s\n' "$(tr '\0' ' ' <"$f" | tr -d '\r\n' | head -c 1024)"
		return
	done
	printf 'panel=\n'
}

profile_usable() {
	local name=${1##*/} size
	case $name in
	.* | *[!A-Za-z0-9._-]*) return 1 ;;
	?*.txt | ?*.TXT) ;;
	*) return 1 ;;
	esac
	[ -f "$1" ] && [ ! -L "$1" ] || return 1
	size=$(wc -c <"$1" 2>/dev/null) || return 1
	[ "$size" -gt 0 ] && [ "$size" -le "$PROFILE_MAX_BYTES" ]
}

# Snapshot files under numbered names, never use an imported name as an app path.
profile_scan() {
	local dir=$1 source=$2 f
	[ -d "$dir" ] && [ ! -L "$dir" ] || return 0
	for f in "$dir"/*; do
		[ -e "$f" ] || [ -L "$f" ] || continue
		case $f in *.txt | *.TXT) ;; *) continue ;; esac
		if [ "$profile_n" -ge "$PROFILE_MAX_FILES" ] || ! profile_usable "$f"; then
			profile_skipped=$((profile_skipped + 1))
			continue
		fi
		profile_n=$((profile_n + 1))
		dd if="$f" of="$PROFILE_DIR/in/$profile_n.txt" bs=$PROFILE_MAX_BYTES count=1 2>/dev/null || return 1
		printf '%s\t%s\t%s\n' "$profile_n" "$source" "${f##*/}" >>"$PROFILE_DIR/list"
	done
}

# Copy atomically, trying the next drive even when a mounted drive is full/read-only.
profile_copy() {
	local dir=$1 name=$2
	[ ! -L "$dir" ] || return 1
	mkdir -p "$dir" 2>/dev/null || return 1
	[ ! -e "$dir/$name" ] && [ ! -L "$dir/$name" ] || return 1
	[ ! -e "$dir/.$name.tmp" ] && [ ! -L "$dir/.$name.tmp" ] || return 1
	if cp "$PROFILE_DIR/out" "$dir/.$name.tmp" 2>/dev/null &&
		mv "$dir/.$name.tmp" "$dir/$name" 2>/dev/null; then
		return 0
	fi
	rm -f "$dir/.$name.tmp"
	return 1
}

profiles() {
	local request easy name source dir where='' profile_n=0 profile_skipped=0
	[ -d "$PROFILE_DIR" ] || return 1
	umask 007
	request=$(cat "$PROFILE_DIR/request" 2>/dev/null)
	rm -f "$PROFILE_DIR/request"
	case $request in export | scan) ;; *) profile_status "error request"; return ;; esac
	trap 'boot_umount' EXIT
	boot_mount ro || BOOT=
	profile_identity >"$PROFILE_DIR/identity"
	easy=$(profile_easyroms)
	if [ "$request" = scan ]; then
		rm -rf "$PROFILE_DIR/in"
		mkdir -p "$PROFILE_DIR/in" || { profile_status "error copy"; return; }
		: >"$PROFILE_DIR/list"
		for source in builtin BOOT EASYROMS Download; do
			case $source in
			builtin) dir=$PROFILE_BUILTIN ;;
			BOOT) dir=${BOOT:+$BOOT/$PROFILE_FOLDER} ;;
			EASYROMS) dir=${easy:+$easy/$PROFILE_FOLDER} ;;
			Download) dir=$PROFILE_DOWNLOAD/$PROFILE_FOLDER ;;
			esac
			[ -n "$dir" ] || continue
			if ! profile_scan "$dir" "$source"; then
				profile_status "error copy"; return
			fi
		done
	else
		[ -s "$PROFILE_DIR/readme" ] || { profile_status "error request"; return; }
		{
			printf '# %s\nformat=andr36oid-ktprofile-1\n' "$(head -n 1 "$PROFILE_DIR/readme")"
			cat "$PROFILE_DIR/identity"
			echo "$KNOBS" | while IFS='|' read -r key kind once paths; do
				[ -n "$key" ] || continue
				[ -z "$once" ] || continue
				v=$(getprop "persist.andr36oid.kt.$key")
				[ -n "$v" ] && printf 'persist.andr36oid.kt.%s=%s\n' "$key" "$v"
			done
		} >"$PROFILE_DIR/out"
		name=ktprofile-$(date +%Y%m%d-%H%M%S)-$$.txt
		if [ -n "$BOOT" ]; then
			if [ -n "${KT_BOOT:-}" ]; then
				[ ! -e "$BOOT/.readonly" ] && profile_copy "$BOOT/$PROFILE_FOLDER" "$name" && where=BOOT
			elif mount -o remount,rw "$BOOT_MNT" 2>/dev/null; then
				profile_copy "$BOOT/$PROFILE_FOLDER" "$name" && where=BOOT
			fi
		fi
		boot_umount
		if [ -z "$where" ] && [ -n "$easy" ]; then
			profile_copy "$easy/$PROFILE_FOLDER" "$name" && where=EASYROMS
		fi
		if [ -z "$where" ] && [ -d "$PROFILE_DOWNLOAD" ]; then
			profile_copy "$PROFILE_DOWNLOAD/$PROFILE_FOLDER" "$name" && where=Download
		fi
		[ -n "$where" ] || { profile_status "error copy"; return; }
		printf '%s/%s/%s\n' "$where" "$PROFILE_FOLDER" "$name" >"$PROFILE_DIR/saved"
		if [ "$where" = Download ] && [ -z "${KT_PROFILE_DIR:-}" ]; then
			chown media_rw:media_rw "$PROFILE_DOWNLOAD/$PROFILE_FOLDER" "$PROFILE_DOWNLOAD/$PROFILE_FOLDER/$name"
			chmod 0775 "$PROFILE_DOWNLOAD/$PROFILE_FOLDER"
			chmod 0664 "$PROFILE_DOWNLOAD/$PROFILE_FOLDER/$name"
			am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
				-d "file:///sdcard/Download/$PROFILE_FOLDER/$name" >/dev/null 2>&1
		fi
	fi
	boot_umount
	if [ -z "${KT_PROFILE_DIR:-}" ]; then
		chown -R system:system "$PROFILE_DIR"
	fi
	profile_status "done $profile_skipped"
}

if [ "$1" = profiles ]; then
	profiles
	exit $?
fi

paused=$(is_paused)

if [ "$1" = boot ]; then
	# Second SD card switched off before the slot had a kernel option: carry it over
	if [ "$(getprop persist.andr36oid.sd2_mount)" = 0 ] &&
		[ -z "$(getprop persist.andr36oid.kt.sd2_slot)" ]; then
		setprop persist.andr36oid.kt.sd2_slot off
	fi
	# The defaults of the start options: this start's command line without the part
	# kernel-tweaks.txt added. boot.ini puts it near the end, but more can follow
	# (the boot log options, or just a space): take out its last match anywhere.
	if boot_mount ro; then
		added=$(file_args)
		boot_umount
		c=" $CMDLINE "
		case $c in *" $added "*)
			[ -n "$added" ] && BASE="${c%" $added "*} ${c##*" $added "}" ;;
		esac
	fi
	setprop sys.andr36oid.kt.started_with "$(printf '%s' "${added:-}" | head -c 91)"
	echo "$KNOBS" | while IFS='|' read -r key kind once paths; do
		[ -n "$key" ] || continue
		if [ "$kind" = boot ]; then
			v=$(boot_read "$paths" "$BASE")
		else
			# shellcheck disable=SC2086
			v=$(read_knob "$kind" $paths 2>/dev/null)
		fi
		[ -n "$v" ] && setprop "sys.andr36oid.ktd.$key" "$v"
	done
	# Data with no tweaks saved but a kernel-tweaks.txt on BOOT: the first start of
	# andr36oid installed on the eMMC (its BOOT is a copy of the SD card's) or after
	# a reset. Take the start options over, apply_all would drop the file otherwise.
	if [ -n "$added" ] && [ -z "$(any_set)" ] && [ -z "$(getprop persist.andr36oid.kt.paused)" ]; then
		echo "$KNOBS" | while IFS='|' read -r key kind once paths; do
			[ "$kind" = boot ] || continue
			def=$(getprop "sys.andr36oid.ktd.$key")
			[ -n "$def" ] || continue
			v=$(boot_read "$paths" "$CMDLINE")
			[ -n "$v" ] && [ "$v" != "$def" ] && setprop "persist.andr36oid.kt.$key" "$v"
		done
		log -t andr36oid-ktweaks "no tweaks saved: took over kernel-tweaks.txt ($added)"
	fi
	if [ "$paused" != 1 ] && [ -n "$(any_set)" ]; then
		tries=$(getprop persist.andr36oid.kt.tries)
		if [ "${tries:-0}" -ge 2 ]; then
			log -t andr36oid-ktweaks "the last $tries starts didn't finish, pausing the tweaks"
			setprop persist.andr36oid.kt.paused crash
			setprop persist.andr36oid.kt.tries 0
			paused=1
		else
			setprop persist.andr36oid.kt.tries $((${tries:-0} + 1))
		fi
	fi
	apply_all "$paused"
	setprop sys.andr36oid.kt.ready 1
	exit 0
fi

# apply: run again while the app asked for more during the run
while :; do
	req=$(getprop sys.andr36oid.kt.request)
	paused=$(is_paused)
	apply_all "$paused"
	setprop sys.andr36oid.kt.done "$req"
	[ "$(getprop sys.andr36oid.kt.request)" = "$req" ] && break
done
