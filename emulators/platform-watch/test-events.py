#!/usr/bin/env python3
"""Native dispatcher versus the original Go event loop on identical card fixtures.
No device/mount access. Set A36_WATCH_GO_REFERENCE to the compiled setup test binary.
"""
from pathlib import Path
import json,os,signal,subprocess,tempfile,time

here=Path(__file__).resolve().parent
reference=os.environ['A36_WATCH_GO_REFERENCE']
results=[]

def write(p,s):
 p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
def events(root):
 try:return (root/'events').read_text().splitlines()
 except FileNotFoundError:return []
def count(root,kind):return events(root).count(kind)
def until(pred,timeout=6):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  if pred():return
  time.sleep(.025)
 raise AssertionError('event deadline expired')
def fixture(root):
 (root/'mnt/media_rw/AAAA-0001/fds').mkdir(parents=True)
 write(root/'system/etc/gameconsole/daijishou/daijishou-seed.txt',(here.parent/'daijishou/daijishou-seed.txt').read_text())
 write(root/'properties/sys.andr36oid.easyroms.ready','1')
 write(root/'properties/sys.andr36oid.easyroms.id','public:179,7')
 write(root/'proc/mounts',f'/dev/block/vold/public:179,7 {root}/mnt/media_rw/AAAA-0001 exfat rw 0 0\n')
 write(root/'system/bin/gameconsole-setup',f'''#!/usr/bin/python3
from pathlib import Path
import os,sys,time
r=Path({str(root)!r})
def emit(s):
 with (r/'events').open('a') as f:f.write(s+'\\n')
if sys.argv[1]=='watch-config':
 if os.getenv('A36_WATCH_BAD_CONFIG'):print('unsupported config')
 else:sys.stdout.write((r/'watch-config').read_text())
elif sys.argv[1]=='watch-platforms':
 emit('legacy')
 if os.getenv('A36_PLATFORM_WATCH_LEGACY')!='1':raise Exception('missing recursion guard')
 time.sleep(60)
elif sys.argv[1]=='reconcile-platforms':
 try:(r/'job.lock').mkdir()
 except FileExistsError:emit('overlap');raise SystemExit(22)
 try:emit('scan');time.sleep(.04)
 finally:(r/'job.lock').rmdir()
else:raise Exception(sys.argv)
''')
 (root/'system/bin/gameconsole-setup').chmod(0o755)
 write(root/'system/etc/andr36oid-subsystem.sh',f'#!/bin/sh\necho unstage >> "{root}/events"\nrm -f "{root}/data/local/tmp/portmaster/session.mounts"\n')

def stop(p):
 if p.poll() is None:
  p.terminate()
  try:p.wait(timeout=3)
  except subprocess.TimeoutExpired:p.kill();p.wait()

