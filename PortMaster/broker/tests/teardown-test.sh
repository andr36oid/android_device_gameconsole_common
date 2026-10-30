#!/bin/sh
# Requires a Linux root shell and a compiler; exposes no hardware to the fixture.
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions kill_subsystem_payload kill_subsystem restore_android finish_session
cat > "$tmp/held.c" <<'EOF'
#include <fcntl.h>
#include <unistd.h>
int main(int argc,char **argv) {
  if(argc!=2 || chroot(argv[1]) || chdir("/"))return 1;
  int fd=open("/ready",O_WRONLY|O_CREAT,0600);
  if(fd<0)return 1;
  for(;;)pause();
}
EOF
cc -static -O2 "$tmp/held.c" -o "$ROOT/held"
"$ROOT/held" "$ROOT" & child=$!
printf '%s\n' "$child" >> "$tmp/pids"
sleep 60 & unrelated=$!
printf '%s\n' "$unrelated" >> "$tmp/pids"
wait_until test -e "$ROOT/ready"
SESSION_PID=
kill_subsystem
not_alive "$child" || fail 'teardown returned before the guest exited'
wait "$child" 2>/dev/null || :
alive "$unrelated" || fail 'teardown killed a host process'
stop_mute_guard() { :; }
prepare_retroarch_sync() { RA_SYNC_DIR=; }
restore_guest_hardware() { return 1; }
operation_done() { printf '%s\n' "$1" > "$tmp/result"; }
# A clean frontend exit cannot hide failed Android hardware restoration.
if (finish_session 0); then fail 'failed restoration reported a successful session'; fi
[ "$(cat "$tmp/result")" = 1 ] || fail 'cleanup failure was not published'
[ -d "$STATE/session.lock" ] || fail 'failed cleanup discarded the recovery journal'
echo 'PASS: scoped guest teardown waits for exit, preserves host processes, and reports retained cleanup failures'
