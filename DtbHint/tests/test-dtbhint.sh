#!/bin/sh
# Host tests for andr36oid-dtbhint.sh. Builds fake /proc, /sys and BOOT trees from
# tests/boards.txt (made from the real DTBs by gen-boards.py): the DTB of one
# folder "installed" on the board of another folder, for every pair, and checks
# that the hint lists the right folder.
#
#   tests/test-dtbhint.sh [shell...]     default: dash bash "busybox ash"
#   QUICK=1 skips the board matrix (about 10 minutes). To get closer to the
#   console, put toybox and one-true-awk (prebuilts/build-tools) first in PATH.
#
# How a wrong board is faked (the parts nobody has measured are marked):
#   - SARADC channel 0: genuine 166, EE 514 (U-Boot rg351mp / rg351v bands, as
#     dArkOSRE and ROCKNIX expect), Soy 675 (rg351p band, assumed) or 900 (not in
#     any band: "Soy reads v00", the other possibility);
#   - joypad buttons of the installed DT: a pin the real board also uses as a
#     button reads high; any other pin reads low ("lo" mode) or high ("hi" mode,
#     the DT's pull-up wins). Both modes are run;
#   - sticks: when the stick mux enable pin differs, both sticks sit at the end of
#     their range (assumed: the mux stays off);
#   - EE volume keys on SARADC channel 2: 1020 on EE boards, 0 elsewhere (assumed).

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT=$HERE/../helper/andr36oid-dtbhint.sh
BOARDS=$HERE/boards.txt
[ $# -gt 0 ] || set -- dash bash "busybox ash"

PASS=0
FAIL=0
ok() { PASS=$((PASS + 1)); }
bad() {
	FAIL=$((FAIL + 1))
	echo "FAIL [$SH] $*"
}
expect() {
	d=$1
	shift
	if "$@"; then ok; else bad "$d"; fi
}
contains() { grep -q -F -- "$2" "$1" 2>/dev/null; }
lacks() { ! grep -q -F -- "$2" "$1" 2>/dev/null; }

W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT

# field <folder> <n> ; field n of the folder's line in boards.txt
field() { awk -F'|' -v f="$1" -v n="$2" '$1 == f { print $n }' "$BOARDS"; }

# wiring <amux-en pin> ; the helper's name for it
wiring() {
	case $1 in
	3B5) echo genuine ;;
	2B4) echo ee-b4 ;;
	2B3) echo ee-b3 ;;
	0C1) echo soy ;;
	*) echo unknown ;;
	esac
}
fam() {
	case $(wiring "$1") in
	ee-*) echo ee ;;
	*) wiring "$1" ;;
	esac
}

# pinnum <pin like 3B5> ; number in the bank (B5 = 13)
pinnum() {
	l=$(printf '%s' "$1" | cut -c 2)
	n=$(printf '%s' "$1" | cut -c 3)
	case $l in A) b=0 ;; B) b=8 ;; C) b=16 ;; D) b=24 ;; esac
	echo $((b + n))
}

# be32 <n> ; four bytes, big endian
be32() {
	# shellcheck disable=SC2059 # the format is the octal escapes built here
	printf "$(printf '\\%03o\\%03o\\%03o\\%03o' $(($1 >> 24 & 255)) $(($1 >> 16 & 255)) $(($1 >> 8 & 255)) $(($1 & 255)))"
}

