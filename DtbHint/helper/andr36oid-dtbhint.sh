#!/system/bin/sh
# shellcheck shell=ksh
# DTB hint: after every start, look for signs that the device tree (DTB) on BOOT
# was made for another board, and write BOOT/dtb-hint.txt (English, Spanish,
# Portuguese) with the folder to try. If something looks wrong and the screen
# works, a notification says so too. It never copies or changes a DTB.
#
# What it reads (all read-only):
#   - SARADC channel 0: the board ID resistor. U-Boot reads the same channel for
#     hwrev (rg351mp 146-186, rg351v 494-534, rg351p 655-695, else v00).
#     Genuine R36S wiring reads in the rg351mp band, the EE clones in the rg351v
#     band (dArkOSRE and ROCKNIX pick their DTBs the same way), Soy Sauce boards
#     most likely in the rg351p band (their firmware ships rg351p files).
#     ro.boot.revision (boot.ini passes hwrev) is the fallback.
#   - the joypad node of the running DT: its stick mux enable pin says which
#     wiring the DT was made for;
#   - whether the joypad driver came up (input device "GO-Super Gamepad"),
#     buttons that read "pressed" while nobody touches them (debugfs gpio),
#     volume keys on SARADC channel 2 that read "pressed" (EE DTs), sticks that
#     sit at the end of their range (getevent -p);
#   - pin clash messages in dmesg;
#   - which BOOT folder the DTB at the top of BOOT came from (file compare).
# The screen type can't be read: the panel has no ID the kernel or U-Boot asks for.
#
# The DTBHINT_* variables are for tests on a PC (tests/test-dtbhint.sh).

ROOT=${DTBHINT_ROOT:-}
PROC=$ROOT/proc
SYS=$ROOT/sys
DATA=${DTBHINT_DATA:-/data/misc/andr36oid-dtbhint}
BOOT_MNT=${DTBHINT_BOOT_MNT:-/mnt/andr36oid-dtbhint-boot}
BOOT_DEVS=${DTBHINT_BOOT_DEVS:-"/dev/block/by-name/BOOT /dev/block/platform/ff370000.dwmmc/by-name/BOOT /dev/block/mmcblk0p1"}
UBOOT_DEVS=${DTBHINT_UBOOT_DEVS:-"/dev/block/by-name/uboot /dev/block/platform/ff370000.dwmmc/by-name/uboot /dev/block/mmcblk0p4"}
# Test mode: a folder; dmesg, getevent and getprop answers come from files in
# $ROOT, BOOT is the folder $DTBHINT_BOOT, the notification goes to $TEST/calls
TEST=${DTBHINT_TEST:-}
# one BOOT user at a time, see boot_lock (in test mode: in $TEST)
BOOT_LOCK=${DTBHINT_BOOT_LOCK:-/dev/andr36oid-boot.lock}
[ -n "$TEST" ] && [ -z "${DTBHINT_BOOT_LOCK:-}" ] && BOOT_LOCK=$TEST/boot.lock
BOOT_LOCK_WAIT=${DTBHINT_BOOT_LOCK_WAIT:-30}
BOOT=
BOOT_RW=
BOOT_MOUNTED=
# wait this long after boot_completed, so nobody still holds a button from the start
WAIT=${DTBHINT_WAIT:-20}
# and this long between the two button reads
GAP=${DTBHINT_GAP:-3}

HINT=dtb-hint.txt
TAG=andr36oid-dtbhint
DTB=rk3326-r36s-android.dtb
CR=$(printf '\r')

