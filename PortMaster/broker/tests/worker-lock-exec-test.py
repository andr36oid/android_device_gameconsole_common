#!/usr/bin/env python3
"""Keep both flocks over the shell -> native exec -> worker shell boundary."""
from pathlib import Path
import os,shlex,subprocess,tempfile
common=Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory(prefix='a36-worker-lock-') as d:
 d=Path(d);support=d/'support';subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror',str(common/'PortMaster/erofs-tools/andr36oid-erofs-support.c'),'-o',str(support)],check=True)
 worker=d/'worker.sh';worker.write_text('''set -eu
flock -n 7 7>&7
flock -n 9 9>&9
! flock -n "$A36_STORAGE_LOCK" true
! flock -n "$A36_CONTROL/operation.lock" true
''')
 parent=d/'dispatch.sh';parent.write_text('''set -eu
STATE=STATE_VALUE;ROOT=$STATE;IMAGE=
setprop(){ :; }; getprop(){ :; }
. LIB_VALUE
acquire_operation install-release
exec SUPPORT_VALUE child "$1" WORKER_VALUE 7>&7 9>&9
'''.replace('STATE_VALUE',shlex.quote(str(d/'state'))).replace('LIB_VALUE',shlex.quote(str(common/'PortMaster/broker/andr36oid-images.sh'))).replace('SUPPORT_VALUE',shlex.quote(str(support))).replace('WORKER_VALUE',shlex.quote(str(worker))))
 for shell in ['sh','bash','mksh']:
  env={**os.environ,'A36_CONTROL':str(d/'control'),'A36_STORAGE_LOCK':str(d/'storage.lock'),'A36_USB_STATE':str(d/'usb')}
  subprocess.run([shell,str(parent),__import__('shutil').which(shell)],env=env,check=True)
  # Close-on-exec catches the old callsite for mksh; this demonstrates why the
  # explicit redirections matter rather than merely asserting source text.
  if shell=='mksh':
   bad=d/'bad.sh';bad.write_text(parent.read_text().replace(' 7>&7 9>&9\n','\n'))
   assert subprocess.run([shell,str(bad),__import__('shutil').which(shell)],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode
  print('PASS',shell,'both operation/storage locks retained through native exec and worker shell')
 broker=(common/'PortMaster/broker/andr36oid-subsystem.sh').read_text()
 for text in ['install-release-worker "$job" "$request" 6<&6 7>&7 9>&9','optimize-worker "$job" "$pinned" 7>&7 9>&9']:assert text in broker,text
