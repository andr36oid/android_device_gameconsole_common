#!/bin/bash
# Run the real helpers in a fake Android root, through their normal volume lookups.
# Called by each component's tests; no testing overrides bypass the lookups here.
set -e
HERE=$(cd "$(dirname "$0")/../.." && pwd)
if ! command -v bwrap >/dev/null; then
	echo "skip EASYROMS helper lookups: no bwrap"
	exit 0
fi
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
R=$T/root
mkdir -p "$R/system/bin" "$R/proc" "$R/sys/class/block/mmcblk1p7" \
	"$R/sys/block/mmcblk0/mmcblk0p7" "$R/data/media/0" "$R/props" \
	"$R/mnt/media_rw/LEGACY" "$R/mnt/media_rw/SELECTED" "$R/tmp"

stub() {
	printf '#!/bin/sh\n%s\n' "$2" >"$R/system/bin/$1"
	chmod +x "$R/system/bin/$1"
}
# shellcheck disable=SC2016 # the stub reads its own argument
stub getprop 'cat "/props/$1" 2>/dev/null; exit 0'
stub sm '[ "$*" = "list-volumes public" ] || exit 1; cat /volumes'
stub dmesg 'cat /dmesg'
stub logcat 'exit 0'
stub pm 'exit 0'
stub app_process 'printf "%s\n" "$@" >/args; echo "done list" >/data/misc/andr36oid-savebackup/status'
for cmd in chown restorecon log sync sleep; do stub "$cmd" 'exit 0'; done

# Use Android's toybox applets for the file work, leaving services as stubs.
TOYBOX=${TOYBOX:-/andr36oid/src/prebuilts/build-tools/linux-x86/bin/toybox}
if [ -x "$TOYBOX" ]; then
	mkdir -p "$R/toy/bin" "$R/toy/lib64"
	cp "$TOYBOX" "$R/toy/bin/toybox"
	cp "$(dirname "$TOYBOX")"/../lib64/libcrypto-host.so "$(dirname "$TOYBOX")"/../lib64/libz-host.so \
		"$(dirname "$TOYBOX")"/../lib64/libc++.so "$R/toy/lib64/"
	for cmd in cat chmod cp cut find grep head ls md5sum mkdir mv rm sed stat tail tr wc; do
		ln -s /toy/bin/toybox "$R/system/bin/$cmd"
	done
fi

cat >"$R/proc/mounts" <<'EOF'
/dev/block/vold/public:179,7 /mnt/media_rw/LEGACY vfat ro 0 0
/dev/block/vold/public:179,103 /mnt/media_rw/SELECTED exfat rw 0 0
EOF
cat >"$R/dmesg" <<'EOF'
exFAT-fs (mmcblk0p7): error, other card
exFAT-fs (mmcblk1p7): error, selected card
exFAT-fs (mmcblk1p7): error, selected card again
EOF
for card in LEGACY SELECTED; do
	mkdir -p "$R/mnt/media_rw/$card/touchmap" "$R/mnt/media_rw/$card/apk"
	echo '{}' >"$R/mnt/media_rw/$card/touchmap/$card.json"
	echo apk >"$R/mnt/media_rw/$card/apk/$card.apk"
done

check() {
	if ! "$@"; then
		echo "FAIL $component ($sh, $mode): $*"
		cat "$T/output"
		exit 1
	fi
}

run() {
	local lib64=()
	[ ! -e /lib64 ] || lib64=(--ro-bind /lib64 /lib64)
	bwrap --bind "$R" / --ro-bind /usr /usr --ro-bind /bin /bin --ro-bind /lib /lib "${lib64[@]}" \
		--ro-bind /etc /etc --dev /dev \
		--setenv PATH /system/bin:/usr/bin:/bin \
		--setenv LD_LIBRARY_PATH /toy/lib64 \
		--unsetenv SAVEBACKUP_RUN --unsetenv STORAGECARE_RUN --unsetenv BIOSCHECK_PART \
		--unsetenv BIOSCHECK_EASYROMS --unsetenv TOUCHMAP_TEST --unsetenv APKINSTALL_DIR \
		$sh /system/helper.sh >"$T/output" 2>&1
}

