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
# A static helper must execute in the native glibc image without Android's
# dynamic linker. Both launch paths keep the image's original helper path.
FN_WATCH="$tmp/fn-watch"
cc -Wall -Wextra -Werror -O2 -static "$source_dir/andr36oid-fn-watch.c" -o "$FN_WATCH"
mkdir -p "$ROOT/usr/local/bin"
helper="$ROOT/usr/local/bin/fn_menu_watcher.sh"
cp "$(dirname "$0")/fixtures/native-helpers/fn_menu_watcher.sh" "$helper"
repair_native_fn_watcher
cmp "$FN_WATCH" "$helper" || fail 'native watcher was not replaced'
[ -x "$helper" ] || fail 'replacement watcher is not executable'
repair_native_fn_watcher
cmp "$FN_WATCH" "$helper" || fail 'watcher replacement was not idempotent'
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
