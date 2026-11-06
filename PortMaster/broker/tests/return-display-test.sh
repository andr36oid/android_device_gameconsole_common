#!/bin/sh
# Exercise real EXIT/recovery restoration with simulated Android services/DRM.
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions restore_android finish_session
HWC=composer
stop_mute_guard() { :; }
stop_audio_route_guard() { :; }
sleep() { :; }
getprop() { [ "$1" != init.svc.surfaceflinger ] || printf '%s\n' "$sf"; }
setprop() {
  printf '%s\n' "$*" >> "$tmp/events"
  case "$*" in 'ctl.start surfaceflinger') sf=running;; esac
}
kill_subsystem() { echo guest-reaped >> "$tmp/events"; }
stop_android_display() { sf=stopped; echo drm-released >> "$tmp/events"; }
show_splash() { echo splash >> "$tmp/events"; }
restore_guest_hardware() {
  echo gamma-restored >> "$tmp/events"
  rm -f "$STATE/session.gamma"
}
prepare_retroarch_sync() { RA_SYNC_DIR=; echo sync >> "$tmp/events"; }
release_session_loop() { echo loop-released >> "$tmp/events"; }
start() {
  echo framework-start >> "$tmp/events"
  # WMS ends bootanim after windows draw, including a runtime restart where
  # sys.boot_completed was already 1. Model that real Android handshake.
  setprop service.bootanim.exit 1
}
DID_HANDOFF=1; sf=stopped
: > "$STATE/session.gamma"
restore_android
[ "$CLEANUP_FAILED" = 0 ] || fail 'normal restoration failed'
cat > "$tmp/expected" <<'EOF'
guest-reaped
splash
drm-released
gamma-restored
ctl.start composer
service.bootanim.exit 0
ctl.start surfaceflinger
ctl.start bootanim
sync
loop-released
framework-start
service.bootanim.exit 1
EOF
cmp "$tmp/expected" "$tmp/events" || fail 'animation did not precede slow cleanup/framework or raced DRM restoration'
# Repeated recovery after Android is back must not resurrect the animation or
# clear WMS's exit flag. Starting an already running SF is an init no-op.
: > "$tmp/events"
restore_android
if grep -Eq 'service.bootanim.exit 0|ctl.start bootanim|sys.boot_completed' "$tmp/events"; then
  fail 'repeated recovery covered live Android or reset boot completion'
fi
# EXIT uses the same display path even for a failed guest, retaining its status.
: > "$tmp/events"; sf=stopped
if (finish_session 42); then fail 'guest failure became success'; else rc=$?; fi
[ "$rc" = 42 ] || fail 'guest exit status was lost'
grep -qx 'ctl.start bootanim' "$tmp/events" || fail 'failed guest skipped return animation'
# A session without handoff never touches the Android display.
: > "$tmp/events"; DID_HANDOFF=0
restore_android
if grep -Eq 'bootanim|ctl.start' "$tmp/events"; then fail 'no-handoff session restarted display'; fi
echo 'PASS: return animation follows guest/gamma release, precedes storage/framework, ends through WMS, and tolerates repeated recovery and failed EXIT'
