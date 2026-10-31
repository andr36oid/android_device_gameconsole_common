#!/bin/sh
set -eu
. "$(dirname "$0")/test-lib.inc"
load_functions root_path
broker=${broker%/*}/andr36oid-guest.sh
load_functions repair_native_fn_watcher write_guest_session
source_dir=$(CDPATH='' cd -- "$(dirname "$0")/../../subsystem" && pwd)
cc -Wall -Wextra -Werror -O2 "$(dirname "$0")/hotkey-test.c" -o "$tmp/hotkeys"
"$tmp/hotkeys"
cc -Wall -Wextra -Werror -O2 "$(dirname "$0")/fn-watcher-stream-test.c" -o "$tmp/stream"
"$tmp/stream"
cc -Wall -Wextra -Werror -O2 "$(dirname "$0")/guest-input-stream-test.c" -o "$tmp/guest-stream"
"$tmp/guest-stream"
# A static helper must execute in the native glibc image without Android's
# dynamic linker. Both launch paths keep the image's original helper path.
FN_WATCH="$tmp/fn-watch"
cc -Wall -Wextra -Werror -O2 -static "$source_dir/andr36oid-fn-watch.c" -o "$FN_WATCH"
mkdir -p "$ROOT/usr/local/bin"
helper="$ROOT/usr/local/bin/fn_menu_watcher.sh"
cp "$(dirname "$0")/fixtures/native-helpers/fn_menu_watcher.sh" "$helper"
# A hotfix without the flashed system binary must preserve the original shell
# helper too, not just a previously installed ELF replacement.
FN_WATCH="$tmp/missing"
if repair_native_fn_watcher; then fail 'missing binary accepted the original image helper'; fi
cmp "$(dirname "$0")/fixtures/native-helpers/fn_menu_watcher.sh" "$helper" || fail 'missing binary changed original helper'
FN_WATCH="$tmp/fn-watch"
repair_native_fn_watcher
cmp "$FN_WATCH" "$helper" || fail 'native watcher was not replaced'
[ -x "$helper" ] || fail 'replacement watcher is not executable'
repair_native_fn_watcher
cmp "$FN_WATCH" "$helper" || fail 'watcher replacement was not idempotent'
# Same-directory staging must preserve the old file on every preparation or
# rename failure, even after cp has already written partial output.
for fault in mktemp cp chmod mv; do
  (
    mktemp() { [ "$fault" != mktemp ] || return 1; command mktemp "$@"; }
    cp() {
      if [ "$fault" = cp ]; then printf partial > "$2"; return 1; fi
      command cp "$@"
    }
    chmod() { [ "$fault" != chmod ] || return 1; command chmod "$@"; }
    mv() { [ "$fault" != mv ] || return 1; command mv "$@"; }
    if repair_native_fn_watcher; then fail "$fault failure reported success"; fi
    cmp "$FN_WATCH" "$helper" || fail "$fault failure damaged installed watcher"
    [ -x "$helper" ] || fail "$fault failure changed installed permissions"
    set -- "${helper%/*}"/.a36-fn-watch.*
    [ ! -e "$1" ] || fail "$fault failure leaked a staging file"
  )
done
# Observe the old complete inode at the publication boundary.
(
  mv() {
    cmp "$FN_WATCH" "$helper" || fail 'helper changed before rename'
    cmp "$FN_WATCH" "$2" || fail 'staged watcher was incomplete'
    [ -x "$2" ] || fail 'staged watcher was not executable'
    command mv "$@"
  }
  repair_native_fn_watcher
)
# A new source binary on a later boot must replace the persistent overlay's
# older ELF. Appended bytes distinguish the fixture without changing its ELF.
cp "$FN_WATCH" "$tmp/next-boot-watch"
printf '\nnext-boot fixture\n' >> "$tmp/next-boot-watch"
( FN_WATCH="$tmp/next-boot-watch"; repair_native_fn_watcher )
cmp "$tmp/next-boot-watch" "$helper" || fail 'later boot retained an older watcher'
repair_native_fn_watcher
cmp "$FN_WATCH" "$helper" || fail 'repeat boot did not restore matching source'
FN_WATCH="$tmp/missing"
if repair_native_fn_watcher; then fail 'missing helper admitted a broken watcher'; fi
cmp "$tmp/fn-watch" "$helper" || fail 'failed replacement destroyed the installed watcher'
write_guest_session
grep -Fq '/usr/local/bin/fn_menu_watcher.sh &' "$ROOT/run/andr36oid-session.sh" || fail 'wrapper bypasses repaired helper'
# Images without the optional native watcher must not gain a second sender.
rm "$helper"
repair_native_fn_watcher
[ ! -e "$helper" ] || fail 'optional watcher was unexpectedly created'
echo 'PASS: native Fn watcher deployment, static guest helper, repeat installation and missing-helper failure'
