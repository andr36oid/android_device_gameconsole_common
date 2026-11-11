#!/bin/sh
# Host-root workers must be recoverable without relying on a chroot /proc sweep.
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions process_start_time session_active \
  record_session_worker await_session_worker forward_session_console run_session_worker stop_session_workers
build_guest_helper
run_session() {
  # A host-root descendant with the same busy behavior as a stuck watcher/retry
  # loop. Cleanup must terminate it even after the original broker disappears.
  sh -c 'echo "$$" > "$1"; while :; do :; done' busy-worker "$tmp/busy" &
  wait "$!"
}
run_session_worker & runner=$!
printf '%s\n' "$runner" >> "$tmp/pids"
record_session_worker runner "$runner"
wait_until test -s "$tmp/busy"
busy=$(cat "$tmp/busy"); printf '%s\n' "$busy" >> "$tmp/pids"
mkfifo "$ROOT/dev-console"
mkdir -p "$ROOT/dev"
mv "$ROOT/dev-console" "$ROOT/dev/console"
: > "$STATE/session.fifo"
forward_session_console & forwarder=$!
printf '%s\n' "$forwarder" >> "$tmp/pids"
record_session_worker console "$forwarder"
sleep 60 & unrelated=$!
printf '%s\n' "$unrelated" >> "$tmp/pids"
# Run in another shell to model guardian cleanup, which cannot wait() children
# of the dead broker. The start-time/boot journals still identify its workers.
(stop_session_workers) || fail 'guardian could not stop host workers'
not_alive "$runner" || fail 'runner survived restore'
not_alive "$forwarder" || fail 'console forwarder survived restore'
wait "$runner" 2>/dev/null || :
wait "$forwarder" 2>/dev/null || :
not_alive "$busy" || fail 'host-root busy descendant survived restore'
alive "$unrelated" || fail 'unrelated Android process was killed'
[ ! -f "$STATE/session.runner" ] && [ ! -f "$STATE/session.console" ] || fail 'retired worker journal survived'
stop_session_workers || fail 'repeated worker cleanup failed'
# A recycled PID must never be killed from a stale journal.
stamp=$(process_start_time "$unrelated"); boot=$(cat /proc/sys/kernel/random/boot_id)
printf '%s %s %s\n' "$unrelated" "$((stamp+1))" "$boot" > "$STATE/session.runner"
stop_session_workers
alive "$unrelated" || fail 'recycled host PID was killed'
# Failure must retain the worker identity so a later recovery can finish.
printf '%s %s %s\n' "$unrelated" "$stamp" "$boot" > "$STATE/session.runner"
(
  guest_failure() { printf 'phase=freeze [pid=%s comm=sleep state=D ppid=1]\n' "$unrelated"; return 1; }
  GUEST=guest_failure
  if stop_session_workers; then fail 'worker failure reported success'; fi
  [ -s "$STATE/session.runner" ] || fail 'worker failure lost journal'
  grep -Fq "runner worker tree did not stop: phase=freeze [pid=$unrelated comm=sleep state=D" "$LOG" ||
    fail 'worker failure omitted survivor details'
)
rm -f "$STATE/session.runner"
# Linux exposes children per thread. A child forked by a nonleader thread
# must not outlive the tracked worker even though the leader has no children.
cat > "$tmp/thread-worker.c" <<'C'
#define _GNU_SOURCE
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <unistd.h>
static void *worker(void *marker) {
    pid_t pid = fork();
    if (pid < 0) _exit(2);
    if (pid == 0) {
        if (setsid() < 0) _exit(3);
        int fd = open(marker, O_WRONLY | O_CREAT | O_EXCL, 0600);
        if (fd < 0 || dprintf(fd, "%d\n", getpid()) < 0) _exit(3);
        close(fd);
    }
    for (;;) pause();
}
int main(int argc, char **argv) {
    pthread_t thread;
    if (argc != 2 || pthread_create(&thread, NULL, worker, argv[1])) return 1;
    for (;;) pause();
}
C
cc -O2 -Wall -Wextra -Werror -pthread "$tmp/thread-worker.c" -o "$tmp/thread-worker"
run_session() { exec "$tmp/thread-worker" "$tmp/thread-child"; }
run_session_worker & runner=$!
printf '%s\n' "$runner" >> "$tmp/pids"
record_session_worker runner "$runner"
wait_until test -s "$tmp/thread-child"
thread_child=$(cat "$tmp/thread-child")
printf '%s\n' "$thread_child" >> "$tmp/pids"
(stop_session_workers) || fail 'guardian could not stop multithreaded worker'
not_alive "$runner" || fail 'multithreaded runner survived worker teardown'
wait "$runner" 2>/dev/null || :
not_alive "$thread_child" || fail 'child of nonleader thread survived worker teardown'
# A broker that died before publication must not admit the pending worker.
rm "$STATE/session.lock/pid"
rm -f "$tmp/busy"
if run_session_worker; then fail 'unpublished worker ran without its owner'; fi
[ ! -f "$tmp/busy" ] || fail 'orphan worker admitted work'
echo 'PASS: guardian reaps host-root runner/watcher descendants and console, preserves unrelated/recycled PIDs, retains failed cleanup identities'
