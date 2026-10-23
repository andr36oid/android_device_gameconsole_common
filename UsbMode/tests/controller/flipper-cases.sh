# Sourced by the shared helper suite; no separate gadget sequence is used here.
# shellcheck shell=sh
# shellcheck disable=SC2154,SC2317,SC2034
mount() {
    printf 'mount %s\n' "$*" >>"$T/log"
    [ "${fail_mount:-0}" = 0 ] || return 1
    for mount_path do :; done
    printf '8 1 0:1 / %s rw - functionfs flipper rw\n' "$mount_path" >>"$MOUNTS"
}
umount() {
    printf 'umount %s\n' "$*" >>"$T/log"
    [ "${fail_umount:-0}" = 0 ] || return 1
    awk -v path="$1" '$5!=path' "$MOUNTS" >"$T/mounts-new"
    mv "$T/mounts-new" "$MOUNTS"
}
flipper_fixture() {
    controller_fixture none
    FLIPPER_FFS=$T/flipper-ffs
    command mkdir -p "$FLIPPER_FFS"
    setprop "$P.functions" 262144
    setprop "$P.flipper_tick" 1
    setprop "$P.flipper_pid" "$(prop owner)"
    setprop "$P.flipper_start" "$(awk '{print $22}' /proc/$$/stat)"
    setprop "$P.webusb" 0
    setprop init.svc.andr36oid-controller running
    printf '0x0483' >"$STATE/flipper-vid"
    printf '0x5740' >"$STATE/flipper-pid"
    printf '0x0100' >"$STATE/flipper-release"
    printf 'Flipper' >"$STATE/flipper-manufacturer"
    printf 'Flipper Zero' >"$STATE/flipper-product"
    printf 'virtual' >"$STATE/flipper-serial"
    printf 'Guest CDC' >"$STATE/flipper-configuration"
}
for trigger in close eof death pause focus screen load reset timeout helper; do
    flipper_fixture
    check start_drive
    check test -d "$G/functions/ffs.flipper"
    check test -L "$G/configs/b.1/f14"
    check test ! -d "$G/functions/acm.echo"
    check test "$(cat "$G/idVendor")" = 0x0483
    check test "$(cat "$G/strings/0x409/product")" = 'Flipper Zero'
    check test "$(cat "$G/strings/0x409/serialnumber")" = virtual
    check test "$(cat "$G/configs/b.1/strings/0x409/configuration")" = 'Guest CDC'
    check test "$(cat "$G/UDC")" = ff300000.usb
    check test -z "$(sed -n '/prop ctl.start andr36oid-controller/p' "$T/log")"
    case $trigger in
    death) owner_start=invalid ;;
    pause|focus|screen|timeout) setprop "$P.flipper_tick" 0 ;;
    *) setprop "$P.request" stop ;;
    esac
    if lease; then echo "FAIL: Flipper $trigger kept its lease"; exit 1; fi
    check recover
    check_restored
    check test ! -d "$G/functions/ffs.flipper"
    check test ! -L "$G/configs/b.1/f14"
    check test "$(getprop init.svc.andr36oid-controller)" = stopped
    check test "$(prop flipper_mount)" = 0
done
for failure in mount daemon bind; do
    flipper_fixture
    case $failure in
    mount) fail_mount=1 ;;
    daemon) setprop init.svc.andr36oid-controller stopped ;;
    bind) fail_bind=1 ;;
    esac
    if start_drive; then echo "FAIL: Flipper accepted $failure failure"; exit 1; fi
    fail_mount=0 fail_bind=0
    check recover
    check_restored
done
flipper_fixture
setprop "$P.functions" 262656
if validate; then echo 'FAIL: Flipper and echo accepted together'; exit 1; fi
check test ! -e "$STATE/owned"
flipper_fixture
setprop "$P.flipper_pid" 1
if validate; then echo 'FAIL: wrong Flipper owner accepted'; exit 1; fi
check test ! -e "$STATE/owned"