# Folders per wiring. tests/test-dtbhint.sh checks these against tests/boards.txt,
# which is made from the DTBs (tests/gen-boards.py).
F_GENUINE="Panels/Panel0 Panels/Panel1 Panels/Panel2 Panels/Panel3 Panels/Panel4 Panels/Panel5 Panels/Panel6 Panels/R36S-Plus Experimental/R36S-V21 Experimental/R36S-V21-2551-2552 Experimental/R36S-V22 Experimental/R36S-Y02-Y07 Experimental/R36S-V30 Experimental/R36S-V30-2603 Experimental/R36S-Panel3-Alt"
F_SOY="Experimental/Soy-Panel5 Experimental/Soy-Panel5-253x Experimental/Soy-Panel6 Experimental/Soy-Panel7 Experimental/Soy-Panel7-253x Experimental/Soy-V04-Panel2 Experimental/Soy-V05-2601"
# EE boards, stick mux enable on GPIO2_B4 (K36 style) ...
F_EE_B4="Experimental/EE-K36-Panel1 Experimental/Kinhank-K36 Experimental/EE-K36-Panel4 Experimental/RX6H Experimental/EE-V12-Var2 Experimental/K36S Experimental/R36T Experimental/XF35H Experimental/R36-Max Experimental/S600 Experimental/R39-Max Experimental/T16-Max Experimental/XF40H"
# ... and on GPIO2_B3 (G80 style)
F_EE_B3="Experimental/EE-G80-Panel8 Experimental/EE-V12-Var1-Panel8 Experimental/EE-G80-Panel9 Experimental/EE-V12-Var3-Panel3 Experimental/EE-V12-Var3-PanelA Experimental/R36-Ultra"

log() {
	if [ -n "$TEST" ]; then
		echo "log: $*" >>"$TEST/log"
	else
		/system/bin/log -t "$TAG" "$*" 2>/dev/null
	fi
	return 0
}

# ---- small readers; each prints nothing when the source is missing ----

# info <name> <value> ; for Settings > About console > Console details, which
# can't read the board ID, BOOT or the U-Boot partition itself:
# andr36oid.info.<name>
info() {
	if [ -n "$TEST" ]; then
		echo "andr36oid.info.$1=$2" >>"$TEST/info"
	else
		setprop "andr36oid.info.$1" "$2" 2>/dev/null
	fi
}

# uboot_version ; "2017.09-gf293360118" from the U-Boot partition, empty if
# it can't be read
uboot_version() {
	local dev
	for dev in $UBOOT_DEVS; do
		[ -r "$dev" ] || continue
		head -c 4194304 "$dev" 2>/dev/null | grep -a -o -m 1 'U-Boot 20[0-9][0-9]\.[0-9][0-9][-0-9a-z]*' |
			head -n 1 | sed 's/^U-Boot //'
		return 0
	done
}

prop() {
	if [ -n "$TEST" ]; then
		sed -n "s/^$1=//p" "$ROOT/props" 2>/dev/null | head -n 1
	else
		getprop "$1" 2>/dev/null
	fi
}

kmsg() {
	if [ -n "$TEST" ]; then
		cat "$ROOT/dmesg.txt" 2>/dev/null
	else
		dmesg 2>/dev/null
	fi
}

events() {
	if [ -n "$TEST" ]; then
		cat "$ROOT/getevent.txt" 2>/dev/null
	else
		getevent -p 2>/dev/null
	fi
}

nap() {
	[ -n "$TEST" ] || sleep "$1"
}

# be32 <file> <index> ; the index-th big-endian 32-bit cell of a DT property
be32() {
	local b
	[ -r "$1" ] || return 0
	b=$(od -A n -t u1 -j $(($2 * 4)) -N 4 "$1" 2>/dev/null) || return 0
	# shellcheck disable=SC2086 # the four bytes are split on purpose
	set -- $b
	[ $# -eq 4 ] || return 0
	echo $((($1 << 24) + ($2 << 16) + ($3 << 8) + $4))
}

# adc <channel> ; raw SARADC reading (10 bit), empty if there is none
adc() {
	local d n v
	for d in "$SYS"/bus/iio/devices/iio:device*; do
		[ -r "$d/name" ] || continue
		read -r n <"$d/name"
		case $n in
		*saradc*) ;;
		*) continue ;;
		esac
		v=
		read -r v <"$d/in_voltage$1_raw" 2>/dev/null
		case $v in
		'' | *[!0-9]*) return 0 ;;
		esac
		echo "$v"
		return 0
	done
}

# stuck ; labels of the buttons that read low (= pressed) now, one per line
stuck() {
	[ -r "$SYS/kernel/debug/gpio" ] || return 0
	# " gpio-118 (                    |GPIO DPAD-UP        ) in  lo IRQ"
	sed -n 's/^ *gpio-[0-9]* *([^|]*|\(GPIO [^)]*\)) in  lo.*$/\1/p' "$SYS/kernel/debug/gpio" |
		sed 's/ *$//' | sort
}