[ "$#" != 0 ] || set -- SaveBackup StorageCare BiosCheck TouchMapper ApkInstall
for component in "$@"; do
	case $component in
	SaveBackup) name=savebackup ;;
	StorageCare) name=storagecare ;;
	BiosCheck) name=bioscheck ;;
	TouchMapper) name=touchmap; helper=andr36oid-touchmap-share.sh ;;
	ApkInstall) name=apkinstall ;;
	*) echo "unknown component $component"; exit 1 ;;
	esac
	if [ "$component" != TouchMapper ]; then helper=andr36oid-$name.sh; fi
	cp "$HERE/$component/helper/$helper" "$R/system/helper.sh"
	DIR=$R/data/misc/andr36oid-$name
	mkdir -p "$DIR"
	for sh in /bin/dash /bin/bash /bin/mksh; do
		[ -x "${sh%% *}" ] || continue
		for mode in legacy selected absent waiting; do
			rm -f "$R/props/"*
			cat >"$R/volumes" <<'EOF'
public:179,7 mounted LEGACY
public:179,103 mounted SELECTED
EOF
			card=LEGACY
			if [ "$mode" != legacy ]; then
				echo 1 >"$R/props/sys.andr36oid.easyroms.ready"
				card=SELECTED
				if [ "$mode" != absent ]; then
					echo public:179,103 >"$R/props/sys.andr36oid.easyroms.id"
					echo mmcblk1p7 >"$R/props/sys.andr36oid.easyroms.dev"
					echo /sys/class/block/mmcblk1p7 >"$R/props/sys.andr36oid.easyroms.part"
				else card=; fi
			fi
			if [ "$mode" = waiting ]; then
				echo 'public:179,7 mounted LEGACY' >"$R/volumes"
				# SaveBackup reads mounts rather than sm
				head -n 1 "$R/proc/mounts" >"$R/proc/mounts.tmp"
				mv "$R/proc/mounts.tmp" "$R/proc/mounts"
				card=
			fi
			case $component in
			SaveBackup)
				echo action=list >"$DIR/request"
				run
				if [ -n "$card" ]; then
					check test "$(sed -n '/^--easyroms$/{n;p;}' "$R/args")" = "/mnt/media_rw/$card"
				else check test "$(grep -c -x -- --easyroms "$R/args" || true)" = 0; fi
				;;
			StorageCare)
				echo action=health >"$DIR/request"
				run
				case $card in
				LEGACY) errors=1; fs=vfat ;;
				SELECTED) errors=2; fs=exfat ;;
				*) errors=0; fs= ;;
				esac
				# Errors still describe the selected partition while vold checks it
				[ "$mode" != waiting ] || errors=2
				check grep -qx "fs_errors=$errors" "$DIR/health"
				check grep -qx "easyroms_fs=$fs" "$DIR/health"
				;;
			BiosCheck)
				echo action=scan >"$DIR/request"
				run
				if [ -n "$card" ]; then
					check grep -qx $'easyroms\t'"/mnt/media_rw/$card" "$DIR/scan"
				elif [ "$mode" = absent ]; then
					check grep -qx $'internal\t/data/media/0' "$DIR/scan"
				else check test "$(grep -c -E '^(easyroms|internal|bios)' "$DIR/scan" || true)" = 0; fi
				;;
			TouchMapper)
				echo import >"$DIR/request"
				run
				if [ -n "$card" ]; then
					check test "$(ls "$DIR/in")" = "$card.json"
				else check grep -qx 'error nocard' "$DIR/status"; fi
				;;
			ApkInstall)
				echo action=scan >"$DIR/request"
				run
				if [ -n "$card" ]; then
					check grep -q "$card.apk$" "$DIR/list"
					check test "$(wc -l <"$DIR/list")" = 1
				else check grep -qx 'error noeasyroms' "$DIR/status"; fi
				;;
			esac
			if [ "$mode" = waiting ]; then
				echo '/dev/block/vold/public:179,103 /mnt/media_rw/SELECTED exfat rw 0 0' >>"$R/proc/mounts"
			fi
		done
	done
	echo "EASYROMS lookups PASSED ($component)"
done
