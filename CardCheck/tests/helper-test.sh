#!/bin/bash
# Host test of the root helper (helper/andr36oid-cardcheck.sh): speed test with the small
# block tool and the bus mode, and the real size check: pass, pause + resume, delete,
# bad data, a card that wraps around, and a power cut in the middle.
#
#   tests/helper-test.sh <shell> <andr36oid-cardspeed built for the host>
#
# The card is a plain file, the card's file system a temp dir. Commands the console has
# and the host doesn't (am, log, blockdev, dmesg, logcat) are small scripts in $T/bin;
# "sync" is one too, so the test can pause, corrupt or wrap the card at a given moment.
# A new size check checks the card is a block device ([ -b ]); only bash can be made to
# accept a plain file there (enable -n [), so other shells get the resume and delete
# paths, starting from a pause made with bash.
set -u
SH=$1
CARDSPEED=$2
HERE=$(cd "$(dirname "$0")/.." && pwd)
HELPER=$HERE/helper/andr36oid-cardcheck.sh
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fails=0
checks=0

ok() {
	checks=$((checks + 1))
	if [ "$2" != "$3" ]; then
		echo "FAIL [$SH] $1: got '$2', want '$3'"
		fails=$((fails + 1))
	fi
}

# test data and progress files left in the app's folder
leftovers() { find "$DIR" -name 'head*' -o -name 'sums*' -o -name 'progress*' | wc -l; }

key() { sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n 1; }

DIR=$T/data
ROOT=$T/media/ABCD-1234
DEV=$T/dev/mmcblk0
mkdir -p "$DIR" "$ROOT" "$T/dev" "$T/bin" "$T/sys/dev/block/179:0"
echo DEVNAME=mmcblk0 >"$T/sys/dev/block/179:0/uevent"
ln -s ../../../mmc0:59b4 "$T/sys/dev/block/179:0/device"

for c in am log blockdev; do
	printf '#!/bin/sh\nexit 0\n' >"$T/bin/$c"
done
cat >"$T/bin/dmesg" <<'EOF'
#!/bin/sh
echo "[    2.1] mmc1: new high speed SDHC card at address 59b4"
echo "[    2.3] mmc0: new high speed SDXC card at address 59b4"
echo "[  900.4] mmc0: new ultra high speed SDR104 SDXC card at address 59b4"
echo "[  900.5] mmcblk0: mmc0:59b4 SN64G 59.5 GiB"
EOF
printf '#!/bin/sh\nexit 0\n' >"$T/bin/logcat"
# sync: after $SYNC_AT calls do $SYNC_DO (pause, wrap, corrupt, stop)
cat >"$T/bin/sync" <<EOF
#!/bin/sh
n=\$(cat "$T/sync.count" 2>/dev/null || echo 0)
n=\$((n + 1))
echo \$n >"$T/sync.count"
[ -n "\${SYNC_AT:-}" ] && [ "\$n" -eq "\$SYNC_AT" ] || exit 0
case \$SYNC_DO in
pause) echo pause >"$DIR/stop" ;;
stop) : >"$DIR/stop" ;;
wrap) head -c 4096 /dev/zero | dd of="$DEV" conv=notrunc 2>/dev/null ;;
corrupt) f=\$(find "$ROOT/.andr36oid-cardcheck" -name 'f002.bin' | head -n 1)
	printf 'X' | dd of="\$f" bs=1 seek=100 conv=notrunc 2>/dev/null ;;
