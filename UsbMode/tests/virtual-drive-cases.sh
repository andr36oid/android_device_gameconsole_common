#!/bin/sh
# shellcheck disable=SC2034,SC2154
# Sourced by the helper fixture, after the existing image cases.
command mkdir -p "$T/vbin"
export VIRTUAL_TEST_ROOT="$T"
for tool in df fsync losetup truncate fallocate newfs_msdos mkfs.exfat mkfs.ntfs mke2fs fsck_msdos fsck.exfat fsck.ntfs e2fsck; do
	ln -sf "$HERE/tests/virtual-drive-tools.py" "$T/vbin/$tool"
done
PATH=$T/vbin:$PATH
export PATH
virtual_fixture() {
	# Fixture setup uses the host truncate only to make a sparse regular test file.
	image_fixture
	setprop "$P.owner" "$$"
	setprop "$P.request" create
	setprop "$P.vname" 'My drive'
	setprop "$P.vsize" 1
	setprop "$P.vfs" fat32
	setprop "$P.vlabel" 'USB DRIVE'
	: >"$T/tools.log"
	rm -f "$T/loop"
}
for name in 'Disk 1' A a-b_c.d; do check virtual_name "$name"; done
for name in '' /tmp/a ../a a/../b 'a..b' .hidden 'a;' 'a*' 'a\b' 'a ' 'a.' "$(printf 'a\nb')"; do
	if virtual_name "$name"; then echo "FAIL: virtual name $name"; exit 1; fi
done
check virtual_name "$(printf '%087d' 1)"
if virtual_name "$(printf '%088d' 1)"; then exit 1; fi
for gb in 1 4 8 16 32 64 1024; do check virtual_size "$gb"; done
for gb in 0 01 -1 1.5 1025 999999999999 a '1;id'; do
	if virtual_size "$gb"; then echo "FAIL: virtual size $gb"; exit 1; fi
done
for fs in fat32 exfat ntfs ext4; do
	check virtual_label '' "$fs"
	check virtual_label 'MY DRIVE-1' "$fs"
	if virtual_label 'bad/label' "$fs"; then exit 1; fi
	virtual_fixture
	setprop "$P.vfs" "$fs"
	check virtual_request
	check test "$(prop vresult)" = 'done'
	check test ! -d "$STATE/virtual"
	check test ! -e "$T/loop"
	image=$MEDIA/UUID/isos/My\ drive.img
	check test -f "$image"
	check test "$(virtual_probe "$image" 1073741824)" = "$fs"
	check test -n "$(sed -n '/My drive.img/p' "$STATE/images")"
	check python3 "$HERE/tests/virtual-drive-mbr.py" "$image" "$fs" 1073741824
	case $fs in fat32) tool=newfs_msdos ;; exfat) tool=mkfs.exfat ;; ntfs) tool=mkfs.ntfs ;; ext4) tool=mke2fs ;; esac
	check test -n "$(sed -n "/^$tool /p" "$T/tools.log")"
	check test -z "$(sed -n '/^fallocate /p' "$T/tools.log")"
	check test -n "$(sed -n '/^fsync .*My drive.img.creating/p' "$T/tools.log")"
	setprop "$P.image" 'My drive.img'
	setprop "$P.stamp" "$(image_info 'My drive.img')"
	setprop "$P.request" repair
	check virtual_request
	check test "$(prop vresult)" = 'done'
	check test -f "$image"
	check test ! -e "$T/loop"
	setprop "$P.request" delete
	check virtual_request
	check test "$(prop vresult)" = 'done'
	check test ! -e "$image"