# ---- facts ----

gather() {
	local a r s1 s2 dt
	dt=$PROC/device-tree
	MODEL=$(tr -d '\0' <"$dt/model" 2>/dev/null)

	# wiring the running DT was made for: stick mux enable pin number in its bank
	# (GPIO3_B5 = 13 genuine, GPIO2_B4 = 12 and GPIO2_B3 = 11 EE, GPIO0_C1 = 17 Soy)
	DT_WIRING=unknown
	case $(be32 "$dt/odroidgo3-joypad/amux-en-gpios" 1) in
	13) DT_WIRING=genuine ;;
	12) DT_WIRING=ee-b4 ;;
	11) DT_WIRING=ee-b3 ;;
	17) DT_WIRING=soy ;;
	esac

	# the board: ID resistor on SARADC channel 0, else what U-Boot made of it
	ADC0=$(adc 0)
	ADC2=$(adc 2)
	REV=$(prop ro.boot.revision)
	[ -n "$REV" ] || REV=$(sed -n 's/.*androidboot\.revision=\([^ ]*\).*/\1/p' "$PROC/cmdline" 2>/dev/null)
	HW=unknown
	HW_FROM=none
	if [ -n "$ADC0" ]; then
		a=$ADC0
		HW_FROM="ADC0 $a"
		if [ "$a" -ge 139 ] && [ "$a" -le 191 ]; then
			HW=genuine
		elif [ "$a" -ge 489 ] && [ "$a" -le 541 ]; then
			HW=ee
		elif [ "$a" -ge 650 ] && [ "$a" -le 705 ]; then
			HW=soy
		fi
	elif [ -n "$REV" ]; then
		HW_FROM="hwrev $REV"
		case $REV in
		rg351mp) HW=genuine ;;
		rg351v) HW=ee ;;
		rg351p) HW=soy ;;
		esac
	fi

	# joypad driver up?
	JOY_DT=no
	if [ -d "$dt/odroidgo3-joypad" ]; then
		r=$(tr -d '\0' <"$dt/odroidgo3-joypad/status" 2>/dev/null)
		case $r in
		'' | okay | ok) JOY_DT=yes ;;
		esac
	fi
	JOY_UP=no
	grep -q '^N: Name="GO-Super Gamepad"' "$PROC/bus/input/devices" 2>/dev/null && JOY_UP=yes

	# buttons that read pressed twice, a few seconds apart
	s1=$(stuck)
	nap "$GAP"
	s2=$(stuck)
	STUCK=
	STUCK_N=0
	if [ -n "$s1" ] && [ -n "$s2" ]; then
		STUCK=$(printf '%s\n' "$s1" | while IFS= read -r l; do
			printf '%s\n' "$s2" | grep -q -x -F -- "$l" && printf '%s, ' "${l#GPIO }"
		done)
		STUCK=${STUCK%, }
		[ -n "$STUCK" ] && STUCK_N=$(printf '%s' "$STUCK" | tr ',' '\n' | wc -l | tr -d ' ')
	fi

	# EE DTs read the volume keys on SARADC channel 2 (1.8 V idle); below about
	# 1.05 V the driver reports a key held
	VOL_STUCK=no
	if [ -d "$dt/adc-keys" ] && [ -n "$ADC2" ] && [ "$ADC2" -lt 595 ]; then
		VOL_STUCK=yes
	fi

	# sticks at the end of their range (-900..900 in this driver)
	RAIL=$(events | awk '
		/^add device/ { pad = 0 }
		/name:[ \t]*"GO-Super Gamepad"/ { pad = 1 }
		pad && /value -?[0-9]+, min -?[0-9]+, max -?[0-9]+/ {
			line = $0
			sub(/^.*\(0003\):/, "", line)
			nf = split(line, f, /[ ,:]+/)
			code = ""; v = ""; lo = ""; hi = ""
			for (i = 1; i <= nf; i++) {
				if (code == "" && f[i] ~ /^[0-9a-f]+$/) code = f[i]
				if (f[i] == "value") v = f[i + 1] + 0
				if (f[i] == "min") lo = f[i + 1] + 0
				if (f[i] == "max") hi = f[i + 1] + 0
			}
			if (code != "0000" && code != "0001" && code != "0003" && code != "0004") next
			if (hi <= lo) next
			m = (hi - lo) / 20
			if (v <= lo + m || v >= hi - m) cnt++
		}
		END { print cnt + 0 }')

	# pin clash lines from the kernel (the EE UART2 bug looked like this)
	CLASH=$(kmsg | grep -E 'already requested by|cannot claim for|Error applying setting|odroidgo3-joypad.*(fail|error)' | head -n 6 |
		sed 's/^\[[ 0-9.]*\] *//')
	SERIAL_CLASH=no
	printf '%s\n' "$CLASH" | grep -q 'serial' && SERIAL_CLASH=yes
	return 0
}

