#!/system/bin/sh
# Reapplied on every start. Keep this script idempotent.
# The hotfix applier that shipped on this release predates the build=any key, so
# it skips universal hotfixes. files/0 is a newer applier that understands it.
# Run it once, in its own state directory so it never disturbs the applier that
# ran us, to apply the build=any hotfixes the old applier passed over. The guard
# stops files/0 from running itself again, and its own crash-pause still bounds
# any failure.
set -e
if [ -z "$HOTFIX_REEXEC" ]; then
	state=$(dirname "$(dirname "$HOTFIX_DIR")")
	HOTFIX_REEXEC=1 HOTFIX_STATE="$state/any" "${HOTFIX_SH:-/system/bin/sh}" files/0
fi
