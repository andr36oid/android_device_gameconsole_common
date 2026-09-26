#!/bin/bash
# Prints the andr36oid version, v<build date>-<kind>:
#   release  every project is committed and on GitHub
#   dirty    some project has local changes or commits that aren't pushed
#   debug    an eng build (the variant is the first argument)
# ANDR36OID_BUILD_KIND overrides the kind.
cd "$(dirname "$0")/../../.." || exit 1

kind=$ANDR36OID_BUILD_KIND
if [ -z "$kind" ] && [ "${1:-$TARGET_BUILD_VARIANT}" = eng ]; then
    kind=debug
fi
if [ -z "$kind" ]; then
    kind=release
    # The Rockchip libraries write the git state into these during the build.
    generated=':!libhwjpeg/mpi/version.h :!omx_il/include/rockchip/git_info.h'
    if [ -f .repo/project.list ] && xargs -P16 -I{} sh -c '
            cd "{}" 2>/dev/null || exit 0
            git diff-index --quiet HEAD -- . $0 2>/dev/null || exit 255
            [ -n "$(git for-each-ref --contains HEAD refs/remotes --count=1)" ] || exit 255
        ' "$generated" < .repo/project.list 2>/dev/null; then
        :
    else
        kind=dirty
    fi
fi

echo "v$(date -u +%Y-%m-%d)-$kind"