# ---- BOOT ----

# boot_lock <seconds> ; the same in andr36oid-report.sh, andr36oid-dtbhint.sh and
# andr36oid-cardsetup.sh. The start-up helpers take turns with BOOT: Linux won't
# mount it a second time with the other ro/rw flag. The lock is a folder in /dev
# (a restart clears it) with the holder's pid; a holder that was killed is
# noticed by its pid, and its BOOT mount is taken away. False after waiting
# this long.
boot_lock() {
	local n=0 pid m
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
	grep -o ' /mnt/andr36oid-[a-z]*-boot ' "$PROC/mounts" 2>/dev/null | while read -r m; do
		boot_cmd umount "$m"
	done
	return 0
}

boot_unlock() {
	[ "$(cat "$BOOT_LOCK/pid" 2>/dev/null)" = "$$" ] && rm -rf "$BOOT_LOCK"
}

# boot_cmd <mount or umount> <args...> ; only recorded in test mode
boot_cmd() {
	if [ -n "$TEST" ]; then
		echo "$*" >>"$TEST/boot"
		return 0
	fi
	"$@" 2>/dev/null
}

# boot_open ; mounts BOOT read-only, holds the BOOT lock until boot_close
boot_open() {
	local dev
	BOOT=
	BOOT_RW=
	BOOT_MOUNTED=
	if ! boot_lock "$BOOT_LOCK_WAIT"; then
		log "BOOT is busy (another start-up helper)"
		return 1
	fi
	if [ -n "$TEST" ]; then
		if [ -d "${DTBHINT_BOOT:-}" ]; then
			BOOT=$DTBHINT_BOOT
			boot_cmd mount -o ro
			return 0
		fi
		boot_unlock
		return 1
	fi
	mkdir -p "$BOOT_MNT"
	for dev in $BOOT_DEVS; do
		[ -e "$dev" ] || continue
		mount -t vfat -o ro,noatime "$dev" "$BOOT_MNT" 2>/dev/null || continue
		BOOT=$BOOT_MNT
		BOOT_MOUNTED=1
		return 0
	done
	boot_unlock
	return 1
}

# boot_rw ; makes the open BOOT writable, only when there is something to write
boot_rw() {
	[ -n "$BOOT_RW" ] && return 0
	if [ -n "$TEST" ]; then
		boot_cmd mount -o remount,rw
		[ -e "$BOOT/.readonly" ] || BOOT_RW=1
	elif mount -o remount,rw "$BOOT_MNT" 2>/dev/null; then
		BOOT_RW=1
	fi
	[ -n "$BOOT_RW" ]
}

boot_close() {
	if [ -n "$BOOT_MOUNTED" ]; then
		sync
		umount "$BOOT_MNT" 2>/dev/null
	fi
	[ -n "$TEST" ] && [ -n "$BOOT" ] && boot_cmd umount
	BOOT_MOUNTED=
	BOOT_RW=
	boot_unlock
}

# which BOOT folder the top DTB is a copy of (Panels first: Panel4 is also the top)
installed_folder() {
	local d
	FOLDER=
	[ -f "$BOOT/$DTB" ] || return 0
	for d in "$BOOT"/Panels/*/ "$BOOT"/Experimental/*/; do
		[ -f "$d$DTB" ] || continue
		if cmp -s "$BOOT/$DTB" "$d$DTB"; then
			d=${d%/}
			FOLDER=${d#"$BOOT"/}
			return 0
		fi
	done
}

# ---- verdict ----

# family <wiring> ; genuine, ee or soy
family() {
	case $1 in
	ee-*) echo ee ;;
	*) echo "$1" ;;
	esac
}