# fake <root> <installed folder> <board folder> [options...]
#   pull=lo|hi   adc0=<n>|none   rev=<hwrev>   joy=down   clash=serial
#   locale=<l>   noboot   nojoy
fake() {
	R=$1 X=$2 Y=$3
	shift 3
	pull=lo adc0='' rev='' joy=up clash='' locale=en-US noboot='' nojoy=''
	for o in "$@"; do
		case $o in
		pull=*) pull=${o#pull=} ;;
		adc0=*) adc0=${o#adc0=} ;;
		rev=*) rev=${o#rev=} ;;
		joy=down) joy=down ;;
		clash=serial) clash=serial ;;
		locale=*) locale=${o#locale=} ;;
		noboot) noboot=1 ;;
		nojoy) nojoy=1 ;;
		esac
	done
	rm -rf "$R"
	mkdir -p "$R/proc/device-tree" "$R/proc/bus/input" "$R/sys/kernel/debug" \
		"$R/sys/bus/iio/devices/iio:device0" "$R/boot" "$R/test"
	xen=$(field "$X" 3) yen=$(field "$Y" 3)
	xbtn=$(field "$X" 4) ybtn=$(field "$Y" 4)
	xvol=$(field "$X" 5) yvol=$(field "$Y" 5)
	printf '%s\000' "$(field "$X" 2)" >"$R/proc/device-tree/model"
	if [ -z "$nojoy" ]; then
		mkdir -p "$R/proc/device-tree/odroidgo3-joypad"
		{ be32 99; be32 "$(pinnum "$xen")"; be32 1; } >"$R/proc/device-tree/odroidgo3-joypad/amux-en-gpios"
	fi
	[ "$xvol" = adc ] && mkdir -p "$R/proc/device-tree/adc-keys"

	# SARADC
	d=$R/sys/bus/iio/devices/iio:device0
	echo ff288000.saradc >"$d/name"
	if [ -z "$adc0" ]; then
		case $(fam "$yen") in
		genuine) adc0=166 ;;
		ee) adc0=514 ;;
		soy) adc0=675 ;;
		esac
	fi
	[ "$adc0" = none ] || echo "$adc0" >"$d/in_voltage0_raw"
	if [ "$yvol" = adc ]; then echo 1020 >"$d/in_voltage2_raw"; else echo 0 >"$d/in_voltage2_raw"; fi

	# input devices, buttons, sticks
	: >"$R/proc/bus/input/devices"
	: >"$R/getevent.txt"
	g=$R/sys/kernel/debug/gpio
	: >"$g"
	if [ "$joy" = up ] && [ -z "$nojoy" ]; then
		printf 'I: Bus=0019 Vendor=484b Product=1100 Version=0100\nN: Name="GO-Super Gamepad"\n\n' >>"$R/proc/bus/input/devices"
		v=0
		[ "$xen" = "$yen" ] || v=-900
		{
			echo 'add device 4: /dev/input/event3'
			echo '  name:     "GO-Super Gamepad"'
			echo '  events:'
			echo "    ABS (0003): 0000  : value $v, min -900, max 900, fuzz 32, flat 32, resolution 0"
			echo "                0001  : value $v, min -900, max 900, fuzz 32, flat 32, resolution 0"
			echo "                0003  : value $v, min -900, max 900, fuzz 32, flat 32, resolution 0"
			echo "                0004  : value $v, min -900, max 900, fuzz 32, flat 32, resolution 0"
			echo "                0010  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0"
		} >>"$R/getevent.txt"
		i=0
		for p in $xbtn; do
			i=$((i + 1))
			val=$pull
			case " $ybtn $yvol " in *" $p "*) val=hi ;; esac
			printf ' gpio-%-3d (                    |%-20.20s) in  %s IRQ\n' "$i" "GPIO B$i" "$val" >>"$g"
		done
	fi
	if [ "$xvol" != adc ]; then
		for p in $xvol; do
			val=$pull
			case " $ybtn $yvol " in *" $p "*) val=hi ;; esac
			printf ' gpio-%-3d (                    |%-20.20s) in  %s    \n' 90 "GPIO BTN-VOL$p" "$val" >>"$g"
		done
	fi
	printf ' gpio-7   (                    |rk817               ) in  lo IRQ\n' >>"$g"

	{
		echo "ro.boot.revision=$rev"
		echo "persist.sys.locale=$locale"
	} >"$R/props"
	: >"$R/dmesg.txt"
	if [ "$clash" = serial ]; then
		{
			echo '[    1.234567] rockchip-pinctrl pinctrl: pin gpio2-14 already requested by ff160000.serial; cannot claim for odroidgo3-joypad'
			echo '[    1.234600] rockchip-pinctrl pinctrl: pin-78 (odroidgo3-joypad) status -22'
			echo '[    1.234700] odroidgo3-joypad odroidgo3-joypad: Error applying setting, reverse things back'
		} >"$R/dmesg.txt"
	fi

	# BOOT: every folder with a DTB that differs per folder (made once, linked),
	# the installed one on top
	if [ -z "$noboot" ]; then
		if [ ! -d "$W/shared" ]; then
			awk -F'|' '{ print $1 }' "$BOARDS" | while read -r f; do
				mkdir -p "$W/shared/$f"
				echo "dtb of $f" >"$W/shared/$f/rk3326-r36s-android.dtb"
			done
		fi
		ln -s "$W/shared/Panels" "$R/boot/Panels"
		ln -s "$W/shared/Experimental" "$R/boot/Experimental"
		cp "$W/shared/$X/rk3326-r36s-android.dtb" "$R/boot/rk3326-r36s-android.dtb"
	fi
}

