#!/bin/bash
# Host test of helper/andr36oid-report-check.sh on fake pstore/dropbox/state dirs, with
# fake cmd and log that only note their calls. Runs it under every shell found.
#   ProblemReport/tests/report-check-test.sh
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
SCRIPT=$HERE/helper/andr36oid-report-check.sh
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fails=0 runs=0

setup() {
	rm -rf "$T/w"
	mkdir -p "$T/w/pstore" "$T/w/dropbox" "$T/w/dir" "$T/w/prefs"
	printf '#!/bin/sh\necho "$*" >>"%s"\n' "$T/w/cmd.log" >"$T/w/cmd"
	printf '#!/bin/sh\n:\n' >"$T/w/log"
	chmod +x "$T/w/cmd" "$T/w/log"
	echo "100.25 80.00" >"$T/w/uptime"
	: >"$T/w/cmd.log"
}

run() {
	PSTORE=$T/w/pstore DROPBOX=$T/w/dropbox DIR=$T/w/dir APP_PREFS=$T/w/prefs/boot.xml \
		UPTIME=$T/w/uptime CMD=$T/w/cmd LOG=$T/w/log $SH "$SCRIPT"
}

# expect <what> <broadcasts wanted in total>
expect() {
	local n
	n=$(grep -c CrashNoticeReceiver "$T/w/cmd.log")
	runs=$((runs + 1))
	if [ "$n" != "$2" ]; then
		echo "FAIL [$SH] $1: $n broadcasts, wanted $2"
		fails=$((fails + 1))
	fi
}

for SH in dash bash mksh "busybox sh"; do
	command -v "${SH%% *}" >/dev/null || continue
	now=$(date +%s)

	setup
	touch "$T/w/dropbox/system_server_crash@$(((now - 1000) * 1000)).txt"
	run; expect "first start, only an old crash" 0
	[ -f "$T/w/dir/boot-check" ] || { echo "FAIL [$SH] no state file"; fails=$((fails + 1)); }

	setup
	touch "$T/w/dropbox/system_server_crash@$(((now - 50) * 1000)).txt.gz"
	run; expect "system_server crash in this kernel start" 1
	run; expect "same crash again" 1
	# as if the last look was 40 s ago
	sed -i "s/^dropbox .*/dropbox $((now - 40))/" "$T/w/dir/boot-check"
	touch "$T/w/dropbox/system_server_watchdog@$(((now - 10) * 1000)).txt"
	run; expect "a watchdog restart after the last look" 2
	sed -i "s/^dropbox .*/dropbox $((now - 40))/" "$T/w/dir/boot-check"
	touch "$T/w/dropbox/system_app_crash@$(((now - 5) * 1000)).txt"
	rm "$T/w/dropbox/system_server_watchdog@"*
	run; expect "app crash doesn't count" 2

	setup
	echo "Oops: panic" >"$T/w/pstore/dmesg-ramoops-0"
	echo "boot log" >"$T/w/pstore/console-ramoops-0"
	run; expect "new kernel crash record" 1
	run; expect "same kernel crash record" 1
	echo "Oops: another one" >"$T/w/pstore/dmesg-ramoops-0"
	run; expect "different kernel crash record" 2

	setup
	echo "boot log" >"$T/w/pstore/console-ramoops-0"
	run; expect "only a normal boot log" 0

	# Taking over from the app's own preferences
	setup
	echo "Oops: seen by the app" >"$T/w/pstore/dmesg-ramoops-0"
	fp=$(head -c 4096 "$T/w/pstore/dmesg-ramoops-0" | sha1sum | cut -d ' ' -f 1)
	touch "$T/w/dropbox/system_server_crash@$(((now - 50) * 1000)).txt"
	cat >"$T/w/prefs/boot.xml" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="pstore_record">$fp</string>
    <long name="dropbox_time" value="$(((now - 20) * 1000))" />
</map>
XML
	run; expect "app had seen both already" 0
	grep -q "^pstore $fp\$" "$T/w/dir/boot-check" || { echo "FAIL [$SH] pstore not taken over"; fails=$((fails + 1)); }

	setup
	printf 'Restart#1 Part1\n<6>[ 1.0] a normal restart\n' >"$T/w/pstore/dmesg-ramoops-0"
	run; expect "the log of a normal restart is no crash" 0
	printf 'Panic#2 Part1\n<0>[ 2.0] Kernel panic\n' >"$T/w/pstore/dmesg-ramoops-0"
	run; expect "a panic after it is" 1

	setup
	cat >"$T/w/prefs/boot.xml" <<XML
<map>
    <long name="dropbox_time" value="$(((now - 60) * 1000))" />
</map>
XML
	touch "$T/w/dropbox/system_server_native_crash@$(((now - 30) * 1000)).txt"
	run; expect "native crash after the app's last look" 1
done

echo "$runs checks, $fails failed"
[ "$fails" = 0 ]
