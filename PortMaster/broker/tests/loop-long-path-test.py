#!/usr/bin/env python3
"""Model toybox's64-byte label limit with a real inherited open FD."""
from pathlib import Path
import json,os,shlex,subprocess,tempfile
lib=Path(__file__).resolve().parents[1]/'andr36oid-images.sh'
with tempfile.TemporaryDirectory(prefix='a36-loop-fd-') as tmp:
 b=Path(tmp);(b/'bin').mkdir();p=b/('long directory '+('x'*110))/('dArkOSen_R36_11112026'+('y'*100)+'.erofs.img');p.parent.mkdir();p.write_bytes(b'owned read-only lower')
 (b/'bin/losetup').write_text('''#!/usr/bin/python3
import os,sys
from pathlib import Path
backing=sys.argv[-1]
assert len(backing.encode())<64,backing
assert backing=='/proc/self/fd/6' and sys.argv[1:4]==['-r','-o','512'],sys.argv
fd=os.open(backing,os.O_RDONLY);st=os.fstat(fd)
assert st.st_ino==int(os.environ['EXPECTED_INODE'])
# The original path can change after admission; the inherited descriptor pins
# the opened inode for kernel LOOP_SET_FD and later ownership verification.
p=Path(os.environ['ORIGINAL']);p.rename(p.with_suffix('.held'));p.write_text('foreign replacement')
assert os.read(fd,100)==b'owned read-only lower'
print('pinned inode='+str(st.st_ino))
''');(b/'bin/losetup').chmod(0o755)
 script='''set -eu
A36_CONTROL=CONTROL_VALUE
STATE=$A36_CONTROL;ROOT=$A36_CONTROL;IMAGE=
. LIB
exec 6< /dev/null
before=$(readlink /proc/$$/fd/6)
loop_attach_ro /dev/loopMOCK INPUT_VALUE 512
[ "$(readlink /proc/$$/fd/6)" = "$before" ]
'''.replace('CONTROL_VALUE',shlex.quote(str(b/'control'))).replace('LIB',shlex.quote(str(lib))).replace('INPUT_VALUE',shlex.quote(str(p)))
 for shell in ['sh','bash','mksh']:
  if p.with_suffix('.held').exists():p.unlink();p.with_suffix('.held').rename(p)
  env={**os.environ,'PATH':str(b/'bin')+':'+os.environ['PATH'],'EXPECTED_INODE':str(p.stat().st_ino),'ORIGINAL':str(p)}
  subprocess.run([shell,'-c',script],env=env,check=True)
  print('PASS',shell,'>64-byte path through scoped read-only fd6; inode pinned, caller fd6 preserved')
