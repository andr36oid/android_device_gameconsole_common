#!/system/bin/sh
# SPDX-License-Identifier: MIT
# andr36oid PortMaster handoff broker.
#
# Runs a port (or the PortMaster GUI) with the display handed off from Android to
# the glibc ports runtime, then restores Android. Proven sequence (see
# work/reports/portmaster G1/G2/G3): tear the Android framework down so there is
# no DisplayManager fighting us and no PowerManager to suspend-freeze the clone,
# stop the composer cleanly so card0 frees without the drm_fb_release wedge, run
# the SDL/KMSDRM port on the free display through libmali, then restart the
# composer, SurfaceFlinger and the framework.
#
# Usage (must be root):
#   andr36oid-port-broker.sh run <port-binary-relative-to-root> [args...]
#   andr36oid-port-broker.sh gui         # launch the PortMaster GUI
#
# Robustness: a detached guardian watches this script's PID and force-restores
# Android if we die without cleaning up, so the console can never be left in the
# headless state. Framework cycles blip adb; nothing here depends on adb.

set -u
umask 022

ROOT=${PM_ROOT:-/data/local/tmp/pmroot}       # glibc ports runtime
STATE=${PM_STATE:-/data/local/tmp/portmaster} # logs + guardian flags
COMPOSER=${PM_COMPOSER:-vendor.hwcomposer-2-1}
GUARD_GRACE=${PM_GUARD_GRACE:-20}             # seconds after our death before forced restore

mkdir -p "$STATE"
LOG="$STATE/broker.log"
log() { printf '%s %s\n' "$(cut -d. -f1 /proc/uptime)s" "$*" >> "$LOG"; }

[ "$(id -u)" = 0 ] || { echo "broker: must run as root" >&2; exit 1; }
[ -d "$ROOT/usr/lib" ] || { echo "broker: ports runtime missing at $ROOT" >&2; exit 1; }

mode=${1:-}; shift 2>/dev/null || true
case "$mode" in
  run) [ "$#" -ge 1 ] || { echo "broker: run needs a port binary" >&2; exit 2; } ;;
  gui) set -- usr/bin/sdl-port-probe 999999 ;;   # placeholder until PortMaster GUI is staged
  *) echo "usage: $0 run <binary> [args] | gui" >&2; exit 2 ;;
esac

: > "$STATE/broker.clean"; rm -f "$STATE/broker.clean"   # ensure absent = not yet clean
: > "$LOG"
log "broker start mode=$mode cmd=$*"

# Record which display services are running so we restore exactly those.
JOURNAL="$STATE/broker.journal"
: > "$JOURNAL"
for svc in "$COMPOSER" surfaceflinger; do
  [ "$(getprop "init.svc.$svc")" = running ] && echo "$svc" >> "$JOURNAL"
done

restore() {
  # Idempotent: bring the composer, SurfaceFlinger and framework back.
  setprop "ctl.start" "$COMPOSER" 2>/dev/null
  sleep 1
  setprop "ctl.start" surfaceflinger 2>/dev/null
  start 2>/dev/null
}

# Detached guardian: if this script's PID disappears without leaving the clean
# flag, wait a short grace then force-restore. Survives our SIGKILL.
MYPID=$$
(
  while kill -0 "$MYPID" 2>/dev/null; do sleep 1; done
  [ -f "$STATE/broker.clean" ] && exit 0
  sleep "$GUARD_GRACE"
  [ -f "$STATE/broker.clean" ] && exit 0
  echo "GUARDIAN forced restore $(cut -d. -f1 /proc/uptime)s" >> "$LOG"
  setprop ctl.start "$COMPOSER" 2>/dev/null; sleep 1
  setprop ctl.start surfaceflinger 2>/dev/null; start 2>/dev/null
) &
GUARD=$!

cleanup() {
  rc=$?
  trap - EXIT INT TERM HUP
  log "cleanup rc=$rc, restoring"
  restore
  : > "$STATE/broker.clean"     # tell the guardian we restored
  kill "$GUARD" 2>/dev/null
  log "broker end rc=$rc"
  exit "$rc"
}
trap cleanup EXIT INT TERM HUP

# --- handoff -----------------------------------------------------------------
log "stop framework"
stop
i=0; while [ "$(getprop init.svc.surfaceflinger)" = running ] && [ "$i" -lt 10 ]; do sleep 1; i=$((i+1)); done
log "sf=$(getprop init.svc.surfaceflinger) hwc=$(getprop init.svc.$COMPOSER)"
log "stop composer"
setprop ctl.stop "$COMPOSER"
i=0; while [ "$(getprop init.svc.$COMPOSER)" = running ] && [ "$i" -lt 10 ]; do sleep 1; i=$((i+1)); done
ln -sf libmali.so "$ROOT/usr/lib/libmali.so.1" 2>/dev/null

# --- run the port on the freed display --------------------------------------
log "launch: $*"
LD_LIBRARY_PATH="$ROOT/usr/lib" \
SDL_VIDEODRIVER=kmsdrm SDL_KMSDRM_REQUIRE_DRM_MASTER=1 \
HOME="$STATE" TMPDIR="$STATE" \
  "$ROOT/lib/ld-linux-aarch64.so.1" --library-path "$ROOT/usr/lib" "$ROOT/$1" "$@" \
  >> "$LOG" 2>&1
PORT_RC=$?
shift $# 2>/dev/null || true
log "port exited rc=$PORT_RC"
# cleanup (trap) restores Android.
exit "$PORT_RC"
