# Offline controller audit fixtures. Only extracted function definitions run.
set -eu
broker=${A36_TEST_BROKER:-$(CDPATH='' cd -- "$(dirname "$0")/.." && pwd)/andr36oid-subsystem.sh}
tmp=$(mktemp -d "${TMPDIR:-/tmp}/subsystem-controls.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
ROOT="$tmp/root"; UPPER="$tmp/upper"
mkdir -p "$ROOT" "$UPPER"
log() { printf '%s\n' "$*" >> "$tmp/log"; }
sed -n '/^root_path() {/,/^}/p' "$broker" > "$tmp/functions"
sed -n '/^SDL_PAD_BINDS=/,/^prime_input() {/p' "$broker" | sed '$d' >> "$tmp/functions"
. "$tmp/functions"
fail() { echo "FAIL: $*" >&2; exit 1; }
contains() { grep -F -q -- "$2" "$1" || fail "missing $2 in $1"; }
exact() { grep -F -x -q -- "$2" "$1" || fail "missing exact line $2 in $1"; }
repeat() {
  cp -R "$ROOT" "$tmp/first"
  "$@"
  "$@"
  diff -r "$tmp/first" "$ROOT" >/dev/null || fail "repeated $* changed files"
  rm -rf "$tmp/first"
}
# Assert newly added migrations remain on the every-session path.
primed() {
  sed -n '/^prime_input() {/,/^}/p' "$broker" | grep -q "^  $1$" || fail "$1 absent from prime_input"
}
