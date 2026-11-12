#!/usr/bin/env python3
"""Compare native and shipped ARM64 pipeline/checksum behavior, real shipped XZ."""
from pathlib import Path
import hashlib,json,lzma,random,shlex,shutil,struct,subprocess,tempfile,time
common=Path(__file__).resolve().parents[3]
tools=common/'PortMaster/erofs-tools'
qemu=shutil.which('qemu-aarch64') or shutil.which('qemu-aarch64-static')
assert qemu
# The system already ships a static XZ Utils binary, not a toybox approximation.
shipped=Path('/array/5.10-port/image-round6-20261111/erofs-readback/system/bin/unxz')
with tempfile.TemporaryDirectory(prefix='a36-release-stream-') as d:
 d=Path(d);native=d/'native'
 subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror',str(tools/'andr36oid-erofs-support.c'),'-o',str(native)],check=True)
 decoder=d/'unxz';decoder.write_text('#!/bin/sh\nexec '+shlex.quote(qemu)+' '+shlex.quote(str(shipped))+' "$@"\n');decoder.chmod(0o755)
 data=bytearray(random.Random(917).randbytes(2**20+8192))
 # Only the bounded-superblock probe runs here; full real mkfs/fsck/root checks
 # are covered by release-test.py. This exercises many SHA/pipe blocks.
 struct.pack_into('<I',data,1024,0xe0f5e1e2);data[1036]=12;struct.pack_into('<I',data,1060,1);struct.pack_into('<I',data,1104,1)
 data[1072:1088]=bytes(range(16));compressed=lzma.compress(data);parts=[]
 for n,chunk in enumerate([compressed[:500003],compressed[500003:900017],compressed[900017:]],1):
  name=f'part.xz.{n:03d}';(d/name).write_bytes(chunk);parts.append(dict(name=name,size=len(chunk),sha256=hashlib.sha256(chunk).hexdigest()))
 job='abcdef0123456789';name='stream.erofs.img'
 request=dict(format=1,job=job,darkosen='11112026',revision=1,minimum_andr36oid='',image=dict(name=name,size=len(data),sha256=hashlib.sha256(data).hexdigest()),download=dict(compression='xz',size=len(compressed),sha256=hashlib.sha256(compressed).hexdigest(),parts=parts))
 r=d/'request.json';r.write_text(json.dumps(request))
 for label,cmd,unxz in [('host',[str(native)],shutil.which('unxz')),('arm64',[qemu,str(tools/'andr36oid-erofs-support')],str(decoder))]:
  dest=d/name;dest.unlink(missing_ok=True)
  identity=dict(line.split('=',1) for line in subprocess.check_output(cmd+['reserve',str(dest)],text=True).splitlines())
  for p in ['verified','written']:(d/p).write_text('0\n')
  subprocess.run(cmd+['release-unpack',str(r),job,str(d),str(dest),identity['device'],identity['inode'],str(d/'verified'),str(d/'written'),unxz],check=True,timeout=60)
  assert dest.read_bytes()==data
  assert int((d/'verified').read_text())==len(compressed) and int((d/'written').read_text())==len(data)
  meta=subprocess.check_output(cmd+['release-record',str(r),job,'abcd-1234',str(dest)])
  Path(str(dest)+'.meta').write_bytes(meta)
  rec=subprocess.check_output(cmd+['record',str(dest),'abcd-1234'],text=True)
  assert 'origin=release\n' in rec and hashlib.sha256(b'a36-release-v1:11112026:1:'+hashlib.sha256(data).hexdigest().encode()).hexdigest() in rec
  print('PASS',label,'multi-block streamed SHA, ordered split joins, decoder, file flush, v2 standalone record')

 # Parent death must also stop the native producer/decoder; no broker shell
 # process group or later host-wide sweep is needed.
 (d/'pause.c').write_text('#include <unistd.h>\nint main(void){for(;;)pause();}\n')
 subprocess.run(['cc',str(d/'pause.c'),'-o',str(d/'pause')],check=True)
 dest=d/'death.partial';identity=dict(line.split('=',1) for line in subprocess.check_output([str(native),'reserve',str(dest)],text=True).splitlines())
 p=subprocess.Popen([str(native),'release-unpack',str(r),job,str(d),str(dest),identity['device'],identity['inode'],str(d/'verified'),str(d/'written'),str(d/'pause')],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 kids=[]
 try:
  for _ in range(100):
   kids=Path(f'/proc/{p.pid}/task/{p.pid}/children').read_text().split()
   if len(kids)==2:break
   time.sleep(.01)
  assert len(kids)==2,kids
  p.kill();p.wait(timeout=5)
  for _ in range(100):
   live=[]
   for kid in kids:
    try:
     if Path(f'/proc/{kid}/stat').read_text().rsplit(') ',1)[1][0] not in 'ZX':live.append(kid)
    except FileNotFoundError:pass
   if not live:break
   time.sleep(.01)
  assert not live,live
  print('PASS native pipeline parent death reclaims both producer and decoder')
 finally:
  if p.poll() is None:p.kill();p.wait(timeout=5)
