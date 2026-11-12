#!/system/bin/sh
# Keep the owner's 60s trim, but never race an autostart handoff/session.
[ "$(getprop persist.andr36oid.boot_trim)" != 0 ] || exit 0
[ ! -d /dev/.andr36oid-boot-trim-done ] || exit 0
sleep 60
for key in boot_deciding boot_pending; do
  [ "$(getprop sys.andr36oid.subsystem.$key)" != 1 ] || exit 0
done
[ "$(getprop sys.andr36oid.subsystem.session)" != running ] || exit 0
[ "$(getprop init.svc.zygote)" = running ] || exit 0
# The same init service serializes boot/return triggers. Mark only success, so a
# skipped/failed early trim can retry 60s after the normal session-return trigger.
if timeout -k 1 3 /system/bin/cmd activity kill-all; then mkdir /dev/.andr36oid-boot-trim-done 2>/dev/null || :; fi
