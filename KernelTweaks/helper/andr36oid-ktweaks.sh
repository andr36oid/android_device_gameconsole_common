#!/system/bin/sh
# Settings > System > Kernel Tweaks: the root part. Writes the tweaks to the kernel.
#
#   boot   (andr36oid-ktweaks-boot, once persist props are loaded): note every knob's
#          default in sys.andr36oid.ktd.<key>, then apply the saved tweaks
#   apply  (andr36oid-ktweaks, ctl.start from the app): apply again, until
#          sys.andr36oid.kt.request stops changing
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
#   boot     an option on the kernel command line (see boot_read/boot_args): it takes
#            effect at the next start, through BOOT/kernel-tweaks.txt
# extra_rates isn't in the Kernel Tweaks list: Settings > Display > Panel overclocking
# sets it, a comma list of Hz such as "70,77.5", "none" for none.
KNOBS='
sleep_type|bracket||/sys/power/mem_sleep
sleep_test|bracket|once|/sys/power/pm_test
sleep_console|plain||/sys/module/printk/parameters/console_suspend
sleep_log_times|plain||/sys/power/pm_print_times
sleep_log_debug|plain||/sys/power/pm_debug_messages
cores|cores||
heat_limit|thermal||1
swappiness|plain||/proc/sys/vm/swappiness
cache_pressure|plain||/proc/sys/vm/vfs_cache_pressure
page_cluster|plain||/proc/sys/vm/page-cluster
sd1_scheduler|bracket||/sys/block/mmcblk0/queue/scheduler
sd1_readahead|plain||/sys/block/mmcblk0/queue/read_ahead_kb
sd2_scheduler|bracket||/sys/block/mmcblk1/queue/scheduler
sd2_readahead|plain||/sys/block/mmcblk1/queue/read_ahead_kb
stick_invert|plain||/sys/devices/platform/odroidgo3-joypad/stick_invert
poll_interval|plain||/sys/devices/platform/odroidgo3-joypad/poll_interval
hp_det_flip|plain||/sys/module/snd_soc_simple_card_utils/parameters/hp_det_flip
log_level|first||/proc/sys/kernel/printk
panic_restart|plain||/proc/sys/kernel/panic
oops_panic|plain||/proc/sys/kernel/panic_on_oops
stall_report|plain||/sys/module/rcupdate/parameters/rcu_cpu_stall_timeout
power_chip_log|plain||/sys/module/rk817_charger/parameters/dbg_level /sys/module/rk817_battery/parameters/dbg_level /sys/module/snd_soc_rk817/parameters/dbg_level
crash_oops|plain||/sys/module/ramoops/parameters/dump_oops
crash_restart_log|plain||/sys/module/printk/parameters/always_kmsg_dump
serial_console|boot||serial_console
boot_log_level|boot||boot_log_level
cma_size|boot||cma_size
cpu_idle|boot||cpu_idle
timer_tick|boot||timer_tick
boot_timing|boot||boot_timing
usb_autosuspend|boot||usb_autosuspend
extra_rates|boot||extra_rates
crash_log|boot||crash_log
crash_split|boot||crash_split
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
	crash_log:off) echo "ramoops.disable=1" ;;
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
	boot)
		boot_read "$2" "$CMDLINE"
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
	setprop sys.andr36oid.kt.args "$bootargs"
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

paused=$(is_paused)

if [ "$1" = boot ]; then
	# The defaults of the start options: this start's command line without the part
	# kernel-tweaks.txt added at its end
	if boot_mount ro; then
		added=$(file_args)
		boot_umount
		case $CMDLINE in *" $added") [ -n "$added" ] && BASE=${CMDLINE%" $added"} ;; esac
	fi
	setprop sys.andr36oid.kt.started_with "${added:-}"
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
