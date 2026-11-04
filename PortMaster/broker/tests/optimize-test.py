"""Real ARM64 EROFS tools; only mounts, loop devices and power are simulated.
Run as root (the metadata helper chroots) with qemu-aarch64 available.
"""
from pathlib import Path
import os, shutil, struct, subprocess, tempfile, shlex, re
w=Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory(prefix='a36-optimize-') as tmp:
 b=Path(tmp); card=b/'card/subsystem/darkosen';card.mkdir(parents=True)
 tree=b/'tree'
 for name in ['etc/passwd','etc/group','bin/sh','sbin/init','usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1','usr/bin/emulationstation/emulationstation']:
  p=tree/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('fixture\n');p.chmod(0o755)
 source=card/'worker source.img'
 data=bytearray(512+131072);data[510:512]=b'\x55\xaa';data[450]=0x83;struct.pack_into('<II',data,454,1,256)
 x=512+65536;data[x+32:x+48]=bytes(range(16));struct.pack_into('<Q',data,x+48,65536);data[x+64:x+72]=b'_BHRfS_M';struct.pack_into('<Q',data,x+72,1);struct.pack_into('<Q',data,x+112,131072);struct.pack_into('<Q',data,x+136,1)
 subprocess.run(['cc','-O2',str(w/'PortMaster/erofs-tools/andr36oid-erofs-support.c'),'-o',str(b/'real-support')],check=True)
 for name in ['support','mkfs','fsck']:
  tool=b/'real-support' if name=='support' else w/'PortMaster/erofs-tools'/('mkfs.erofs' if name=='mkfs' else 'fsck.erofs')
  text='#!/bin/sh\n'
  if name=='support':text+='case "$1" in detach-owned) exit 0;; esac\n'
  if name=='fsck':text+='[ "$#" = 3 ] && [ "$1" = -d0 ] && [ "$2" = --extract ] || exit 66\n'
  text+='exec '+('' if name=='support' else shlex.quote(shutil.which('qemu-aarch64'))+' ')+shlex.quote(str(tool))+' "$@"\n'
  (b/name).write_text(text);(b/name).chmod(0o755)
 # More than both former truncation limits: the first and last diagnostics
 # must survive opt_fail/opt_cleanup and remain in the UI's optimize.log.
 failure_output='FIRST actual child error\n'+'diagnostic detail\n'*70000+'LAST actual child error\n'
 (b/'fail').write_text('#!/usr/bin/python3\nimport sys\nsys.stderr.write('+repr(failure_output)+')\nsys.exit(42)\n');(b/'fail').chmod(0o755)
 script=r'''
set -u
BASE=TEST_ROOT
STATE=$BASE/state;ROOT=$BASE/root;IMAGE=;LOG=$BASE/log
A36_CONTROL=$BASE/control;A36_STORAGE_LOCK=$BASE/storage.lock;A36_USB_STATE=$BASE/usb
A36_EROFS_SUPPORT=$BASE/support;A36_OPT_PRIVATE=1
mkdir -p "$BASE/props" "$STATE"
getprop() { cat "$BASE/props/$1" 2>/dev/null || true; }
setprop() { printf '%s' "$2" > "$BASE/props/$1"; }
log() { printf '%s\n' "$*" >> "$LOG"; }
. IMAGE_LIB
image_cards() { printf '%s\tabcd-1234\tfake\trw\n' "$BASE/card"; }
power_hold() { WAKE=; }
losetup() { [ "${1:-}" != -f ] || printf '/dev/loopMOCK%s\n' "$$"; return 0; }
mount() { for target; do :; done; cp -a "$BASE/tree/." "$target/"; }
umount() { return 0; }
MKFS=$BASE/mkfs; FSCK=$BASE/fsck
setprop "$PROP.opt_dispatch_keep" "$2"
case "$3" in mkfs) MKFS=$BASE/fail;; fsck) FSCK=$BASE/fail;; esac
optimize_worker "$1" "$(selection_id abcd-1234 'worker source.img')"
'''.replace('TEST_ROOT',shlex.quote(str(b))).replace('IMAGE_LIB',shlex.quote(str(w/'PortMaster/broker/andr36oid-images.sh')))
 (b/'worker.sh').write_text(script)
 # Same mocked environment, but exercise recovery after the worker has exited.
 recovery=script[:script.index('optimize_worker "$1"')]+r'''
acquire_operation scan || exit 90
reconcile_optimize "${4:-0}"
rc=$?
release_operation
# Simulate transient work-directory cleanup: refresh must depend only on STATE.
OPT_DIR=$BASE/removed-workdir
opt_refresh_log
exit "$rc"
'''
 (b/'recover.sh').write_text(recovery)
 def prop(key):return (b/'props'/('sys.andr36oid.subsystem.'+key)).read_text()
 final=card/'worker source.erofs.img'
 for shell_index,shell in enumerate(['sh','bash','mksh']):
  if not shutil.which(shell):continue
  for n,(keep,fail) in enumerate([('0','mkfs'),('0','fsck'),('0','fsck'),('1',''),('0','')]):
   source.write_bytes(data)
   for p in [final,Path(str(final)+'.meta')]:
    if p.exists():p.unlink()
   stale=list(card.glob('*.partial.*'))
   job=f'{shell_index*16+n+1:016x}'
   result=subprocess.run([shell,str(b/'worker.sh'),job,keep,fail],capture_output=True,text=True,timeout=90)
   assert all(not p.exists() for p in stale),'next job must reclaim earlier owned partials'
   # Extra allocation diagnostics must not change the named-field wire parser
   # or confuse the progress denominator with the additional space reserve.
   log=(b/'state/optimize.log').read_text()
   estimate=re.search(r'estimate: apparent_bytes=(\d+) allocated_bytes=(\d+) output_bound=(\d+) required=(\d+)',log)
   assert estimate,log
   apparent,allocated,bound,required=map(int,estimate.groups())
   assert apparent>0 and allocated>0 and int(prop('opt_total'))==bound
   assert int(prop('opt_required'))==required==bound+512*1024**2
   assert int(prop('opt_available'))>=required
   if fail:
    assert result.returncode!=0 and source.exists() and not final.exists(),(result,prop('opt_error'))
    assert (b/'state/optimize-child.log').read_text()==failure_output
    assert failure_output in log and 'child failed: exit=42' in log
    assert 'LAST actual child error' in log[-65536:] and 'child failed: exit=42' in log[-65536:],'UI tail must include error and exit status'
   else:
    assert result.returncode==0 and final.exists() and Path(str(final)+'.meta').exists(),(result,prop('opt_error'))
    assert source.exists()==(keep=='1'),(shell,keep)
    assert prop('opt_state')=='done'
   partials=list(card.glob('*.partial.*'))
   if fail=='fsck':
    assert prop('opt_error')=='verify_failed' and len(partials)==1,(partials,prop('opt_error'))
    partial=partials[0]
    assert partial.stat().st_size>0 and 'preserve_partial=1\n' in (b/'control/optimize/journal').read_text()
    recovered=subprocess.run([shell,str(b/'recover.sh'),'unused',keep,fail],capture_output=True,text=True,timeout=30)
    assert recovered.returncode==0 and partial.exists(),recovered
    assert failure_output in (b/'state/optimize.log').read_text(),'full stderr survives cleanup/recovery/log refresh'
    assert (b/'state/optimize-child.log').read_text()==failure_output
    # Replacing the path must never authorize deleting a different inode. A
    # new optimize attempt must stop rather than accumulate another partial.
    held=b/'held-partial';partial.rename(held);partial.write_text('foreign inode sentinel')
    guarded=subprocess.run([shell,str(b/'recover.sh'),'unused',keep,fail,'1'],capture_output=True,text=True,timeout=30)
    assert guarded.returncode!=0 and partial.read_text()=='foreign inode sentinel',guarded
    partial.unlink();held.rename(partial)
   else:
    assert not partials,partials
  print('PASS:',shell,'real EROFS full decode/fsck, original policy, full persistent child stderr after cleanup, verify partial retention/scans, next-job reclaim, inode guard')
