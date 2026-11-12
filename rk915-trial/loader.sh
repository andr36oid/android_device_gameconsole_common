#!/system/bin/sh
# Armed-only, synchronous init hook. Never run to replace a live Wi-Fi module.
set -eu
PATH=/system/bin:/vendor/bin
export PATH
umask 077
BASE=/data/misc/andr36oid-rk915-trial
STORE=/vendor/etc/andr36oid-rk915-trial
MODULE=/vendor/lib/modules/rk915.ko
FSYNC=/system/bin/andr36oid-fsync
WATCH=/system/bin/andr36oid-rk915-trial-watchdog
STARTED=0
RISKY=0
LIVE=0
log() { printf '%s\n' "$*" >> "$BASE/loader.log"; "$FSYNC" "$BASE/loader.log" 2>/dev/null || :; }
refuse() { log "REFUSED: $*"; exit 1; }
finish() {
    rc=$?
    trap - EXIT
    if [ "$LIVE" = 0 ] && [ "$RISKY" = 1 ]; then
        log 'candidate path failed; restoring stock view and requesting stock reboot'
        umount "$MODULE" 2>/dev/null || :
        setprop sys.powerctl reboot,rk915-trial-fallback || :
    elif [ "$LIVE" = 0 ] && [ "$STARTED" = 1 ]; then
        setprop sys.andr36oid.rk915_trial.active 0 || :
        : > "$BASE/abort"
    fi
    exit "$rc"
}
trap 'finish' EXIT
[ -e "$BASE/armed" ] || exit 0
[ ! -L "$BASE" ] && [ -d "$BASE" ] || exit 1
[ "$(stat -c '%u:%g:%a' "$BASE")" = 0:0:700 ] || exit 1
[ -f "$BASE/armed" ] && [ ! -L "$BASE/armed" ] || exit 1
[ "$(stat -c '%u:%g:%a' "$BASE/armed")" = 0:0:600 ] || exit 1
[ -x "$FSYNC" ] || exit 1
[ "$(getenforce)" = Permissive ] || refuse 'trial requires the approved permissive test image'
[ "$(uname -r)" = 5.10.252-rk3326-itskenny0 ] || refuse 'kernel release differs'
[ "$(cat /sys/kernel/andr36oid/flavor)" = gcc ] || refuse 'requires GCC'
variant=$(cat /sys/kernel/andr36oid/variant)
case "$variant" in g2) gpu=g2 ;; g7p1) gpu=g7 ;; *) refuse 'GPU variant differs' ;; esac
build=$(cat /sys/kernel/andr36oid/build)
case "$build" in '#30 '*) parent=30 ;; '#31 '*) parent=31 ;; *) refuse 'kernel build is not qualified' ;; esac
KEY=k${parent}-${gpu}
CONF=$STORE/$KEY/manifest.conf
[ -f "$CONF" ] && [ ! -L "$CONF" ] || refuse 'missing immutable manifest'
# Parse data, never source it as executable shell code.
EXPECT_BUILD= EXPECT_CONFIG= EXPECT_STOCK= EXPECT_TRIAL= EXPECT_WATCH= EXPECT_VERSION= EXPECT_RF=
while IFS='=' read -r k v; do
    case "$k" in
    build) [ -z "$EXPECT_BUILD" ] || refuse 'duplicate manifest field'; EXPECT_BUILD=$v ;;
    config_sha256) [ -z "$EXPECT_CONFIG" ] || refuse 'duplicate manifest field'; EXPECT_CONFIG=$v ;;
    stock_sha256) [ -z "$EXPECT_STOCK" ] || refuse 'duplicate manifest field'; EXPECT_STOCK=$v ;;
    trial_sha256) [ -z "$EXPECT_TRIAL" ] || refuse 'duplicate manifest field'; EXPECT_TRIAL=$v ;;
    watchdog_sha256) [ -z "$EXPECT_WATCH" ] || refuse 'duplicate manifest field'; EXPECT_WATCH=$v ;;
    trial_version) [ -z "$EXPECT_VERSION" ] || refuse 'duplicate manifest field'; EXPECT_VERSION=$v ;;
    rfkill) [ -z "$EXPECT_RF" ] || refuse 'duplicate manifest field'; EXPECT_RF=$v ;;
    *) refuse 'unknown manifest field' ;;
    esac
done < "$CONF"
for h in "$EXPECT_CONFIG" "$EXPECT_STOCK" "$EXPECT_TRIAL" "$EXPECT_WATCH"; do
    case "$h" in *[!0-9a-f]*|'') refuse 'invalid manifest hash' ;; esac
    [ "${#h}" = 64 ] || refuse 'invalid manifest hash length'
