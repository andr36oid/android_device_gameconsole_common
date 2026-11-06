#!/bin/sh
# No privileged mounts. Optional: A36_TEST_BTRFS_DIR=/mounted/btrfs runs the real
# space/fsync/journal path there; the source fixture always probes as btrfs.
set -eu
here=$(CDPATH='' cd -- "$(dirname "$0")/.." && pwd)
python3 - "$here" <<'PY'
from pathlib import Path
import os, shlex, shutil, struct, subprocess, sys, tempfile

broker = Path(sys.argv[1])
backing = os.environ.get('A36_TEST_BTRFS_DIR')
if backing:
    assert subprocess.check_output(['stat', '-f', '-c', '%T', backing], text=True).strip() == 'btrfs'
else:
    print('SKIP: real btrfs backing (set A36_TEST_BTRFS_DIR); testing current host filesystem', flush=True)
with tempfile.TemporaryDirectory(prefix='a36-preflight-', dir=backing) as tmp:
    base = Path(tmp)
    native = base/'native-support'
    subprocess.run(['cc', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                    str(broker.parent/'erofs-tools/andr36oid-erofs-support.c'), '-o', str(native)], check=True)
    helpers = [('native', [str(native)])]
    if shutil.which('qemu-aarch64'):
        helpers.append(('prebuilt', [shutil.which('qemu-aarch64'), str(broker.parent/'erofs-tools/andr36oid-erofs-support')]))
    source = bytearray(512+131072)
    source[510:512] = b'\x55\xaa'; source[450] = 0x83
    struct.pack_into('<II', source, 454, 1, 256)
    x = 512+65536
    source[x+32:x+48] = bytes(range(16)); source[x+64:x+72] = b'_BHRfS_M'
    for offset, value in [(48, 65536), (72, 1), (112, 131072), (136, 1)]:
        struct.pack_into('<Q', source, x+offset, value)
    script = r'''
set -u
STATE=$BASE/state; ROOT=$BASE/root; IMAGE=; LOG=$BASE/log
A36_CONTROL=$BASE/control; A36_STORAGE_LOCK=$BASE/storage.lock; A36_USB_STATE=$BASE/usb
A36_EROFS_SUPPORT=$BASE/support; A36_OPT_PRIVATE=1
mkdir -p "$STATE" "$BASE/props"
getprop() { cat "$BASE/props/$1" 2>/dev/null || true; }
setprop() { printf '%s' "$2" > "$BASE/props/$1"; }
log() { printf '%s\n' "$*" >> "$LOG"; }
. "$LIBRARY"
image_cards() { printf '%s\tabcd-1234\tfake\trw\n' "$BASE/card"; }
power_hold() { WAKE=; }
sleep() { command sleep 0.02; }
losetup() {
  if [ "$MODE" = source_busy ]; then echo 'losetup: Device or resource busy (errno=16)' >&2; return 16; fi
  [ "${1:-}" != -f ] || echo /dev/loopMOCK
  return 0
}
mount() { :; }
umount() { :; }
MKFS=/bin/true; FSCK=/bin/true
case "$MODE" in
  support_missing) SUPPORT=$BASE/absent;;
  journal_dir) mkdir -p "$CONTROL"; touch "$OPT_DIR";;
esac
optimize_worker aaaaaaaaaaaaaaaa "$(selection_id abcd-1234 'source image.img')"
'''
    support_script = r'''#!/bin/sh
case "$MODE:$1" in
  journal_file:fsync) case "$2" in */journal.tmp.*) echo 'fsync: Permission denied (errno=13)' >&2; exit 13;; esac;;
  journal_flush:fsync) case "$2" in */optimize) echo 'fsync: Input/output error (errno=5)' >&2; exit 5;; esac;;
  space_missing:space) set -- space "$BASE/absent";;
  no_space:space) printf 'available=1024\ncluster=4096\nfile_limit=9223372036854775807\n'; exit 0;;
  data_space:space) case "$2" in */control) printf 'available=1024\ncluster=4096\nfile_limit=9223372036854775807\n'; exit 0;; esac;;
  *:metadata) echo 'metadata: test boundary reached after real space/journal' >&2; exit 65;;
  *:detach-owned) exit 0;;
esac
exec HELPER "$@"
'''
    cases = [
        ('journal_dir', 'journal_write', 'mkdir', None),
        ('support_missing', 'support_missing', 'support unavailable:', None),
        ('space_missing', 'space_probe', 'destination space: No such file', 'exit=1'),
        ('journal_file', 'journal_write', 'Permission denied', 'exit=13'),
        ('journal_flush', 'journal_write', 'Input/output error', 'exit=5'),
        ('no_space', 'no_space', 'reason=no_space', None),
        ('data_space', 'data_space', 'reason=data_space', None),
        ('source_busy', 'source_busy', 'Device or resource busy', 'exit=16'),
        ('metadata', 'invalid_image', 'test boundary reached', 'exit=65'),
    ]
    for shell in ['sh', 'bash', 'mksh']:
        if not shutil.which(shell): continue
        for label, helper in helpers:
            for mode, error, message, status in cases:
                run = base/(shell+'-'+label+'-'+mode); run.mkdir()
                card = run/'card/subsystem/darkosen'; card.mkdir(parents=True)
                (card/'source image.img').write_bytes(source)
                (run/'support').write_text(support_script.replace('HELPER', shlex.join(helper)))
                (run/'support').chmod(0o755)
                (run/'worker').write_text(script)
                env = dict(os.environ, BASE=str(run), MODE=mode, LIBRARY=str(broker/'andr36oid-images.sh'))
                result = subprocess.run([shell, str(run/'worker')], env=env, capture_output=True, text=True, timeout=30)
                def prop(name):
                    return (run/'props'/('sys.andr36oid.subsystem.'+name)).read_text()
                assert result.returncode == 1, (shell, label, mode, result)
                assert prop('opt_state') == 'failed' and prop('opt_error') == error, (mode, prop('opt_error'), result)
                log = (run/'state/optimize.log').read_text()
                assert message in log and (status is None or status in log), (mode, log)
                if mode in ['journal_file', 'journal_flush', 'no_space', 'data_space', 'source_busy', 'metadata']:
                    assert int(prop('opt_free')) >= 0 and prop('opt_free') == prop('opt_available'), mode
                assert prop('opt_total') == '', 'preflight must not invent an EROFS output estimate'
                assert (card/'source image.img').exists() and not list(card.glob('*.partial.*'))
            print('PASS:', shell, label, 'preflight stderr/argv/status, journal/space/missing-helper/busy errors, free bytes, source retention', flush=True)
    # Exercise the helper's real child exec failure and journal-after-spawn path.
    # Loop/mount/power state is absent, so stop_opt_child can safely reap exactly
    # the child this test launched.
    child_script = r'''
set -u
STATE=$BASE/state; ROOT=$BASE/root; A36_CONTROL=$BASE/control
A36_EROFS_SUPPORT=$BASE/support
mkdir -p "$STATE"
getprop() { :; }; setprop() { :; }
. "$LIBRARY"
mkdir -p "$OPT_DIR"
: > "$STATE/optimize-diagnostics.log"; : > "$OPT_DIR/worker.log"
OPT_JOB=aaaaaaaaaaaaaaaa; OPT_PHASE=preparing; OPT_START=$(clock_seconds); OPT_ERROR=
OPT_UUID=; OPT_SOURCE=; OPT_SOURCE_DEV=; OPT_SOURCE_INO=; OPT_OFFSET=0
PARTIAL=; PART_DEV=; PART_INO=; META_PARTIAL=; META_DEV=; META_INO=; FINAL=
SRC_LOOP=; VERIFY_LOOP=; OPT_SRC=; OPT_VERIFY=; CHILD=; CHILD_START=
opt_progress() { :; }; sleep() { command sleep 0.02; }
rc=0
if [ "$MODE" = journal_file ]; then
  opt_child /bin/sleep 30 || rc=$?
  [ "$OPT_ERROR" = journal_write ] || exit 90
  stop_opt_child
else
  opt_child /no/such/worker || rc=$?
fi
[ "$rc" != 0 ] || exit 91
opt_details
'''
    for shell in ['sh', 'bash', 'mksh']:
        if not shutil.which(shell): continue
        for mode, message in [('child_exec', 'exec worker child'), ('journal_file', 'exit=13')]:
            run = base/(shell+'-'+mode); run.mkdir()
            (run/'support').write_text(support_script.replace('HELPER', shlex.quote(str(native))))
            (run/'support').chmod(0o755); (run/'child').write_text(child_script)
            result = subprocess.run([shell, str(run/'child')], env=dict(os.environ, BASE=str(run), MODE=mode,
                                    LIBRARY=str(broker/'andr36oid-images.sh')), capture_output=True, text=True, timeout=10)
            assert result.returncode == 0, result
            log = (run/'state/optimize.log').read_text()
            assert message in log, log
        print('PASS:', shell, 'real helper child exec failure and child reaping after journal failure', flush=True)
PY
