#!/usr/bin/env python3
"""Exercise final-image capacity rules without filling or mounting a disk."""
from pathlib import Path
import json,os,subprocess,tempfile
source=Path(__file__).resolve().parents[2]/'erofs-tools/andr36oid-erofs-support.c'
with tempfile.TemporaryDirectory(prefix='a36-release-space-') as tmp:
 d=Path(tmp);shim=d/'space.c';binary=d/'support'
 shim.write_text(r'''
#define _GNU_SOURCE
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <stdlib.h>
#include <string.h>
int __wrap_statfs64(const char *p,struct statfs64 *s) {
 (void)p;memset(s,0,sizeof(*s));s->f_type=getenv("FAT")?0x4d44:0xef53;return 0;
}
int __wrap_statvfs64(const char *p,struct statvfs64 *s) {
 (void)p;memset(s,0,sizeof(*s));s->f_frsize=4096;s->f_bavail=strtoull(getenv("FREE"),0,10)/4096;return 0;
}
''')
 subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror',str(source),str(shim),'-Wl,--wrap=statfs64','-Wl,--wrap=statvfs64','-o',str(binary)],check=True)
 job='0123456789abcdef'
 r=dict(format=1,job=job,darkosen='11112026',revision=1,minimum_andr36oid='',image=dict(name='release.erofs.img',size=4096,sha256='0'*64),download=dict(compression='xz',size=1,sha256='0'*64,parts=[dict(name='part.xz',size=1,sha256='0'*64)]))
 def call(size,free,fat=False):
  r['image']['size']=size;p=d/'request.json';p.write_text(json.dumps(r))
  env={k:v for k,v in os.environ.items() if k!='FAT'};env['FREE']=str(free)
  if fat:env['FAT']='1'
  out=subprocess.run([str(binary),'release-info',str(p),job,str(d),'abcd-1234','eng.test.20261111.220000'],env=env,text=True,capture_output=True)
  info=dict(line.split('=',1) for line in out.stdout.splitlines())
  return out,info
 for size in [4096,5*1024**3,20*1024**3]:
  p,info=call(size,40*1024**3)
  assert p.returncode==0,p
  head=max(64*1024**2,(size+99)//100);head=(head+4095)//4096*4096
  assert int(info['required'])==(size+4095)//4096*4096+head
  assert int(info['headroom'])==head and int(info['available'])==40*1024**3
 p,info=call(2**32,40*1024**3,True)
 assert p.returncode and 'file-size limit' in p.stderr and int(info['file_limit'])==2**32-1
 p,info=call(5*1024**3,5*1024**3)
 assert p.returncode and 'release free space' in p.stderr
 p,info=call(4096,64*1024**2+4095)
 assert p.returncode and 'release free space' in p.stderr
print('PASS release capacity: >4GiB counters, 1%/64MiB rounded headroom, FAT32 limit, no-space boundary')
