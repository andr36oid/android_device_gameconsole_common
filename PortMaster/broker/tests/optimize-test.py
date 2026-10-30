"""Real ARM64 EROFS tools; only mounts, loop devices and power are simulated.
Run as root (the metadata helper chroots) with qemu-aarch64 available.
"""
from pathlib import Path
import os, shutil, struct, subprocess, tempfile, shlex
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
  text+='exec '+('' if name=='support' else shlex.quote(shutil.which('qemu-aarch64'))+' ')+shlex.quote(str(tool))+' "$@"\n'
  (b/name).write_text(text);(b/name).chmod(0o755)
 (b/'fail').write_text('#!/bin/sh\nexit 42\n');(b/'fail').chmod(0o755)
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
losetup() { [ "${1:-}" != -f ] || printf '/dev/loopMOCK%s\n' "$RANDOM"; return 0; }
mount() { for target; do :; done; cp -a "$BASE/tree/." "$target/"; }
umount() { return 0; }
MKFS=$BASE/mkfs; FSCK=$BASE/fsck
setprop "$PROP.opt_dispatch_keep" "$2"
case "$3" in mkfs) MKFS=$BASE/fail;; fsck) FSCK=$BASE/fail;; esac
optimize_worker "$1" "$(selection_id abcd-1234 'worker source.img')"
'''.replace('TEST_ROOT',shlex.quote(str(b))).replace('IMAGE_LIB',shlex.quote(str(w/'PortMaster/broker/andr36oid-images.sh')))
 (b/'worker.sh').write_text(script)
 def prop(key):return (b/'props'/('sys.andr36oid.subsystem.'+key)).read_text()
 final=card/'worker source.erofs.img'
 for shell in ['bash','mksh']:
  if not shutil.which(shell):continue
  for n,(keep,fail) in enumerate([('0','mkfs'),('0','fsck'),('1',''),('0','')]):
   source.write_bytes(data)
   for p in card.glob('*.erofs.img*'):p.unlink()
   result=subprocess.run([shell,str(b/'worker.sh'),str(n+1)*16,keep,fail],capture_output=True,text=True,timeout=90)
   if fail:
    assert result.returncode!=0 and source.exists() and not final.exists(),(result,prop('opt_error'))
   else:
    assert result.returncode==0 and final.exists() and Path(str(final)+'.meta').exists(),(result,prop('opt_error'))
    assert source.exists()==(keep=='1'),(shell,keep)
    assert prop('opt_state')=='done'
   assert not list(card.glob('*.partial.*'))
  print('PASS:',shell,'real EROFS build/fsck, keep original, default removal, formatter failure, verifier failure, staging cleanup')
