"""Shared real GPT/EROFS fixture supplied by partition-update-test.py.
Only Android properties, mount ownership and SETUP are simulated here; hashes,
records, guard, copy/readback, BOOT publication and source removal are native.
"""
import os
import shlex

libs = common / 'PortMaster/broker'
casebase = base / 'lifecycle'
casebase.mkdir()
card = casebase / 'card/subsystem/darkosen'
card.mkdir(parents=True)
sourcefile = card / images[1].name
props = casebase / 'props'
props.mkdir()
power = casebase / 'power'
power.mkdir()
for name in ['wake_lock','wake_unlock']:
    (power/name).touch()
wrapper = casebase/'support'
wrapper.write_text('''#!/usr/bin/python3
import os,sys,subprocess
from pathlib import Path
args=sys.argv[1:]
if args[0]=='fsync' and os.environ.get('FAULT')=='readyfail' and args[1].endswith('/release-lifecycle'):
    intent=Path(args[1])/'intent'
    if intent.exists() and 'phase=ready\\n' in intent.read_text() and not Path(HIT).exists():
        Path(HIT).touch();sys.exit(1)
rc=subprocess.call([REAL]+args)
point=args[0]
if point=='fsync' and args[1].endswith('/release-lifecycle'):
    p=Path(args[1])/'intent'
    point='readycrash' if p.exists() and 'phase=ready\\n' in p.read_text() else ''
if rc==0 and point and point==os.environ.get('FAULT') and not Path(HIT).exists():
    Path(HIT).touch();os.kill(os.getppid(),9)
sys.exit(rc)
'''.replace('REAL',repr(str(fixture))).replace('HIT',repr(str(casebase/'hit'))))
wrapper.chmod(0o755)
# Keep the caller's process as the worker parent. The wrapper's child command
# must exec (rather than Python-wait), so PDEATHSIG protects the real copy.
s = wrapper.read_text()
s = s.replace('args=sys.argv[1:]\n', 'args=sys.argv[1:]\nif args[0]=="child":os.execv('+repr(str(fixture))+',['+repr(str(fixture))+']+args)\n')
wrapper.write_text(s)
script = r'''
set -u
BASE=@BASE@
STATE=$BASE/state; ROOT=$BASE/root; IMAGE=; LOG=$BASE/broker.log
A36_CONTROL=$BASE/control; A36_STORAGE_LOCK=$BASE/storage.lock; A36_USB_STATE=$BASE/usb
A36_EROFS_SUPPORT=$BASE/support; A36_POWER_DIR=$BASE/power; A36_RELEASE_WAKE_MS=10; A36_RELEASE_POLL=0.02
A36_OPT_PRIVATE=1; A36_RELEASE_TEST_INLINE=1
export A36_RELEASE_WAKE_MS
mkdir -p "$STATE" "$ROOT"
getprop() { if [ "$1" = ro.build.version.incremental ]; then echo eng.test.20261112.020000; else cat "$BASE/props/$1" 2>/dev/null || :; fi; }
setprop() { printf '%s' "$2" > "$BASE/props/$1"; }
log() { printf '%s\n' "$*" >> "$LOG"; }
. @LIBS@/andr36oid-images.sh
. @LIBS@/andr36oid-release.sh
. @LIBS@/andr36oid-prepared.sh
. @LIBS@/andr36oid-update.sh
image_cards() { printf '%s\taaaa-1111\tfake\trw\n' "$BASE/card"; }
prepared_devices() { PREPARED_DISK=@DISK@; PREPARED_PART=@PART@; PREPARED_BOOTDEV=fake; }
A36_BLOCK_SYS=@SYS@
prepared_pin_manifest() {
  [ "${FAULT:-}" != no_partition ] || return 3
  mkdir -p "$PREPARED_DIR"
  "$SUPPORT" partition-pin @BOOT@/andr36oid-darkosen.json "$PREPARED_DIR/manifest.json"
}
partition_publish_boot() {
  "$SUPPORT" partition-publish "$PART_MANIFEST" "$PREPARED_DISK" "$PREPARED_PART" "$A36_BLOCK_SYS" "$PREPARED_DIR/cache" @BOOT@
}
resolve_selected_image() {
  local choice=${1:-$(getprop "$SELECT_PROP")}
  set_card "$BASE/card" aaaa-1111 fake rw
  if [ "$choice" = @FILEID@ ]; then
    SOURCE=@FILE@; SOURCE_TRANSPORT=file
    RECORD=$(completion_record "$SOURCE") || return 1
    SOURCE_ID=$(field "$RECORD" source_id)
  else
    prepared_devices; mkdir -p "$PREPARED_DIR/cache"; : > "$PREPARED_DIR/progress"
    RECORD=$("$SUPPORT" partition-info @BOOT@/andr36oid-darkosen.json "$PREPARED_DISK" "$PREPARED_PART" "$A36_BLOCK_SYS" "$PREPARED_DIR/cache" "$(getprop ro.build.version.incremental)" "$PREPARED_DIR/progress") || return 1
    SOURCE=$PREPARED_PART; SOURCE_TRANSPORT=partition; SOURCE_ID=$(field "$RECORD" source_id)
    [ -z "$choice" ] || [ "$choice" = "$SOURCE_ID" ] || return 1
  fi
  FAMILY=$SOURCE_ID; GENERATION=$(field "$RECORD" generation); EFFECTIVE=$SOURCE
}
write_catalog() { :; }
publish_resolution() { :; }
publish_setup_state() { :; }
prestage_unmount_locked() { echo unmount >> "$BASE/events"; rm -f "$STATE/prestage.ready"; }
stage_mounts() {
  resolve_selected_image || return 1
  printf 'setup %s\n' "$SOURCE_TRANSPORT" >> "$BASE/events"
  [ "${FAULT:-}" != setupfail ] || [ "$SOURCE_TRANSPORT" = file ] || return 1
  [ -f @FILE@ ] && [ -f @FILE@.meta ] || return 1
  mkdir -p "$CONTROL/ready"
  printf '%s\n' "$EFFECTIVE" > "$CONTROL/ready/$(ready_key)"
  STAGE_COMPLETE=1
}
prestage_park() {
  [ -s "$CONTROL/ready/$(ready_key)" ] && [ -f @FILE@ ] || return 1
  printf 'park %s\n' "$SOURCE_TRANSPORT" >> "$BASE/events"
  touch "$STATE/prestage.ready"
}
case "$1" in
 apply)
  setprop "$PROP.rel_dispatch_job" 0123456789abcdef
  acquire_operation apply-release-to-partition || exit 90
  apply_release_partition_worker 0123456789abcdef @FILEID@ ;;
 recover) acquire_operation scan || exit 91; release_operation ;;
esac
'''
# ID is derived from the release record (same Android catalog encoding).
sourcefile.write_bytes(new)
Path(str(sourcefile)+'.meta').write_bytes(original_record)
record = subprocess.check_output([str(fixture),'record',str(sourcefile),'aaaa-1111'],text=True)
fileid = dict(line.split('=',1) for line in record.splitlines())['source_id']
for key, value in {'BASE':casebase,'LIBS':libs,'DISK':disk,'PART':part,'SYS':sys,'BOOT':boot,'FILE':sourcefile,'FILEID':fileid}.items():
    script=script.replace('@'+key+'@',shlex.quote(str(value)))