done
# Fallocate is used only on a filesystem with fast unwritten extents.
virtual_fixture
sed 's/ - exfat / - ext4 /' "$MOUNTS" >"$T/newmounts"
mv "$T/newmounts" "$MOUNTS"
check virtual_request
check test "$(prop vresult)" = 'done'
check test -n "$(sed -n '/^fallocate /p' "$T/tools.log")"
# Large images keep exact sector counts beyond mksh's signed 32-bit range.
virtual_fixture
sed 's/ - exfat / - ext4 /' "$MOUNTS" >"$T/newmounts"
mv "$T/newmounts" "$MOUNTS"
setprop "$P.vsize" 16
setprop "$P.vfs" exfat
check virtual_request
check test "$(prop vresult)" = 'done'
check test "$(stat -c %s "$MEDIA/UUID/isos/My drive.img")" = 17179869184
check python3 "$HERE/tests/virtual-drive-mbr.py" "$MEDIA/UUID/isos/My drive.img" exfat 17179869184
# Formatting failure and cancellation never publish a partial image.
for behavior in failure cancel; do
	virtual_fixture
	if [ "$behavior" = failure ]; then export VIRTUAL_FAIL_TOOL=mkfs.exfat
	else export VIRTUAL_CANCEL_TOOL=truncate; fi
	setprop "$P.vfs" exfat
	check virtual_request
	if [ "$behavior" = failure ]; then check test "$(prop vresult)" = error
	else check test "$(prop vresult)" = cancelled; fi
	check test ! -e "$MEDIA/UUID/isos/My drive.img"
	check test ! -e "$MEDIA/UUID/isos/.My drive.img.creating"
	check test ! -d "$STATE/virtual"
	check test ! -e "$T/loop"
	unset VIRTUAL_FAIL_TOOL VIRTUAL_CANCEL_TOOL
done
# Cancelling a formatter also detaches its loop before removing the partial.
virtual_fixture
export VIRTUAL_CANCEL_TOOL=newfs_msdos
check virtual_request
check test "$(prop vresult)" = cancelled
check test ! -d "$STATE/virtual"
check test ! -e "$T/loop"
check test ! -e "$MEDIA/UUID/isos/My drive.img"

# Init bounds crash recovery to one attempt, like the existing USB owner.
check awk '/^on property:init.svc.andr36oid-usb-drive=stopped.*vactive=1$/ {trigger=1; next}
    trigger && /setprop sys.andr36oid.usbdrive.vactive 0/ {cleared=1}
    trigger && /setprop sys.andr36oid.usbdrive.request stop/ {stopped=1}
    trigger && /start andr36oid-usb-drive/ {started=1}
    trigger && /^on / {trigger=0}
    END {exit !(cleared && stopped && started)}' "$HERE/../init.rk30board.rc"
check test "$(prop vactive)" = 0
unset VIRTUAL_CANCEL_TOOL
# A failed detach retains both the loop and journal until a safe retry.
virtual_fixture
export VIRTUAL_FAIL_DETACH=1
check virtual_request
check test "$(prop vresult)" = error
check test -d "$STATE/virtual"
check test -e "$T/loop"
check test ! -e "$MEDIA/UUID/isos/My drive.img"
unset VIRTUAL_FAIL_DETACH
check virtual_recover
check test ! -d "$STATE/virtual"
check test ! -e "$T/loop"
# Recovery after a formatter crash detaches the recorded loop and deletes only
# the matching incomplete file. A published image is kept after a late crash.
virtual_fixture
image_root
v_name=Recovered.img v_request=create
v_path=$easy_root/isos/.$v_name.creating
check virtual_journal
/usr/bin/truncate -s 1073741824 "$v_path"
stat -c '%d:%i' "$v_path" >"$STATE/virtual/inode"
printf '%s\n' /dev/block/loop7 >"$STATE/virtual/loop"
printf '%s\n' "$v_path" >"$T/loop"
check virtual_recover
check test ! -e "$v_path"
check test ! -e "$T/loop"
check test ! -d "$STATE/virtual"
check virtual_journal
: >"$v_path"
check virtual_recover
check test ! -e "$v_path"
check virtual_journal
: >"$easy_root/isos/$v_name"
check virtual_recover
check test -e "$easy_root/isos/$v_name"
# Across a reboot, exFAT inode numbers may change. Only the durable token in
# the partition gap grants ownership of a nonempty partial file.
check virtual_journal
dd if="$STATE/virtual/token" of="$v_path" bs=512 seek=1 count=1 2>/dev/null
printf 'previous-boot\n' >"$STATE/virtual/boot"
printf '0:0\n' >"$STATE/virtual/inode"
check virtual_recover
check test ! -e "$v_path"
check virtual_journal
printf 'unrelated\n' >"$v_path"
printf 'previous-boot\n' >"$STATE/virtual/boot"
if virtual_recover; then echo 'FAIL: unowned partial deleted after reboot'; exit 1; fi
check test -f "$v_path"
rm "$v_path"
check virtual_recover
# A replacement inode or symlink is never cleaned as an interrupted creation.
check virtual_journal
printf 'original\n' >"$v_path"
stat -c '%d:%i' "$v_path" >"$STATE/virtual/inode"
mv "$v_path" "$v_path.saved"
printf 'other\n' >"$v_path"
if virtual_recover; then echo 'FAIL: replaced partial deleted'; exit 1; fi
check test -f "$v_path"
rm "$v_path"
ln -s "$v_path.saved" "$v_path"
if virtual_recover; then echo 'FAIL: partial symlink followed'; exit 1; fi
check test -f "$v_path.saved"
# A live child from the same boot is stopped; a stale boot record is not used.
virtual_fixture
image_root
v_name=Child.img v_request=create
v_path=$easy_root/isos/.$v_name.creating
check virtual_journal
: >"$v_path"
/bin/sleep 30 &
child_pid=$!
printf '%s %s\n' "$child_pid" "$(awk '{print $22}' "/proc/$child_pid/stat")" >"$STATE/virtual/child"
check virtual_recover
wait "$child_pid" 2>/dev/null || true
check test ! -e "$v_path"
check virtual_journal
: >"$v_path"
/bin/sleep 30 &
child_pid=$!
printf '%s %s\n' "$child_pid" "$(awk '{print $22}' "/proc/$child_pid/stat")" >"$STATE/virtual/child"
printf 'previous-boot\n' >"$STATE/virtual/boot"
check virtual_recover
check kill -0 "$child_pid"
kill "$child_pid"
wait "$child_pid" 2>/dev/null || true
# Mount, space, FAT32 file limit, name clash and shared-image guards.
for reason in space fat_limit exists unavailable shared changed layout name size label; do
	virtual_fixture
	case $reason in
	space) export VIRTUAL_FREE_KB=1048576 ;;
	fat_limit) sed 's/ - exfat / - vfat /' "$MOUNTS" >"$T/newmounts"; mv "$T/newmounts" "$MOUNTS"; setprop "$P.vsize" 4 ;;
	exists) : >"$MEDIA/UUID/isos/My drive.img" ;;
	unavailable) setprop sys.andr36oid.easyroms.ready 0 ;;
	shared) touch "$STATE/owned" ;;
	changed) setprop "$P.request" delete; setprop "$P.stamp" 0:0:1 ;;
	layout) setprop "$P.request" repair ;;
	name) setprop "$P.vname" '../trick' ;;
	size) setprop "$P.vsize" 0 ;;
	label) setprop "$P.vlabel" 'bad/label' ;;
	esac
	check virtual_request
	check test "$(prop vresult)" = error
	check test "$(prop verror)" = "$reason"
	check test -z "$(sed -n "/^losetup .*'-o'/p" "$T/tools.log")"
	unset VIRTUAL_FREE_KB
