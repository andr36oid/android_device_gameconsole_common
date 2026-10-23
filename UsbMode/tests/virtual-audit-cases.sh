#!/bin/sh
# The overrides below are called indirectly by virtual_request.
# shellcheck disable=SC2034,SC2154,SC2317
command mkdir -p "$T/preflight"
for audit_tool in truncate fallocate losetup dd od fsync tail tr cut; do
    ln -sf "$(command -v "$audit_tool")" "$T/preflight/$audit_tool"
done
ln -sf /usr/bin/printf "$T/preflight/printf"
for audit_tool in truncate fallocate losetup dd od fsync; do
    virtual_fixture
    rm "$T/preflight/$audit_tool"
    # Deliberately hide every other tool location for this preflight.
    # shellcheck disable=SC2123
    if (PATH=$T/preflight; v_tools_ready=0; virtual_tools); then
        echo "FAIL: missing $audit_tool accepted"; exit 1
    fi
    check test "$(prop verror)" = tools
    check test "$(prop vdetail)" = "Required image tool missing: $audit_tool"
    check test ! -d "$STATE/virtual"
    # A successful daemon preflight is cached across requests.
    # shellcheck disable=SC2123
    if ! (PATH=$T/preflight; v_tools_ready=1; virtual_tools); then exit 1; fi
    ln -sf "$(command -v "$audit_tool")" "$T/preflight/$audit_tool"
done
for audit_case in failed_space missing_fs malformed loop; do
    virtual_fixture
    case $audit_case in
    failed_space)
        (virtual_space() { return 1; }; virtual_request)
        audit_reason=space ;;
    missing_fs)
        image_root
        easy_mount=999999
        check test "$(virtual_space)" = "$(printf '921600000000\t-\t0')"
        (virtual_space() { printf '921600000000\t-\t0\n'; }; virtual_request)
        audit_reason=fs ;;
    malformed)
        (virtual_space() { printf '921600000000\texfat\n'; }; virtual_request)
        audit_reason=space ;;
    loop)
        export VIRTUAL_LOOP_OUTPUT=invalid
        check virtual_request
        unset VIRTUAL_LOOP_OUTPUT
        audit_reason=loop ;;
    esac
    check test "$(prop vresult)" = error
    check test "$(prop verror)" = "$audit_reason"
    check test -n "$(prop vdetail)"
    check test ! -d "$STATE/virtual"
    check test ! -e "$MEDIA/UUID/isos/.My drive.img.creating"
    check test ! -e "$MEDIA/UUID/isos/My drive.img"
done
