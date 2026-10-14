#!/system/bin/sh
# Settings > Controls > Buttons on this console > Find an unknown button: the root part.
# Reads every GPIO level (the kernel's root-only gpio_levels file, which only reads
# registers) and every SoC ADC channel many times over about 2 seconds, and writes which
# pins stayed low, stayed high or changed, and how far each ADC channel moved.
#
# Started by the Controllers app with ctl.start andr36oid-gpio-scan after setting
# sys.andr36oid.gscan.phase (idle: nothing touched, held: the unknown button held) and
# sys.andr36oid.gscan.request. Writes /data/system/andr36oid-gpio-scan-<phase>.txt
# (readable by all, also kept for the problem report) and answers in
# sys.andr36oid.gscan.result with "<request> <answer>": ok, missing (no gpio_levels in
# this kernel) or invalid.
#
# The file, one line per bank and per ADC channel:
#   gpio2 low=0x........ high=0x........ changing=0x........ dir=0x........ mux=<32> pull=<32>
#   adc saradc 1 min=... max=...
# low/high: pins that read 0/1 in every sample, changing: the others (pin A0 = bit 0).
# dir, mux and pull as the kernel's gpio_levels has them.

# tests point this at a fake /sys and /data
R=${KT_ROOT:-}
LEVELS=$R/sys/devices/platform/pinctrl/gpio_levels
OUT_DIR=$R/data/system
SAMPLES=${GS_SAMPLES:-20}
PAUSE=${GS_PAUSE:-0.1}
# tests swap the pause for "the next fake reading" (mksh has sleep built in)
SLEEP=${GS_SLEEP:-sleep}

request=$(getprop sys.andr36oid.gscan.request)
phase=$(getprop sys.andr36oid.gscan.phase)

answer() {
	setprop sys.andr36oid.gscan.result "$request $1"
	exit 0
}

case $phase in
idle | held) ;;
*) answer invalid ;;
esac
[ -r "$LEVELS" ] || answer missing

banks=
adcs=

# hex8 <value> ; true for 8 lowercase hex digits
hex8() {
	case $1 in
	[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) return 0 ;;
	esac
	return 1
}

# One sample. Each 32-bit level is kept as two 16-bit halves: mksh does its sums in
# 32-bit signed numbers. and_* has the bits that were 1 in every sample, or_* the bits
# that were 1 in any.
sample() {
	while read -r name level dir mux pull; do
		case $name in gpio[0-9]) ;; *) continue ;; esac
		lv=${level#level=0x}
		hex8 "$lv" || continue
		hi=$((0x${lv%????}))
		lo=$((0x${lv#????}))
		if eval "[ -z \"\${seen_$name:-}\" ]"; then
			eval "seen_$name=1 and_hi_$name=$hi and_lo_$name=$lo or_hi_$name=$hi or_lo_$name=$lo"
			banks="$banks $name"
		else
			eval "and_hi_$name=\$((and_hi_$name & hi)) and_lo_$name=\$((and_lo_$name & lo))"
			eval "or_hi_$name=\$((or_hi_$name | hi)) or_lo_$name=\$((or_lo_$name | lo))"
		fi
		rest="$dir $mux $pull"
		eval "rest_$name=\$rest"
	done <"$LEVELS"

	for f in "$R"/sys/bus/iio/devices/iio:device*/in_voltage*_raw; do
		[ -r "$f" ] || continue
		v=
		read -r v <"$f" 2>/dev/null || continue
		case $v in '' | *[!0-9]*) continue ;; esac
		d=${f%/*}
		n=${d##*iio:device}
		c=${f##*/in_voltage}
		c=${c%_raw}
		case $n$c in *[!0-9]*) continue ;; esac
		k=${n}_$c
		if eval "[ -z \"\${amin_$k:-}\" ]"; then
			eval "amin_$k=$v amax_$k=$v"
			adcs="$adcs $k"
		else
			eval "[ $v -lt \$amin_$k ] && amin_$k=$v; [ $v -gt \$amax_$k ] && amax_$k=$v"
		fi
	done
}

i=0
while [ "$i" -lt "$SAMPLES" ]; do
	sample
	i=$((i + 1))
	[ "$i" -lt "$SAMPLES" ] && $SLEEP "$PAUSE"
done

out=$OUT_DIR/andr36oid-gpio-scan-$phase.txt
tmp=$out.tmp
{
	echo "# Find an unknown button, $phase: $SAMPLES samples, $(date '+%Y-%m-%d %H:%M:%S')"
	for b in $banks; do
		eval "ah=\$and_hi_$b al=\$and_lo_$b oh=\$or_hi_$b ol=\$or_lo_$b rest=\$rest_$b"
		# shellcheck disable=SC2154 # set by the eval above
		printf '%s low=0x%04x%04x high=0x%04x%04x changing=0x%04x%04x %s\n' "$b" \
			$((~oh & 0xffff)) $((~ol & 0xffff)) "$ah" "$al" \
			$((ah ^ oh)) $((al ^ ol)) "$rest"
	done
	for k in $adcs; do
		n=${k%_*}
		c=${k#*_}
		name=
		read -r name _ <"$R/sys/bus/iio/devices/iio:device$n/name" 2>/dev/null
		eval "printf 'adc %s %s min=%s max=%s\n' \"\${name:-iio:device$n}\" $c \$amin_$k \$amax_$k"
	done
} >"$tmp" && mv -f "$tmp" "$out" && chmod 644 "$out"
answer ok