esac
EOF
# [ for bash with enable -n [: a plain file counts as a block device
cat >"$T/bin/[" <<'EOF'
#!/bin/bash
[ "$1" = -b ] && [ "$#" -eq 3 ] && exec /usr/bin/[ -f "$2" ]
exec /usr/bin/[ "$@"
EOF
chmod +x "$T/bin/"*

new_card() {
	rm -rf "$ROOT" "$DIR"
	mkdir -p "$ROOT" "$DIR"
	head -c 4194304 /dev/urandom >"$DEV"
	cp "$DEV" "$T/dev.orig"
}

# run <shell> <action> [VAR=value ...] : one start of the service, as init would do it
run() {
	local sh=$1 action=$2
	shift 2
	printf 'action=%s\npath=%s\ndisk=179:0\ndisk_mb=128\n' "$action" "$ROOT" >"$DIR/request"
	rm -f "$T/sync.count"
	if [ "$sh" = bash ]; then
		# shellcheck disable=SC2016 # $0 is for the inner bash
		env -i PATH="$T/bin:/usr/bin:/bin" CARDCHECK_DIR="$DIR" CARDCHECK_MEDIA="$T/media" \
			CARDCHECK_SYS="$T/sys" CARDCHECK_DEVDIR="$T/dev" CARDCHECK_CARDSPEED="$CARDSPEED" \
			CARDCHECK_RAND_SECONDS=1 CARDCHECK_FREE_MB=160 "$@" \
			bash -c 'enable -n [ test; . "$0"' "$HELPER"
	else
		env -i PATH="$T/bin:/usr/bin:/bin" CARDCHECK_DIR="$DIR" CARDCHECK_MEDIA="$T/media" \
			CARDCHECK_SYS="$T/sys" CARDCHECK_DEVDIR="$T/dev" CARDCHECK_CARDSPEED="$CARDSPEED" \
			CARDCHECK_RAND_SECONDS=1 CARDCHECK_FREE_MB=160 "$@" \
			"$sh" "$HELPER"
	fi
}

RES=$DIR/result-full-ABCD-1234
PROG=$DIR/progress-ABCD-1234

# ---- speed ----
new_card
run "$SH" speed CARDCHECK_FREE_MB=900
ok "speed needs 1 GB free" "$(key "$DIR/status" error)" no_space
run "$SH" speed CARDCHECK_FREE_MB=2000
S=$DIR/result-speed-ABCD-1234
ok "speed state" "$(key "$S" state)" "done"
ok "speed size" "$(key "$S" size_mb)" 256
ok "speed bus from the newest mmc0 line" "$(key "$S" bus)" ultra_high_speed_SDR104_SDXC
ok "speed direct" "$(key "$S" direct)" 1
case $(key "$S" rr_ops) in ''|*[!0-9]*) ok "rr_ops is a number" "$(key "$S" rr_ops)" N ;; esac
case $(key "$S" rw_s) in [0-9]*.[0-9]*) ;; *) ok "rw_s is a time" "$(key "$S" rw_s)" S ;; esac
ok "speed file gone" "$(ls -A "$ROOT")" ""
run "$SH" speed CARDCHECK_FREE_MB=2000 CARDCHECK_CARDSPEED=/nonexistent
ok "speed without the small block tool" "$(key "$S" state)/$(key "$S" rr_ops)" done/
rm -f "$T/sys/dev/block/179:0/device"
run "$SH" speed CARDCHECK_FREE_MB=2000 CARDCHECK_CARDSPEED=/nonexistent
ok "speed on a USB drive: no bus" "$(key "$S" bus)" ""
ln -s ../../../mmc0:59b4 "$T/sys/dev/block/179:0/device"

# ---- request checks ----
printf 'action=full\npath=/data/evil\n' >"$DIR/request"
env -i PATH="$T/bin:/usr/bin:/bin" CARDCHECK_DIR="$DIR" CARDCHECK_MEDIA="$T/media" "$SH" "$HELPER"
ok "path outside media_rw" "$(key "$DIR/status" error)" bad_request
run "$SH" resume
ok "resume without a paused check" "$(key "$DIR/status" error)" no_progress