with tempfile.TemporaryDirectory(prefix='platform-watch-events-') as tmp:
 tmp=Path(tmp);native=tmp/'watch'
 subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror','-DPLATFORM_WATCH_HOST_TEST',str(here/'platform-watch.c'),'-o',str(native)],check=True)
 # Compile the actual production branch too; only libcutils declarations are a
 # host header fixture. The real Android link/build remains separately reported.
 write(tmp/'include/cutils/properties.h','#define PROPERTY_VALUE_MAX 92\nint property_get(const char *, char *, const char *);\n')
 subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-I'+str(tmp/'include'),'-c',str(here/'platform-watch.c'),'-o',str(tmp/'production.o')],check=True)
 roots=[tmp/'go',tmp/'native']
 for root in roots:fixture(root)
 log=(tmp/'reference.log').open('w')
 go=subprocess.Popen([reference,'-test.run=^TestOriginalGoWatcherReferenceProcess$','-test.v'],env=dict(os.environ,A36_WATCH_REFERENCE_ROOT=str(roots[0])),stdout=log,stderr=subprocess.STDOUT)
 c=None
 try:
  until(lambda:(roots[0]/'watch-config').exists())
  write(roots[1]/'watch-config',(roots[0]/'watch-config').read_text())
  c=subprocess.Popen([str(native),str(roots[1])],env=dict(os.environ,A36_WATCH_TEST_MS='100'))
  until(lambda:all(count(r,'scan')>=1 for r in roots))
  def expect(action,kind='scan',timeout=6):
   before=[count(r,kind) for r in roots]
   for root in roots:action(root)
   until(lambda:all(count(r,kind)>b for r,b in zip(roots,before)),timeout)
  def burst(r):
   for i in range(4):write(r/'mnt/media_rw/AAAA-0001/fds/Game.FDS',str(i));time.sleep(.04)
  before=[count(r,'scan') for r in roots];expect(burst);time.sleep(.3)
  assert [count(r,'scan') for r in roots]==[b+1 for b in before],'copy burst not coalesced'
  before=[count(r,'scan') for r in roots]
  for root in roots:
   write(root/'mnt/media_rw/AAAA-0001/fds/README.txt','not a ROM')
   write(root/'mnt/media_rw/AAAA-0001/fds/.hidden/Other.fds','hidden')
   write(root/'mnt/media_rw/AAAA-0001/not-a-system/Game.fds','unknown folder')
  time.sleep(2.4);assert [count(r,'scan') for r in roots]==before,'ignored files caused scans'
  expect(lambda r:(r/'mnt/media_rw/AAAA-0001/fds/Game.FDS').rename(r/'mnt/media_rw/AAAA-0001/fds/Game.tmp'))
  expect(lambda r:(r/'mnt/media_rw/AAAA-0001/fds/Game.tmp').rename(r/'mnt/media_rw/AAAA-0001/fds/Game.fds'))
  expect(lambda r:write(r/'mnt/media_rw/AAAA-0001/fds/sub/Game.fds','nested'))
  expect(lambda r:(r/'mnt/media_rw/AAAA-0001/fds/Game.fds').unlink())
  def new_card(r):
   write(r/'mnt/media_rw/BBBB-0002/fds/Card2.fds','new card')
   write(r/'proc/mounts',f'/dev/block/vold/public:179,7 {r}/mnt/media_rw/BBBB-0002 exfat rw 0 0\n')
  expect(new_card,timeout=20)
  for root in roots:
   write(root/'data/local/tmp/portmaster/session.mounts','resident stage')
   write(root/'properties/persist.andr36oid.subsystem.prestage','0')
   write(root/'properties/sys.andr36oid.subsystem.session','running')
  time.sleep(.4);assert all(count(r,'unstage')==0 for r in roots),'active guest was evicted'
  expect(lambda r:write(r/'properties/sys.andr36oid.subsystem.session','ended'),kind='unstage',timeout=20)
  until(lambda:all(not (r/'data/local/tmp/portmaster/session.mounts').exists() for r in roots))
  assert not any('overlap' in events(r) for r in roots),'workers overlapped'
  assert events(roots[0])==events(roots[1]),(events(roots[0]),events(roots[1]))
  smaps=Path(f'/proc/{c.pid}/smaps_rollup').read_text()
  threads=len(list(Path(f'/proc/{c.pid}/task').iterdir()))
  results.append(dict(case='original Go versus native',events=events(roots[0]),standing_native_host_smaps=smaps,threads=threads,real_android_measurement=False))
 finally:
  stop(go)
  if c:stop(c)
  log.close()
 # Explicit off and unsupported config must exec the old engine without a loop.
 for reason in ('off','config'):
  root=tmp/('fallback-'+reason);fixture(root);write(root/'watch-config',(roots[0]/'watch-config').read_text())
  if reason=='off':write(root/'properties/persist.andr36oid.ram.native_platform_watch','0')
  env=dict(os.environ,A36_WATCH_TEST_MS='100')
  if reason=='config':env['A36_WATCH_BAD_CONFIG']='1'
  p=subprocess.Popen([str(native),str(root)],env=env)
  try:until(lambda:count(root,'legacy')==1);time.sleep(.2);assert count(root,'legacy')==1
  finally:stop(p)
  results.append(dict(case='legacy fallback '+reason,status='PASS'))
 root=tmp/'watch-limit';fixture(root);write(root/'watch-config',(roots[0]/'watch-config').read_text())
 p=subprocess.Popen([str(native),str(root)],env=dict(os.environ,A36_WATCH_TEST_MS='100',A36_WATCH_TEST_INOTIFY_FAIL='1'))
 try:
  until(lambda:count(root,'scan')>=2);time.sleep(.5)
  assert 2<=count(root,'scan')<=5,'watch-limit fallback spins or never rescans'
  assert count(root,'legacy')==0
 finally:stop(p)
 results.append(dict(case='bounded watch-limit fallback',status='PASS'))
 # ready=1 with no id must not accidentally watch the historical p7 fallback.
 root=tmp/'no-card';fixture(root);write(root/'watch-config',(roots[0]/'watch-config').read_text())
 write(root/'properties/sys.andr36oid.easyroms.id','')
 p=subprocess.Popen([str(native),str(root)],env=dict(os.environ,A36_WATCH_TEST_MS='100'))
 try:
  time.sleep(.4);assert count(root,'scan')==0,'invented a games card'
  write(root/'properties/sys.andr36oid.easyroms.id','public:179,7')
  until(lambda:count(root,'scan')==1)
  write(root/'properties/sys.andr36oid.easyroms.id','');time.sleep(.3)
  before=count(root,'scan');write(root/'mnt/media_rw/AAAA-0001/fds/Detached.fds','ignored after removal')
  time.sleep(.3);assert count(root,'scan')==before,'removed card still watched'
 finally:stop(p)
 results.append(dict(case='no-card ready identity and late mount/remove',status='PASS'))
 result=dict(status='PASS',cases=results)
 if os.environ.get('A36_WATCH_REPORT'):Path(os.environ['A36_WATCH_REPORT']).write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps(result,indent=2))
