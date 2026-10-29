# Helpers for shell-only broker regressions. No mounts, Android services or audio.
broker=${A36_TEST_BROKER:-$(CDPATH='' cd -- "$(dirname "$0")/.." && pwd)/andr36oid-subsystem.sh}
tmp=$(mktemp -d "${TMPDIR:-/tmp}/subsystem-lifecycle.XXXXXX")
STATE="$tmp/state"; ROOT="$tmp/root"; LOG="$tmp/log"
MUTE_PIDFILE="$STATE/mute-guard.pid"
HANDOFF=1; SESSION_MODE=wrapper; SESSION_OWNED=1; DID_HANDOFF=0
LOOPDEV=; BOUND=; SDL_PAD_MAP=; cmd=start; arg=; GUARD_GRACE=0
mkdir -p "$STATE/session.lock" "$ROOT/run" "$ROOT/tmp"
printf '%s\n' "$$" > "$STATE/session.lock/pid"
cat /proc/sys/kernel/random/boot_id > "$STATE/session.lock/boot_id"
: > "$STATE/session.loop"
load_functions() {
  for fn do
    awk -v name="$fn" '
      $0 ~ ("^" name "\\(\\) \\{") {
        active=1
        if ($0 ~ /} *$/) {print; exit}
      }
      active {
        print
        if ($0 ~ /<<.SHIM./) shim=1
        else if ($0 == "SHIM") shim=0
        else if ($0 == "}" && !shim) exit
      }
    ' "$broker" >> "$tmp/functions"
  done
  . "$tmp/functions"
  rm "$tmp/functions"
}
fail() { echo "FAIL: $*" >&2; exit 1; }
log() { printf '%s\n' "$*" >> "$LOG"; }
getprop() { [ "$1" != persist.andr36oid.subsystem.mute ] || echo 1; }
setprop() { printf '%s\n' "$*" >> "$tmp/properties"; }
tinymix() { printf '%s\n' "$*" >> "$tmp/mixer"; }
update_settings_entry() { :; }
# Use /proc state so an exited grandchild zombie does not count as a survivor.
alive() {
  [ -r "/proc/$1/stat" ] || return 1
  case "$(cat "/proc/$1/stat" 2>/dev/null)" in *') Z '*|*') X '*) return 1 ;; esac
  kill -0 "$1" 2>/dev/null
}
wait_until() {
  count=0
  until "$@"; do
    count=$((count+1)); [ "$count" -lt 100 ] || fail "timed out: $*"
    sleep 0.05
  done
}
not_alive() { ! alive "$1"; }
cleanup() {
  # Only PIDs created and saved by these tests, including a legacy orphan guard.
  for file in "$MUTE_PIDFILE" "$tmp/guard" "$tmp/pids"; do
    [ -f "$file" ] || continue
    while read -r pid rest; do
      case "$pid" in ''|*[!0-9]*) continue ;; esac
      kill -KILL "$pid" 2>/dev/null || :
    done < "$file"
  done
  rm -rf "$tmp"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
