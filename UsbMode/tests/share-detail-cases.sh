#!/bin/sh
# shellcheck disable=SC2154,SC2034
for file_error_case in busy blank silent; do
    image_fixture
    setprop "$P.detail" 'old detail'
    setprop "$P.vdetail" 'old virtual detail'
    fail_file=1 file_error=$file_error_case
    check handle_request
    check test "$(prop state)" = error
    check test "$(prop error)" = configure_drive
    check test -n "$(prop vdetail)"
    check test "$(prop detail)" = "$(prop vdetail)"
    case $file_error_case:$(prop vdetail) in
        busy:'Cannot open backing storage: device is busy'|blank:'Backing storage rejected the open'|silent:'configure_drive failed (exit 1)') ;;
        *) echo "FAIL: missing/wrong diagnostic: $(prop vdetail)"; exit 1 ;;
    esac
    check test "$(printf '%s' "$(prop vdetail)" | wc -l)" = 0
    check test -s "$STATE/session.log"
done
reset_fixture
setprop "$P.detail" 'stale previous failure'
rm -f "$STATE/session-step.log"
if fail configure_drive 7; then exit 1; fi
check test "$(prop vdetail)" = 'configure_drive failed (exit 7)'
# Recovery must retain the first failure rather than replace its cause.
if fail close_drive 9; then exit 1; fi
check test "$(prop error)" = configure_drive
check test "$(prop vdetail)" = 'configure_drive failed (exit 7)'