worker=casebase/'worker.sh'
worker.write_text(script)
def reset(stage=True):
    for name in ['control','state']:
        shutil.rmtree(casebase/name,ignore_errors=True)
    for p in props.iterdir(): p.unlink()
    (casebase/'hit').unlink(missing_ok=True)
    (casebase/'events').write_text('')
    (casebase/'state').mkdir()
    if stage: (casebase/'state/prestage.ready').touch()
    (props/'persist.andr36oid.subsystem.prestage').write_text('1' if stage else '0')
    sourcefile.write_bytes(new)
    Path(str(sourcefile)+'.meta').write_bytes(original_record)
    part.write_bytes(original_part)
    (boot/'andr36oid-darkosen.json').write_bytes(manifest.read_bytes())
def run_shell(shell, op='apply', fault='', ok=True):
    result=subprocess.run([shell,str(worker),op],text=True,capture_output=True,timeout=60,env={**os.environ,'FAULT':fault})
    if (result.returncode==0)!=ok:
        print('PARTITION LOG', (casebase/'state/partition-update.log').read_text() if (casebase/'state/partition-update.log').exists() else '',flush=True)
    assert (result.returncode==0)==ok,(shell,op,fault,result)
    return result
def prop(key):
    p=props/('sys.andr36oid.subsystem.'+key)
    return p.read_text() if p.exists() else ''
for shell in ['sh','bash','mksh']:
    if not shutil.which(shell): continue
    reset();run_shell(shell)
    assert part.read_bytes()==new+original_part[len(new):]
    assert not sourcefile.exists() and not Path(str(sourcefile)+'.meta').exists()
    assert (casebase/'events').read_text().splitlines()==['unmount','setup partition','park partition']
    assert prop('rel_state')=='done' and prop('rel_transport')=='partition' and prop('rel_cleanup_pending')=='0'
    assert (power/'wake_unlock').read_text().strip()=='a36-release-0123456789abcdef'
    reset();run_shell(shell,fault='setupfail',ok=False)
    assert sourcefile.exists() and prop('rel_error')=='setup_failed'
    assert prop('rel_transport')=='file' and prop('rel_cleanup_pending')=='0'
    assert (casebase/'events').read_text().splitlines()[-1]=='park file'
    reset();run_shell(shell,fault='no_partition')
    assert sourcefile.exists() and prop('rel_fallback')=='no_partition' and prop('rel_state')=='done'
    assert part.read_bytes()==original_part
    print('PASS partition lifecycle',shell,'SETUP before park before file cleanup; setup failure keeps file; absent partition fallback',flush=True)
for point in ['partition-copy','partition-publish','partition-activate','readycrash','release-remove-image']:
    reset(stage=point!='release-remove-image'); run_shell('bash',fault=point,ok=False)
    assert (casebase/'hit').exists(),point
    run_shell('bash','recover')
    committed=point in ['readycrash','release-remove-image']
    assert sourcefile.exists()!=committed,point
    assert prop('rel_transport')==('partition' if committed else 'file'),point
    assert not (casebase/'control/release-lifecycle/intent').exists(),point
    assert prop('rel_cleanup_pending')=='0',point
    guard=casebase/'control/prepared/cache'/(family+'.updating')
    assert guard.exists()!=committed,point
    run_shell('bash','recover')
    assert disk.open('rb').read(32768)==frozen_gpt
    print('PASS partition crash',point,'verified committed partition' if committed else 'guarded partition, retained verified file',flush=True)
reset();run_shell('bash',fault='readyfail',ok=False)
assert sourcefile.exists() and Path(str(sourcefile)+'.meta').exists()
assert prop('rel_error')=='io_error' and prop('rel_cleanup_pending')=='1'
run_shell('bash','recover')
assert not sourcefile.exists() and not (casebase/'control/release-lifecycle/intent').exists()
print('PASS partition failed durable ready write keeps fallback until committed recovery',flush=True)