hint() {
	R=$1
	b=$R/boot
	[ -d "$b" ] && [ -f "$b/rk3326-r36s-android.dtb" ] || b=$R/none
	DTBHINT_ROOT=$R DTBHINT_TEST=$R/test DTBHINT_BOOT=$b DTBHINT_DATA=$R/data \
		DTBHINT_WAIT=0 DTBHINT_GAP=0 $SH "$SCRIPT" >"$R/test/out" 2>&1
	echo $? >"$R/test/rc"
}

# the hint's result line
result() { sed -n 's/^result: \([^ ]*\).*/\1/p' "$1/data/dtb-hint.txt"; }

# ---- 1. the helper's folder lists match the DTBs ----

list_of() { sed -n "s/^$1=\"\(.*\)\"\$/\1/p" "$SCRIPT"; }
check_lists() {
	while IFS='|' read -r f _ en _ _; do
		case $(wiring "$en") in
		genuine) v=F_GENUINE ;;
		soy) v=F_SOY ;;
		ee-b4) v=F_EE_B4 ;;
		ee-b3) v=F_EE_B3 ;;
		*) v= ;;
		esac
		n=0
		for l in F_GENUINE F_SOY F_EE_B4 F_EE_B3; do
			case " $(list_of $l) " in *" $f "*) n=$((n + 1)) && [ "$l" = "$v" ] || n=99 ;; esac
		done
		expect "lists: $f is in $v only" [ "$n" = 1 ]
	done <"$BOARDS"
	for l in F_GENUINE F_SOY F_EE_B4 F_EE_B3; do
		for f in $(list_of $l); do
			expect "lists: $f from $l exists in boards.txt" grep -q "^$f|" "$BOARDS"
		done
	done
}

# ---- 2. every installed DTB on every board ----

# matrix <pull> <soy adc0> [all] ; every installed DTB on every board ("all") or
# on one board per wiring (the result depends only on the wiring)
matrix() {
	R=$W/m
	folders=$(awk -F'|' '{ print $1 }' "$BOARDS")
	boards=$folders
	[ "$3" = all ] || boards=$(awk -F'|' '!seen[$3]++ { print $1 }' "$BOARDS")
	for X in $folders; do
		for Y in $boards; do
			if [ "$(fam "$(field "$Y" 3)")" = soy ] && [ -n "$2" ]; then
				fake "$R" "$X" "$Y" "pull=$1" "adc0=$2"
			else
				fake "$R" "$X" "$Y" "pull=$1"
			fi
			hint "$R"
			r=$(result "$R")
			h=$R/boot/dtb-hint.txt
			xen=$(field "$X" 3) yen=$(field "$Y" 3)
			if [ "$xen" = "$yen" ]; then
				# same wiring: only the screen can differ, which can't be seen
				[ "$r" = ok ] || bad "matrix $1/${2:-675}: $X on $Y gave $r, want ok"
				contains "$h" "$Y" || bad "matrix $1/${2:-675}: $X on $Y: $Y not listed"
				[ ! -s "$R/test/calls" ] || bad "matrix: $X on $Y notified"
				continue
			fi
			if contains "$h" "$Y" && [ "$r" != ok ]; then
				ok
			else
				bad "matrix $1/${2:-675}: $X on $Y gave $r, $Y $(contains "$h" "$Y" && echo listed || echo 'not listed')"
				continue
			fi
			contains "$R/test/calls" "notify:" || bad "matrix: $X on $Y ($r) did not notify"
			if [ "$(fam "$xen")" = genuine ] && [ "$(fam "$yen")" = soy ] && [ "${2:-675}" = 675 ]; then
				[ "$r" = soy-on-genuine ] || bad "matrix: $X on $Y gave $r, want soy-on-genuine"
			fi
		done
	done
}