decide() {
	local dtf
	dtf=$(family "$DT_WIRING")
	SYMPTOMS=no
	if { [ "$JOY_DT" = yes ] && [ "$JOY_UP" = no ]; } || [ "$STUCK_N" -ge 2 ] ||
		[ "$VOL_STUCK" = yes ] || [ "${RAIL:-0}" -ge 2 ]; then
		SYMPTOMS=yes
	fi

	SUGGEST=
	if [ "$DT_WIRING" = unknown ]; then
		VERDICT=unknown-dt
	elif [ "$HW" != unknown ] && [ "$HW" != "$dtf" ]; then
		if [ "$HW" = soy ] && [ "$dtf" = genuine ]; then
			VERDICT=soy-on-genuine
		else
			VERDICT=wrong-family
		fi
		SUGGEST=$HW
	elif [ "$SYMPTOMS" = no ]; then
		VERDICT=ok
		# A black screen shows no symptoms, and a V12 wired like the G80 runs the
		# K36-kind Var2 without a stuck button: offer every EE folder
		if [ "$dtf" = ee ]; then SUGGEST=ee; else SUGGEST=$DT_WIRING; fi
	elif [ "$dtf" = ee ] && [ "$SERIAL_CLASH" = yes ]; then
		VERDICT=old-ee-dtb
		SUGGEST=$DT_WIRING
	elif [ "$dtf" = ee ] && [ "$VOL_STUCK" = no ]; then
		# every EE board has the volume ladder on channel 2, so with working
		# volume keys the board is EE and only the stick/button wiring differs
		VERDICT=other-ee
		if [ "$DT_WIRING" = ee-b4 ]; then SUGGEST=ee-b3; else SUGGEST=ee-b4; fi
	elif [ "$HW" = unknown ]; then
		# the board ID wasn't recognised and the buttons don't fit: Soy Sauce boards
		# are the ones whose ID we haven't seen yet
		VERDICT=symptoms-unknown-board
		if [ "$dtf" = soy ]; then SUGGEST=genuine; else SUGGEST=soy; fi
	else
		VERDICT=symptoms-same-family
		SUGGEST=$DT_WIRING
	fi
}

# folders <wiring or family> ; the folder list, 3 per line, indented
folders() {
	local l
	case $1 in
	genuine) l=$F_GENUINE ;;
	soy) l=$F_SOY ;;
	ee) l="$F_EE_B4 $F_EE_B3" ;;
	ee-b4) l=$F_EE_B4 ;;
	ee-b3) l=$F_EE_B3 ;;
	*) l= ;;
	esac
	# shellcheck disable=SC2086 # one word per folder
	printf '%s\n' $l | awk '{ printf "%s%s", (NR % 3 == 1 ? "    " : ", "), $0; if (NR % 3 == 0) print "" } END { if (NR % 3) print "" }'
}

name_en() {
	case $1 in
	genuine) echo "an R36S (genuine board wiring)" ;;
	soy) echo "a \"Soy Sauce\" R36S clone (board print Y3506)" ;;
	ee) echo "an \"EE\" clone (K36, G80, HG36, V12, R36 Max/Ultra, ...)" ;;
	ee-b4) echo "an \"EE\" clone of the K36 kind" ;;
	ee-b3) echo "an \"EE\" clone of the G80 kind" ;;
	*) echo "an unknown board" ;;
	esac
}
name_es() {
	case $1 in
	genuine) echo "una R36S (placa original)" ;;
	soy) echo "un clon \"Soy Sauce\" de la R36S (placa Y3506)" ;;
	ee) echo "un clon \"EE\" (K36, G80, HG36, V12, R36 Max/Ultra, ...)" ;;
	ee-b4) echo "un clon \"EE\" del tipo K36" ;;
	ee-b3) echo "un clon \"EE\" del tipo G80" ;;
	*) echo "una placa desconocida" ;;
	esac
}
name_pt() {
	case $1 in
	genuine) echo "um R36S (placa original)" ;;
	soy) echo "um clone \"Soy Sauce\" do R36S (placa Y3506)" ;;
	ee) echo "um clone \"EE\" (K36, G80, HG36, V12, R36 Max/Ultra, ...)" ;;
	ee-b4) echo "um clone \"EE\" do tipo K36" ;;
	ee-b3) echo "um clone \"EE\" do tipo G80" ;;
	*) echo "uma placa desconhecida" ;;
	esac
}

