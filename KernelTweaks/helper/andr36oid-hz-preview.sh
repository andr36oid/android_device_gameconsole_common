#!/system/bin/sh
# Settings > Display > Panel overclocking, "Preview for 5 seconds": the root part. Writes
# the rate to the DSI host's preview_rate; the kernel shows it for 5 seconds and then puts
# the old rate back by itself, this only passes the rate on.
#
# Started by Settings with ctl.start andr36oid-hz-preview after setting
# sys.andr36oid.hzp.rate (Hz like 77.5, or 0 to end a preview now) and
# sys.andr36oid.hzp.request. Answers in sys.andr36oid.hzp.result with
# "<request> <answer>": ok, busy (a preview runs or just ended), range (the screen link
# can't carry it), off (the screen is off), invalid, missing (no preview in this kernel)
# or failed.

# tests point this at a fake /sys
R=${KT_ROOT:-}

rate=$(getprop sys.andr36oid.hzp.rate)
request=$(getprop sys.andr36oid.hzp.request)

answer() {
	setprop sys.andr36oid.hzp.result "$request $1"
	exit 0
}

case $rate in
'' | .* | *.*.* | *[!0-9.]*) answer invalid ;;
esac

file=
for f in "$R"/sys/devices/platform/*.dsi/preview_rate; do
	if [ -e "$f" ]; then
		file=$f
		break
	fi
done
[ -n "$file" ] || answer missing

# the kernel's answer comes as the write error. dd, as the shell's echo doesn't say why a
# write failed; bionic and glibc word ERANGE differently
if err=$(echo "$rate" | dd of="$file" conv=notrunc status=none 2>&1); then
	answer ok
fi
case $err in
*[Bb]usy*) answer busy ;;
*range* | *representable*) answer range ;;
*"No such device"*) answer off ;;
*[Ii]nvalid*) answer invalid ;;
*) answer failed ;;
esac
