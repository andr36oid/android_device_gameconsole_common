#!/bin/sh
# Only root-private temp files/proc identity reads; no power/mount/device writes.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
base=$(mktemp -d)
trap 'rm -rf "$base"' EXIT
STATE=$base/state; ROOT=$base/root; IMAGE=; A36_CONTROL=$base/control
mkdir -p "$STATE" "$ROOT"
getprop() { :; };setprop() { :; }
. "$here/andr36oid-images.sh"
. "$here/andr36oid-update.sh"
job=0123456789abcdef
for phase in queued verifying unpacking checking; do
  release_cancel_phase "$job" "$phase"
  cancel_release ffffffffffffffff
  [ ! -f "$RELEASE_CANCEL_DIR/$job" ]
  cancel_release "$job"
  [ -f "$RELEASE_CANCEL_DIR/$job" ]
  if release_cancel_phase "$job" copying; then echo 'missed accepted cancellation' >&2;exit 1;fi
  rm -f "$RELEASE_CANCEL_DIR/$job"
done
for phase in copying publishing committed setup restaging cleaning finalizing finished; do
  release_cancel_phase "$job" "$phase"
  cancel_release "$job"
  [ ! -f "$RELEASE_CANCEL_DIR/$job" ]
done
# Dead/reused owner cannot be cancelled using an old displayed job token.
release_cancel_phase "$job" verifying
sed 's/^start=.*/start=not-the-owner/' "$RELEASE_CANCEL_DIR/gate" > "$base/stale"
mv "$base/stale" "$RELEASE_CANCEL_DIR/gate"
cancel_release "$job"
[ ! -f "$RELEASE_CANCEL_DIR/$job" ]
# Cancellation and copy admission race on the SAME lock; exactly one wins.
n=0
while [ "$n" -lt 30 ]; do
  release_cancel_phase "$job" verifying
  cancel_release "$job" & pid=$!
  copy=0; release_cancel_phase "$job" copying && copy=1
  wait "$pid"
  if [ "$copy" = 1 ]; then [ ! -f "$RELEASE_CANCEL_DIR/$job" ]
  else [ -f "$RELEASE_CANCEL_DIR/$job" ]; fi
  rm -f "$RELEASE_CANCEL_DIR/$job"
  n=$((n+1))
done
# mksh retains descriptor restorations inside EXIT traps. A second gate there
# must not inherit the first invocation's flock through an internal saved fd.
(
  trap 'release_cancel_phase "$job" finalizing; release_cancel_phase "$job" finished' EXIT
  :
)
printf 'PASS %s cancel gate: four allowed states, closed states/wrong job/stale owner refused;30 cancel-vs-copy races serialized\n' "${KSH_VERSION:-shell}"