# the three language parts
text_en() {
	local now="${FOLDER:-a DTB that is not in Panels or Experimental}"
	echo "DTB HINT (written by the console at every start)"
	echo "==============================================="
	echo ""
	echo "Now in use: $now"
	echo "It was made for $(name_en "$DT_WIRING")."
	[ "$HW" = unknown ] || echo "This console looks like $(name_en "$HW")."
	echo ""
	case $VERDICT in
	ok)
		echo "Nothing looks wrong: the buttons and sticks fit this DTB."
		echo "If the picture is black, garbled or the colours are wrong, only the"
		echo "screen type differs. Try the other folders of the same kind:"
		;;
	soy-on-genuine)
		echo "WARNING: this DTB is for the genuine R36S board. On a Soy Sauce board"
		echo "it puts 3.3 V on a power rail that should get 1.1 V. Switch the"
		echo "console off soon and use one of these folders instead:"
		;;
	wrong-family)
		echo "This DTB is for another board. Buttons, sticks, volume keys or"
		echo "charging will not work right. Use one of these folders instead:"
		;;
	old-ee-dtb)
		echo "The buttons don't work because this DTB is from an older release"
		echo "(the serial port takes a button pin). Take the same folder from a"
		echo "newer andr36oid release. The folders for this kind of board:"
		;;
	other-ee)
		echo "The buttons or sticks don't fit this DTB. EE clones come in two"
		echo "wirings; try a folder of the other one:"
		;;
	symptoms-unknown-board)
		echo "The buttons or sticks don't fit this DTB, and the board ID was not"
		echo "recognised. Try one of these folders:"
		;;
	symptoms-same-family)
		echo "The buttons or sticks don't fit this DTB, but the board looks like"
		echo "the right kind. Try another folder of the same kind:"
		;;
	*)
		echo "The DTB in use has no joypad the check knows. Pick a folder by the"
		echo "READMEs in Panels and Experimental."
		;;
	esac
	folders "$SUGGEST"
	echo ""
	echo "How: put the SD card in a computer, open the BOOT drive, back up the"
	echo "files at the top, then copy ALL files of the folder to the top of BOOT."
	echo "Details: Panels/README.txt and Experimental/README.txt. The screen type"
	echo "can't be detected: if the picture stays black, try the next folder."
}

text_es() {
	local now="${FOLDER:-un DTB que no esta en Panels ni en Experimental}"
	echo "PISTA DE DTB (la consola escribe esto en cada arranque)"
	echo "======================================================="
	echo ""
	echo "En uso ahora: $now"
	echo "Se hizo para $(name_es "$DT_WIRING")."
	[ "$HW" = unknown ] || echo "Esta consola parece $(name_es "$HW")."
	echo ""
	case $VERDICT in
	ok)
		echo "No se ve nada mal: los botones y los sticks encajan con este DTB."
		echo "Si la imagen sale negra, rara o con colores mal, solo cambia el tipo"
		echo "de pantalla. Prueba las otras carpetas del mismo tipo:"
		;;
	soy-on-genuine)
		echo "AVISO: este DTB es para la placa original de la R36S. En una placa"
		echo "Soy Sauce pone 3,3 V en una linea que debe tener 1,1 V. Apaga pronto"
		echo "la consola y usa una de estas carpetas:"
		;;
	wrong-family)
		echo "Este DTB es para otra placa. Botones, sticks, volumen o la carga no"
		echo "van a funcionar bien. Usa una de estas carpetas:"
		;;
	old-ee-dtb)
		echo "Los botones no funcionan porque este DTB es de una version antigua"
		echo "(el puerto serie ocupa un pin de boton). Copia la misma carpeta de"
		echo "una version mas nueva de andr36oid. Carpetas para este tipo de placa:"
		;;
	other-ee)
		echo "Los botones o los sticks no encajan con este DTB. Los clones EE"
		echo "tienen dos cableados; prueba una carpeta del otro:"
		;;
	symptoms-unknown-board)
		echo "Los botones o los sticks no encajan con este DTB y no se reconocio"
		echo "la placa. Prueba una de estas carpetas:"
		;;
	symptoms-same-family)
		echo "Los botones o los sticks no encajan con este DTB, aunque la placa"
		echo "parece del tipo correcto. Prueba otra carpeta del mismo tipo:"
		;;
	*)
		echo "El DTB en uso no tiene un mando que la prueba conozca. Elige una"
		echo "carpeta con los README de Panels y Experimental."
		;;
	esac
	folders "$SUGGEST"
	echo ""
	echo "Como: pon la tarjeta SD en un ordenador, abre la unidad BOOT, guarda"
	echo "una copia de los archivos de arriba y copia TODOS los archivos de la"
	echo "carpeta arriba del todo en BOOT. El tipo de pantalla no se puede"
	echo "detectar: si la imagen sigue negra, prueba la siguiente carpeta."
}