if [ "$SH" = bash ]; then
	# ---- a good card: 160 MB free - 64 kept = 96 MB = 6 files ----
	new_card
	run bash full
	ok "pass" "$(key "$RES" state)/$(key "$RES" result)/$(key "$RES" total_mb)" done/pass/96
	ok "pass leaves nothing" "$(ls -A "$ROOT")$(leftovers)" 0
	cmp -s "$DEV" "$T/dev.orig"
	ok "card start untouched" $? 0

	# ---- stop deletes ----
	new_card
	run bash full SYNC_AT=3 SYNC_DO=stop
	ok "stop" "$(key "$RES" state)" stopped
	ok "stop deletes the test data" "$(ls -A "$ROOT")" ""

	# ---- bad data ----
	new_card
	run bash full SYNC_AT=7 SYNC_DO=corrupt
	ok "bad data" "$(key "$RES" result)/$(key "$RES" bad_mb)/$(key "$RES" good_mb)" bad_data/16/32

	# ---- wraps around while writing ----
	new_card
	run bash full SYNC_AT=4 SYNC_DO=wrap
	ok "wraps" "$(key "$RES" result)/$(key "$RES" restored)/$(key "$RES" good_mb)" wraps/yes/32
	cmp -s "$DEV" "$T/dev.orig"
	ok "wraps: card start put back" $? 0
	ok "wraps leaves nothing" "$(ls -A "$ROOT")" ""
fi

# ---- pause while writing, then resume (bash makes the pause, $SH resumes) ----
new_card
run bash full SYNC_AT=3 SYNC_DO=pause
ok "pause" "$(key "$RES" state)/$(key "$RES" phase)/$(key "$RES" written_mb)" paused/write/32
ok "pause keeps the test data" "$(find "$ROOT" -name '*.bin' | wc -l)" 2
ok "pause keeps progress" "$(key "$PROG" phase)/$(key "$PROG" written_mb)" write/32
run "$SH" speed CARDCHECK_FREE_MB=2000
ok "no speed test while paused" "$(key "$DIR/status" error)" paused
ok "speed test left the paused data" "$(find "$ROOT" -name '*.bin' | wc -l)" 2
# games used 32 MB meanwhile: 128 free -> 64 more MB to write, 96 in all
run "$SH" resume CARDCHECK_FREE_MB=128
ok "resume passes" "$(key "$RES" state)/$(key "$RES" result)/$(key "$RES" total_mb)" done/pass/96
ok "resume leaves nothing" "$(ls -A "$ROOT")$(leftovers)" 0

# ---- pause while reading back, resume finds the bad file ----
new_card
run bash full SYNC_AT=8 SYNC_DO=pause
ok "pause in verify" "$(key "$RES" phase)/$(key "$RES" written_mb)" verify/96
f=$(find "$ROOT/.andr36oid-cardcheck" -name 'f005.bin')
printf 'X' | dd of="$f" bs=1 seek=5 conv=notrunc 2>/dev/null
run "$SH" resume
ok "resume in verify" "$(key "$RES" result)/$(key "$RES" bad_mb)/$(key "$RES" good_mb)" bad_data/16/80

# ---- pause, then delete ----
new_card
run bash full SYNC_AT=3 SYNC_DO=pause
run "$SH" delete
ok "delete" "$(key "$RES" state)" stopped
ok "delete removes it all" "$(ls -A "$ROOT")$(leftovers)" 0

# ---- power cut while writing: a half file, no status update ----
new_card
run bash full SYNC_AT=3 SYNC_DO=pause
head -c 1000 /dev/urandom >"$ROOT/.andr36oid-cardcheck/d000/f002.bin"
sed -i 's/^state=.*/state=running/' "$DIR/status"
run "$SH" resume
ok "resume after a power cut" "$(key "$RES" result)/$(key "$RES" total_mb)" pass/128

# ---- the card's start changed while paused: the last file wrapped ----
new_card
run bash full SYNC_AT=3 SYNC_DO=pause
head -c 4096 /dev/zero | dd of="$DEV" conv=notrunc 2>/dev/null
run "$SH" resume
ok "wrap found on resume" "$(key "$RES" result)/$(key "$RES" restored)" wraps/yes
cmp -s "$DEV" "$T/dev.orig"
ok "resume: card start put back" $? 0

# ---- a new check on a plain file: only bash is told it's a disk ----
if [ "$SH" != bash ]; then
	new_card
	run "$SH" full
	ok "not a block device" "$(key "$DIR/status" error)" no_disk
fi

echo "helper [$SH]: $checks checks, $fails failed"
[ "$fails" -eq 0 ]