done
[ -n "$EXPECT_BUILD" ] && [ -n "$EXPECT_VERSION" ] || refuse 'incomplete manifest'
[ "$EXPECT_RF" = builtin ] || refuse 'rfkill prerequisite not qualified'
[ "$build" = "$EXPECT_BUILD" ] || refuse 'kernel build identity differs'
[ "$(zcat /proc/config.gz | sha256sum | cut -d' ' -f1)" = "$EXPECT_CONFIG" ] || refuse 'config differs'
[ "$(cat /sys/module/module/parameters/no_radios)" = N ] || refuse 'module.no_radios is enabled'
[ "$(tr -d '\000' < /proc/device-tree/wireless-wlan/wifi_chip_type)" = rk915 ] || refuse 'not the onboard RK915 board path'
[ ! -d /sys/module/rk915 ] || refuse 'rk915 already live; never unload sole Wi-Fi'
[ "$(sha256sum "$MODULE" | cut -d' ' -f1)" = "$EXPECT_STOCK" ] || refuse 'stock module differs (including hotfix overrides)'
TRIAL=$STORE/$KEY/rk915.ko
[ "$(sha256sum "$TRIAL" | cut -d' ' -f1)" = "$EXPECT_TRIAL" ] || refuse 'trial module differs'
[ "$(sha256sum "$WATCH" | cut -d' ' -f1)" = "$EXPECT_WATCH" ] || refuse 'watchdog differs'
boot=$(cat /proc/sys/kernel/random/boot_id)
IFS=' ' read -r requested requested_key requested_hash extra < "$BASE/armed" || refuse 'incomplete arm token'
[ -z "$extra" ] && [ "$requested_key" = "$KEY" ] && [ "$requested_hash" = "$EXPECT_TRIAL" ] || refuse 'arm token differs'
case "$requested" in *[!0-9a-f-]*|'') refuse 'invalid requester boot ID' ;; esac
[ "${#requested}" = 36 ] && [ "$requested" != "$boot" ] || refuse 'request is for next boot, not this boot'
[ "$(wc -l < "$BASE/armed" | tr -d ' ')" = 1 ] || refuse 'multiline arm token'
# The one-shot marker must be durable BEFORE the candidate can affect the chip.
mv "$BASE/armed" "$BASE/consumed" || refuse 'arm rename failed'
"$FSYNC" "$BASE/consumed" "$BASE" || refuse 'consumption flush failed'
rm -f "$BASE/abort" "$BASE/watchdog.ready"
log "consumed $KEY; next boot uses stock; starting init-owned watchdog"
printf '%s %s %s\n' "$boot" "$KEY" "$EXPECT_TRIAL" > "$BASE/watchdog.request"
"$FSYNC" "$BASE/watchdog.request" || refuse 'watchdog request flush failed'
# ctl.start is processed by init even while this synchronous exec is running.
# An on-property action would be queued behind us and would deadlock this wait.
STARTED=1
setprop ctl.start andr36oid-rk915-trial-watchdog || refuse 'cannot start init watchdog'
n=0
ready=
while [ "$n" -lt 50 ]; do
    if [ -f "$BASE/watchdog.ready" ]; then
        ready=$(cat "$BASE/watchdog.ready")
        [ "$ready" = "$boot $KEY $EXPECT_TRIAL" ] && break
    fi
    sleep 0.1
    n=$((n+1))
done
[ "$ready" = "$boot $KEY $EXPECT_TRIAL" ] || refuse 'watchdog did not acknowledge this boot/trial'
[ "$(getprop init.svc.andr36oid-rk915-trial-watchdog)" = running ] || refuse 'watchdog not init-owned/running'
# Arm the stopped-service guard only AFTER the service is running: setting it
# while the disabled service is still stopped could queue a spurious reboot.
setprop sys.andr36oid.rk915_trial.active 1 || refuse 'cannot arm init fallback trigger'
[ "$(getprop init.svc.andr36oid-rk915-trial-watchdog)" = running ] || refuse 'watchdog stopped during activation'
RISKY=1
mount -o bind "$TRIAL" "$MODULE" || refuse 'candidate bind failed'
insmod "$MODULE" pm_checked_resume=1 pm_wake_ack_timeout_ms=0 || refuse 'candidate insmod failed'
[ "$(cat /sys/module/rk915/version)" = "$EXPECT_VERSION" ] || refuse 'loaded module identity differs'
[ "$(cat /sys/module/rk915/parameters/pm_checked_resume)" = Y ] || refuse 'checked mode not enabled'
[ "$(cat /sys/module/rk915/parameters/pm_wake_ack_timeout_ms)" = 0 ] || refuse 'legacy cap not isolated'
[ "$(sha256sum "$MODULE" | cut -d' ' -f1)" = "$EXPECT_TRIAL" ] || refuse 'bound candidate bytes differ'
log "LIVE $KEY version=$EXPECT_VERSION checked=Y cap=0 SHA256=$EXPECT_TRIAL watchdog=running"
LIVE=1
exit 0