text_pt() {
	local now="${FOLDER:-um DTB que nao esta em Panels nem em Experimental}"
	echo "DICA DE DTB (o console escreve isto a cada inicio)"
	echo "=================================================="
	echo ""
	echo "Em uso agora: $now"
	echo "Foi feito para $(name_pt "$DT_WIRING")."
	[ "$HW" = unknown ] || echo "Este console parece $(name_pt "$HW")."
	echo ""
	case $VERDICT in
	ok)
		echo "Nada parece errado: os botoes e os analogicos combinam com este DTB."
		echo "Se a imagem fica preta, embaralhada ou com cores erradas, so muda o"
		echo "tipo de tela. Teste as outras pastas do mesmo tipo:"
		;;
	soy-on-genuine)
		echo "ATENCAO: este DTB e para a placa original do R36S. Numa placa Soy"
		echo "Sauce ele coloca 3,3 V numa linha que deve ter 1,1 V. Desligue o"
		echo "console logo e use uma destas pastas:"
		;;
	wrong-family)
		echo "Este DTB e para outra placa. Botoes, analogicos, volume ou a carga"
		echo "nao vao funcionar direito. Use uma destas pastas:"
		;;
	old-ee-dtb)
		echo "Os botoes nao funcionam porque este DTB e de uma versao antiga (a"
		echo "porta serial ocupa um pino de botao). Copie a mesma pasta de uma"
		echo "versao mais nova do andr36oid. Pastas para este tipo de placa:"
		;;
	other-ee)
		echo "Os botoes ou os analogicos nao combinam com este DTB. Os clones EE"
		echo "tem duas ligacoes; teste uma pasta da outra:"
		;;
	symptoms-unknown-board)
		echo "Os botoes ou os analogicos nao combinam com este DTB e a placa nao"
		echo "foi reconhecida. Teste uma destas pastas:"
		;;
	symptoms-same-family)
		echo "Os botoes ou os analogicos nao combinam com este DTB, mas a placa"
		echo "parece do tipo certo. Teste outra pasta do mesmo tipo:"
		;;
	*)
		echo "O DTB em uso nao tem um controle que o teste conheca. Escolha uma"
		echo "pasta pelos README de Panels e Experimental."
		;;
	esac
	folders "$SUGGEST"
	echo ""
	echo "Como: coloque o cartao SD num computador, abra a unidade BOOT, faca"
	echo "uma copia dos arquivos do topo e copie TODOS os arquivos da pasta para"
	echo "o topo do BOOT. O tipo de tela nao pode ser detectado: se a imagem"
	echo "continuar preta, teste a proxima pasta."
}

# stable facts only, so the file on BOOT doesn't change at every start
details() {
	echo "Details for support"
	echo "-------------------"
	echo "result: $VERDICT"
	echo "DT model: ${MODEL:-?}"
	echo "DT wiring: $DT_WIRING"
	echo "board ID: $HW (from ${HW_FROM%% *}, about $(((${ADC0:-0} + 5) / 10 * 10)); U-Boot hwrev ${REV:-?})"
	echo "joypad driver: $JOY_UP (in DT: $JOY_DT)"
	echo "buttons held without a touch: ${STUCK:-none}"
	echo "volume keys held (ADC): $VOL_STUCK"
	echo "sticks at the edge: ${RAIL:-0}"
	[ -z "$CLASH" ] || {
		echo "kernel:"
		printf '%s\n' "$CLASH" | sed 's/^/  /'
	}
}