# ---- 3. single cases ----

cases() {
	R=$W/c
	K1=Experimental/EE-K36-Panel1

	# Kauan's case before O2-46: EE DTB on an EE board, joypad down, serial clash
	fake "$R" $K1 Experimental/EE-K36-Panel4 joy=down clash=serial
	hint "$R"
	expect "old ee dtb: result" [ "$(result "$R")" = old-ee-dtb ]
	expect "old ee dtb: says newer release" contains "$R/boot/dtb-hint.txt" "newer andr36oid release"
	expect "old ee dtb: kernel lines in details" contains "$R/boot/dtb-hint.txt" "ff160000.serial"
	expect "old ee dtb: notified" contains "$R/test/calls" "notify:"

	# joypad down without a serial clash on EE: try the other EE wiring
	fake "$R" $K1 $K1 joy=down
	hint "$R"
	expect "ee joy down: other-ee" [ "$(result "$R")" = other-ee ]
	expect "ee joy down: G80 listed" contains "$R/boot/dtb-hint.txt" "EE-G80-Panel8"

	# the right DTB: ok, no notification, file in three languages with CRLF
	fake "$R" Panels/Panel4 Panels/Panel4
	hint "$R"
	h=$R/boot/dtb-hint.txt
	expect "right dtb: ok" [ "$(result "$R")" = ok ]
	expect "right dtb: no notification" [ ! -s "$R/test/calls" ]
	expect "right dtb: english" contains "$h" "Now in use: Panels/Panel4"
	expect "right dtb: spanish" contains "$h" "En uso ahora: Panels/Panel4"
	expect "right dtb: portuguese" contains "$h" "Em uso agora: Panels/Panel4"
	expect "right dtb: CRLF" [ "$(grep -c "$(printf '\r')\$" "$h")" = "$(wc -l <"$h" | tr -d ' ')" ]
	expect "right dtb: data copy" [ -s "$R/data/dtb-hint.txt" ]
	expect "right dtb: exit 0" [ "$(cat "$R/test/rc")" = 0 ]

	# a second run with the same state doesn't rewrite the file
	expect "first run: BOOT read-only, then writable for the new file" \
		[ "$(tr '\n' , <"$R/test/boot")" = "mount -o ro,mount -o remount,rw,umount," ]
	expect "first run: lock released" [ ! -e "$R/test/boot.lock" ]
	touch -d '2001-01-01 00:00:00' "$h"
	rm -f "$R/test/boot"
	hint "$R"
	expect "same state: file not rewritten" [ "$(date -r "$h" +%Y)" = 2001 ]
	expect "same state: BOOT only read-only" [ "$(tr '\n' , <"$R/test/boot")" = "mount -o ro,umount," ]
	# a changed state does
	echo 514 >"$R/sys/bus/iio/devices/iio:device0/in_voltage0_raw"
	hint "$R"
	expect "changed state: file rewritten" [ "$(date -r "$h" +%Y)" != 2001 ]
	# another start-up helper holds BOOT: skipped after the wait, nothing mounted
	sleep 30 &
	holder=$!
	mkdir "$R/test/boot.lock"
	echo "$holder" >"$R/test/boot.lock/pid"
	rm -f "$R/test/boot"
	touch -d '2001-01-01 00:00:00' "$h"
	echo 166 >"$R/sys/bus/iio/devices/iio:device0/in_voltage0_raw"
	DTBHINT_BOOT_LOCK_WAIT=1
	export DTBHINT_BOOT_LOCK_WAIT
	hint "$R"
	expect "BOOT busy: said so" contains "$R/test/log" "BOOT is busy"
	expect "BOOT busy: not mounted" [ ! -e "$R/test/boot" ]
	expect "BOOT busy: file untouched" [ "$(date -r "$h" +%Y)" = 2001 ]
	expect "BOOT busy: the holder's lock kept" [ "$(cat "$R/test/boot.lock/pid")" = "$holder" ]
	kill "$holder" 2>/dev/null
	wait "$holder" 2>/dev/null
	# the holder was killed: its lock and its mount are taken over
	echo "/dev/block/mmcblk0p1 /mnt/andr36oid-cardsetup-boot vfat rw,noatime 0 0" >"$R/proc/mounts"
	hint "$R"
	unset DTBHINT_BOOT_LOCK_WAIT
	expect "stale lock: file written" [ "$(date -r "$h" +%Y)" != 2001 ]
	expect "stale lock: the killed helper's mount taken away" contains "$R/test/boot" "umount /mnt/andr36oid-cardsetup-boot"
	expect "stale lock: released" [ ! -e "$R/test/boot.lock" ]
	echo 514 >"$R/sys/bus/iio/devices/iio:device0/in_voltage0_raw"
	hint "$R"
	expect "changed state: wrong-family" [ "$(result "$R")" = wrong-family ]

	# Soy board with Panel5 (the README warning case)
	fake "$R" Panels/Panel5 Experimental/Soy-Panel5
	hint "$R"
	expect "soy on panel5: warning" [ "$(result "$R")" = soy-on-genuine ]
	expect "soy on panel5: 1.1 V in text" contains "$R/boot/dtb-hint.txt" "1.1 V"
	expect "soy on panel5: notify warns" contains "$R/test/calls" "WARNING: genuine R36S DTB on a Soy Sauce board"

	# Spanish and Portuguese notifications
	fake "$R" Panels/Panel4 $K1 locale=es-ES
	hint "$R"
	expect "es notification" contains "$R/test/calls" "Puede que el DTB no sea el correcto"
	fake "$R" Panels/Panel4 $K1 locale=pt-BR
	hint "$R"
	expect "pt notification" contains "$R/test/calls" "O DTB pode estar errado"

	# no SARADC reading: hwrev from U-Boot decides
	fake "$R" Panels/Panel4 $K1 adc0=none rev=rg351v
	hint "$R"
	expect "hwrev fallback: wrong-family" [ "$(result "$R")" = wrong-family ]
	expect "hwrev fallback: said so" contains "$R/data/dtb-hint.txt" "from hwrev"

	# board ID outside every band, nothing wrong seen: ok, no claim about the board
	fake "$R" Panels/Panel4 Panels/Panel4 adc0=900 pull=hi
	hint "$R"
	expect "unknown id, fine: ok" [ "$(result "$R")" = ok ]
	expect "unknown id, fine: no board claim" lacks "$R/boot/dtb-hint.txt" "This console looks like"

	# DT without a joypad node: no advice, no notification
	fake "$R" Panels/Panel4 Panels/Panel4 nojoy
	hint "$R"
	expect "no joypad node: unknown-dt" [ "$(result "$R")" = unknown-dt ]
	expect "no joypad node: no notification" [ ! -s "$R/test/calls" ]

	# no BOOT: still the /data copy, no crash
	fake "$R" Panels/Panel4 $K1 noboot
	hint "$R"
	expect "no boot: data copy" contains "$R/data/dtb-hint.txt" "a DTB that is not in Panels or Experimental"
	expect "no boot: exit 0" [ "$(cat "$R/test/rc")" = 0 ]
	expect "no boot: logged" contains "$R/test/log" "BOOT not found"

	# one button held at start is not enough for a verdict
	fake "$R" Panels/Panel4 Panels/Panel4 adc0=900 pull=hi
	sed -i '1s/ in  hi / in  lo /' "$R/sys/kernel/debug/gpio"
	hint "$R"
	expect "one held button: ok" [ "$(result "$R")" = ok ]
	expect "one held button: listed in details" contains "$R/boot/dtb-hint.txt" "buttons held without a touch: B1"
}

for SH in "$@"; do
	command -v "${SH%% *}" >/dev/null 2>&1 || {
		echo "skip $SH (not installed)"
		continue
	}
	check_lists
	cases
	# the full matrix once per shell would take long; run it for the first shell
	if [ "$SH" = "$1" ] && [ -z "${QUICK:-}" ]; then
		matrix lo '' all
		matrix hi
		matrix lo 900
		matrix hi 900
	fi
done

echo "passed $PASS, failed $FAIL"
[ "$FAIL" = 0 ]