done
# Repair and delete do not take ownership of a loop opened by another app.
virtual_fixture
printf '%s\n' "$MEDIA/UUID/isos/Alpine Linux.iso" >"$T/loop"
setprop "$P.request" delete
check virtual_request
check test "$(prop verror)" = shared
check test -f "$MEDIA/UUID/isos/Alpine Linux.iso"
check test -e "$T/loop"
virtual_fixture
ln "$MEDIA/UUID/isos/Alpine Linux.iso" "$MEDIA/UUID/alias"
setprop "$P.request" delete
check virtual_request
check test "$(prop verror)" = changed
check test -f "$MEDIA/UUID/alias"
# A read-only card and a redirected isos directory are never modified.
virtual_fixture
printf 'public:179,7 mounted_ro UUID\n' >"$T/volumes"
check virtual_request
check test "$(prop verror)" = unavailable
virtual_fixture
mv "$MEDIA/UUID/isos" "$MEDIA/UUID/elsewhere"
ln -s "$MEDIA/UUID/elsewhere" "$MEDIA/UUID/isos"
check virtual_request
check test "$(prop vresult)" = error
check test ! -e "$MEDIA/UUID/elsewhere/My drive.img"
# A retained journal blocks USB sharing until its original card is available.
virtual_fixture
image_root
v_request=create v_name=Waiting.img
check virtual_journal
printf 'ffffffffffffffffffffffffffffffff\n' >"$STATE/virtual/cid"
setprop "$P.request" start
check handle_request
check test "$(prop error)" = virtual_recovery
check test -d "$STATE/virtual"
check test ! -d "$F"
# Restart recovery cancels the old operation rather than recreating it.
virtual_fixture
setprop "$P.state" working
(
	# shellcheck disable=SC2317
	sleep() { [ "$(prop request)" = stop ] || exit 1; exit 0; }
	main
)
check test "$(prop vresult)" = cancelled
check test ! -e "$MEDIA/UUID/isos/My drive.img"
