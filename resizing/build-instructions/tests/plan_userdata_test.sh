#!/bin/sh
# Checks the userdata sizing in overlay/linuxrc. Run: sh tests/plan_userdata_test.sh
# (any POSIX sh; set SH to try another, e.g. the resizer's busybox under qemu).
cd "$(dirname "$0")/.." || exit 1
code=$(sed -n '/^# BEGIN plan_userdata/,/^# END plan_userdata/p' overlay/linuxrc)
fail=0
GB=1000000000

# check <card bytes> <wish> <noroms before> <expected NOROMS> <expected userdata GiB, "half" or "all">
check() {
    sectors=$(($1 / 512))
    out=$(${SH:-sh} -c "$code
NOROMS=$3; plan_userdata $sectors '$2' >/dev/null; echo \$NOROMS \$USERDATA_END")
    noroms=${out% *}; end=${out#* }
    last=$((sectors - 34))
    if [ "$5" = all ]; then exp_end=$last
    elif [ "$5" = half ]; then exp_end=$(( (5701009 + (last - 5701009 + 1) / 2) / 2048 * 2048 - 1 ))
    else exp_end=$(( (5701009 + $5 * 2097152) / 2048 * 2048 - 1 )); fi
    ok=1
    [ "$noroms" = "$4" ] || ok=0
    [ "$end" = "$exp_end" ] || ok=0
    [ "$end" -le "$last" ] || ok=0
    if [ "$noroms" = 0 ]; then
        [ $(( (end + 1) % 2048 )) = 0 ] || ok=0
        [ $((last - end)) -ge 2097152 ] || ok=0
    fi
    if [ $ok = 1 ]; then echo "ok   $1 '$2' noroms=$3 -> NOROMS=$noroms end=$end"
    else echo "FAIL $1 '$2' noroms=$3 -> NOROMS=$noroms end=$end (want $4 $exp_end)"; fail=1; fi
}

# Kauan's 20.1 GB card: half of the free space (just over 8 GiB), about 8.6 GB EASYROMS
check 20113457152 "" 0 0 half
# 16 GB card: half is under 8 GiB, so 8 GiB; EASYROMS still gets over 1 GiB
check 16000000000 "" 0 0 8
# 8 GB card: 8 GiB would not fit, so everything goes to userdata
check 8000000000 "" 0 1 all
# 64 GB card: half of the free space
check $((64 * GB)) "" 0 0 half
# 512 GB card: capped at 64 GiB
check $((512 * GB)) "" 0 0 64
# .noroms always wins
check $((64 * GB)) "16" 1 1 all
# Flasher wish
check $((64 * GB)) "16" 0 0 16
check $((64 * GB)) "all" 0 1 all
# Wish leaves under 1 GiB for EASYROMS: everything to userdata
check $((64 * GB)) "56" 0 1 all
# Wish bigger than the card
check $((32 * GB)) "100" 0 1 all
# Tiny wish is raised to 4 GiB
check $((64 * GB)) "1" 0 0 4
# Junk falls back to the default
check $((512 * GB)) "lots" 0 0 64

exit $fail
