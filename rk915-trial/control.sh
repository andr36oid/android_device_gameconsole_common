#!/system/bin/sh
# Root-only manual controls. No insmod, rmmod, mount or Wi-Fi setting changes.
set -eu
PATH=/system/bin:/vendor/bin
export PATH
umask 077
BASE=/data/misc/andr36oid-rk915-trial
STORE=/vendor/etc/andr36oid-rk915-trial
FSYNC=/system/bin/andr36oid-fsync
case "${1:-status}" in
arm)
    [ "$(id -u)" = 0 ] || { echo 'root required' >&2; exit 1; }
    [ "$(getenforce)" = Permissive ] || exit 1
    [ -x "$FSYNC" ] || exit 1
    [ "$(uname -r)" = 5.10.252-rk3326-itskenny0 ] || exit 1
    [ "$(tr -d '\000' < /proc/device-tree/wireless-wlan/wifi_chip_type)" = rk915 ] || exit 1
    [ "$(cat /sys/kernel/andr36oid/flavor)" = gcc ] || exit 1
    case "$(cat /sys/kernel/andr36oid/variant)" in g2) gpu=g2 ;; g7p1) gpu=g7 ;; *) exit 1 ;; esac
    build=$(cat /sys/kernel/andr36oid/build)
    case "$build" in '#30 '*) parent=30 ;; '#31 '*) parent=31 ;; *) exit 1 ;; esac
    [ ! -e /sys/module/rk915/parameters/pm_checked_resume ] || { echo 'Already in trial; reboot to stock before rearming' >&2; exit 1; }
    KEY=k${parent}-${gpu}
    conf=$STORE/$KEY/manifest.conf
    expect=$(sed -n 's/^trial_sha256=//p' "$conf")
    [ -n "$expect" ] && [ "$(sha256sum "$STORE/$KEY/rk915.ko" | cut -d' ' -f1)" = "$expect" ] || exit 1
    [ "$(sed -n 's/^build=//p' "$conf")" = "$build" ] || exit 1
    [ "$(zcat /proc/config.gz | sha256sum | cut -d' ' -f1)" = "$(sed -n 's/^config_sha256=//p' "$conf")" ] || exit 1
    [ "$(sha256sum /vendor/lib/modules/rk915.ko | cut -d' ' -f1)" = "$(sed -n 's/^stock_sha256=//p' "$conf")" ] || exit 1
    [ ! -L "$BASE" ] || exit 1
    mkdir -p "$BASE"
    [ "$(stat -c '%u:%g:%a' "$BASE")" = 0:0:700 ] || exit 1
    [ ! -e "$BASE/armed" ] && [ ! -L "$BASE/armed.tmp" ] || { echo 'Already armed or unsafe temp path' >&2; exit 1; }
    printf '%s %s %s\n' "$(cat /proc/sys/kernel/random/boot_id)" "$KEY" "$expect" > "$BASE/armed.tmp"
    chmod 600 "$BASE/armed.tmp"
    "$FSYNC" "$BASE/armed.tmp" || exit 1
    mv "$BASE/armed.tmp" "$BASE/armed"
    if ! "$FSYNC" "$BASE/armed" "$BASE"; then
        rm -f "$BASE/armed"
        "$FSYNC" "$BASE" 2>/dev/null || :
        echo 'Arm flush failed; no candidate was loaded' >&2
        exit 1
    fi
    echo "ARMED ONE NEXT BOOT: $KEY $expect; reboot only with owner present"
    ;;
disarm)
    [ "$(id -u)" = 0 ] && [ ! -L "$BASE" ] || exit 1
    if [ -d "$BASE" ]; then
        [ "$(stat -c '%u:%g:%a' "$BASE")" = 0:0:700 ] || exit 1
        rm -f "$BASE/armed" "$BASE/armed.tmp"
        "$FSYNC" "$BASE" || exit 1
    fi
    echo 'Next boot is stock. A live trial/watchdog stays protected until reboot.'
    ;;
status)
    for p in /sys/kernel/andr36oid/variant /sys/kernel/andr36oid/build /sys/kernel/andr36oid/flavor /sys/module/rk915/version /sys/module/rk915/parameters/pm_checked_resume /sys/module/rk915/parameters/pm_wake_ack_timeout_ms; do
        printf '%s: ' "$p"; cat "$p" 2>/dev/null || echo absent
    done
    printf 'watchdog: '; getprop init.svc.andr36oid-rk915-trial-watchdog
    printf 'module view SHA256: '; sha256sum /vendor/lib/modules/rk915.ko
    for p in armed consumed watchdog.ready; do
        if [ -f "$BASE/$p" ]; then printf '%s: ' "$p"; cat "$BASE/$p"; fi
    done
    ;;
*) echo 'usage: control.sh arm|disarm|status' >&2; exit 2 ;;
esac
