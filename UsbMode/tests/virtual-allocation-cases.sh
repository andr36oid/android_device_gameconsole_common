#!/bin/sh
# shellcheck disable=SC2034,SC2154
for gb in 1 2 4 16 64 1024; do
    kib=$((gb * 1048576))
    chunk=$(virtual_chunk "$kib")
    check test "$chunk" -ge 65536
    check test "$((chunk % 65536))" = 0
    check test "$(((kib + chunk - 1) / chunk))" -le 100
done
# A large exFAT image retains exact sizes and progress with a bounded number of children.
virtual_fixture
export VIRTUAL_FREE_KB=1200000000
setprop "$P.vsize" 1024
setprop "$P.vfs" exfat
check virtual_request
check test "$(prop vresult)" = 'done'
check test "$(stat -c %s "$MEDIA/UUID/isos/My drive.img")" = 1099511627776
check python3 "$HERE/tests/virtual-allocation.py" "$T/tools.log" "$T/log"
unset VIRTUAL_FREE_KB
