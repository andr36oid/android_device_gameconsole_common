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
'

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
	echo "$KNOBS" | while IFS='|' read -r key kind once paths; do
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
		if [ "$cur" != "$want" ]; then
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
		echo "$failed" >"$FAILED"
	done
	setprop sys.andr36oid.kt.failed "$(cat "$FAILED" 2>/dev/null)"
}

FAILED=$(mktemp) || exit 1
trap 'rm -f "$FAILED"' EXIT

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
	echo "$KNOBS" | while IFS='|' read -r key kind once paths; do
		[ -n "$key" ] || continue
		# shellcheck disable=SC2086
		v=$(read_knob "$kind" $paths 2>/dev/null)
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
