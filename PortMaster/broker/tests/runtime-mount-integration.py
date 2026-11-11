#!/usr/bin/env python3
"""Real mount/loop tests in a disposable VM, never console or host loop devices.
Set A36_TEST_KERNEL, A36_TEST_GUEST (static ARM64 Soong output) and optionally
A36_TEST_MKFS_EXFAT. Production C and the Android binary are exercised directly.
"""
from pathlib import Path
import os,subprocess,tempfile,gzip,stat,shutil,re
kernel=os.getenv('A36_TEST_KERNEL');guest=os.getenv('A36_TEST_GUEST')
if not kernel or not guest:
 print('SKIP: set A36_TEST_KERNEL and A36_TEST_GUEST for disposable VM mount tests')
 raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix='a36-runtime-vm-') as temporary:
 b=Path(temporary);fixture=Path(__file__).parent/'fixtures/runtime-mount/integration.c'
 subprocess.run(['aarch64-linux-gnu-gcc','-O2','-static','-Wall','-Wextra','-Werror','-Wno-misleading-indentation',str(fixture),'-o',str(b/'init')],check=True)
 tree=b/'runtime';tree.mkdir();(tree/'marker').write_text('runtime works\n')
 (b/'app.c').write_text('int main(void){return 23;}\n')
 subprocess.run(['aarch64-linux-gnu-gcc','-Os','-static',str(b/'app.c'),'-o',str(tree/'runtime-app')],check=True)
 subprocess.run(['mksquashfs',str(tree),str(b/'runtime.squashfs'),'-noappend','-comp','gzip','-processors','1','-no-progress','-no-xattrs'],check=True,stdout=subprocess.DEVNULL)
 disk=b/'card.exfat';disk.touch();disk.open('r+b').truncate(128<<20)
 subprocess.run([os.getenv('A36_TEST_MKFS_EXFAT','mkfs.exfat'),str(disk)],check=True,stdout=subprocess.DEVNULL)
 out=bytearray()
 def add(n,d,m,major=0,minor=0):
  name=n.encode()+b'\0';fields=[1,m,0,0,1,0,len(d),0,0,major,minor,len(name),0];out.extend(b'070701'+b''.join(('%08x'%x).encode() for x in fields)+name);out.extend(b'\0'*(-len(out)%4));out.extend(d);out.extend(b'\0'*(-len(out)%4))
 add('dev',b'',stat.S_IFDIR|0o755);add('dev/console',b'',stat.S_IFCHR|0o600,5,1)
 for name,path,mode in [('init',b/'init',0o755),('guest',Path(guest),0o755),('runtime.squashfs',b/'runtime.squashfs',0o644)]:add(name,path.read_bytes(),stat.S_IFREG|mode)
 # Use unmodified ARM64 util-linux mount/umount/losetup and their real libraries.
 tools=Path(os.getenv('A36_TEST_TOOLS_ROOT','/array/5.10-port/resizer-vm/root'))
 files={}
 def elf_file(src,dst):
  if dst in files:return
  files[dst]=src
  dynamic=subprocess.check_output(['aarch64-linux-gnu-readelf','-d',str(src)],text=True)
  for lib in re.findall(r'\(NEEDED\).*?\[(.*?)\]',dynamic):
   candidates=[tools/'lib/aarch64-linux-gnu'/lib,tools/'usr/lib/aarch64-linux-gnu'/lib]
   found=next(x for x in candidates if x.exists());elf_file(found,'lib/aarch64-linux-gnu/'+lib)
 for name in ['mount','umount','losetup']:
  elf_file(next(x for x in [tools/'bin'/name,tools/'sbin'/name,tools/'usr/bin'/name,tools/'usr/sbin'/name] if x.exists()),'bin/'+name)
 elf_file(tools/'lib/aarch64-linux-gnu/ld-linux-aarch64.so.1','lib/ld-linux-aarch64.so.1')
 dirs={'g'}
 for dst in files:
  dirs.update('g/'+str(x) for x in Path(dst).parents if str(x)!='.')
 for d in sorted(dirs,key=lambda x:(x.count('/'),x)):add(d,b'',stat.S_IFDIR|0o755)
 for dst,src in files.items():add('g/'+dst,src.read_bytes(),stat.S_IFREG|0o755)
 add('TRAILER!!!',b'',0);(b/'ramdisk.gz').write_bytes(gzip.compress(out,mtime=0))
 cmd=['qemu-system-aarch64','-machine','virt,gic-version=2','-cpu','cortex-a35','-smp','4','-m','1024','-nographic','-nic','none','-no-reboot','-monitor','none','-kernel',kernel,'-initrd',str(b/'ramdisk.gz'),'-append','console=ttyAMA0 rdinit=/init loglevel=7 panic=-1 nokaslr initcall_blacklist=system_heap_create,rockchip_drm_init','-drive','if=none,id=card,cache=unsafe,format=raw,file='+str(disk),'-device','virtio-blk-device,drive=card']
 console=Path(os.getenv('A36_TEST_VM_LOG',str(b/'console.log')))
 with console.open('wb') as log:run=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=360)
 text=console.read_text(errors='backslashreplace');print(text)
 assert 'RUNTIME_MOUNT_INTEGRATION_PASS' in text and 'RUNTIME_MOUNT_INTEGRATION_FAIL' not in text
 print('PASS: actual Android guest + real util-linux: general mounts, exFAT/sdcardfs, protected Android loops, private propagation, port/session/unobserved-crash cleanup')