compose() {
	text_en
	echo ""
	echo ""
	text_es
	echo ""
	echo ""
	text_pt
	echo ""
	echo ""
	details
}

notify() {
	local lang title body
	[ "$VERDICT" = ok ] && return 0
	[ "$VERDICT" = unknown-dt ] && return 0
	lang=$(prop persist.sys.locale)
	[ -n "$lang" ] || lang=$(prop ro.product.locale)
	case $lang in
	es*)
		title="Puede que el DTB no sea el correcto"
		body="Los botones o la placa no encajan con el DTB en uso. Mira dtb-hint.txt en la unidad BOOT de la tarjeta SD."
		;;
	pt*)
		title="O DTB pode estar errado"
		body="Os botoes ou a placa nao combinam com o DTB em uso. Veja dtb-hint.txt na unidade BOOT do cartao SD."
		;;
	*)
		title="The device tree (DTB) may be wrong"
		body="The buttons or the board don't fit the DTB in use. See dtb-hint.txt on the BOOT drive of the SD card."
		;;
	esac
	[ "$VERDICT" = soy-on-genuine ] && case $lang in
	es*) body="AVISO: DTB de la R36S original en una placa Soy Sauce. Apaga la consola y mira dtb-hint.txt en BOOT." ;;
	pt*) body="ATENCAO: DTB do R36S original numa placa Soy Sauce. Desligue o console e veja dtb-hint.txt no BOOT." ;;
	*) body="WARNING: genuine R36S DTB on a Soy Sauce board. Switch off and see dtb-hint.txt on BOOT." ;;
	esac
	if [ -n "$TEST" ]; then
		echo "notify: $title | $body" >>"$TEST/calls"
	else
		cmd notification post -S bigtext --bigtext "$body" -t "$title" "$TAG" "$body" >/dev/null 2>&1 ||
			log "notification failed"
	fi
}

main() {
	local new
	nap "$WAIT"
	gather
	info uboot "$(uboot_version)"
	# R46H, R50S, D-28S and G350 have their own DTB in BOOT/Devices; the folders and board
	# checks here are for R36S boards and would only point them the wrong way
	case $MODEL in
	*R46H* | *R50S* | *D-28S* | *G350*)
		log "$MODEL: no hint for this console"
		return 0
		;;
	esac
	# the board check is for R36S boards too
	info board "$HW"
	info board_id "${ADC0:-}"
	mkdir -p "$DATA" 2>/dev/null
	new=$DATA/hint.new
	if boot_open; then
		installed_folder
		info panel "${FOLDER:-custom}"
	else
		FOLDER=
		log "BOOT not found"
	fi
	decide
	log "result $VERDICT: dt=$DT_WIRING hw=$HW ($HW_FROM) folder=${FOLDER:-?} joypad=$JOY_UP stuck=$STUCK_N vol=$VOL_STUCK rail=$RAIL"
	compose | sed "s/\$/$CR/" >"$new"
	# the full raw readings stay on /data for the problem report
	{
		compose
		echo "raw: adc0=${ADC0:-} adc2=${ADC2:-} rev=${REV:-} stuck_n=$STUCK_N"
	} >"$DATA/$HINT"
	if [ -n "$BOOT" ]; then
		# read-only until here: writable only when the text changed
		if ! cmp -s "$new" "$BOOT/$HINT"; then
			if ! boot_rw; then
				log "BOOT is read-only, $HINT not written"
			elif cp "$new" "$BOOT/$HINT.tmp" && mv "$BOOT/$HINT.tmp" "$BOOT/$HINT"; then
				log "wrote $HINT on BOOT"
			else
				rm -f "$BOOT/$HINT.tmp"
				log "could not write $HINT on BOOT"
			fi
		fi
		boot_close
	fi
	rm -f "$new"
	notify
	return 0
}

main "$@"
