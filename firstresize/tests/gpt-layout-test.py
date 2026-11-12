#!/usr/bin/env python3
"""Audit real shipped GPT bytes and hostile/mistaken slot rewrites read-only."""
from pathlib import Path
import hashlib,os,shutil,struct,subprocess,tempfile,uuid,zlib
common=Path(__file__).resolve().parents[2];source=common/'PortMaster/erofs-tools/andr36oid-erofs-support.c'
reference=Path(os.environ.get('A36_GPT_REFERENCE','/array/5.10-port/image-round7-network28-20261111/andr36oid-20261111-2026-rk3326.img'))
with reference.open('rb') as f:prefix=f.read(34*512)
base_h=bytearray(prefix[512:1024]);base_table=bytearray(prefix[1024:34*512]);assert base_h[:8]==b'EFI PART'
with tempfile.TemporaryDirectory(prefix='a36-layout-',dir='/ramdisk') as tmp:
 b=Path(tmp);disk=b/'mmcblk9';sys=b/'sys';sys.mkdir();helper=b/'support'
 subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror','-DA36_PARTITION_FIXTURE',str(source),'-o',str(helper)],check=True)
 capacity=64000000000;last=capacity//512-34
 def publish(table,expanded=False):
  h=bytearray(base_h)
  if expanded:struct.pack_into('<Q',h,32,capacity//512-1);struct.pack_into('<Q',h,48,last)
  struct.pack_into('<I',h,88,zlib.crc32(table));h[16:20]=bytes(4);struct.pack_into('<I',h,16,zlib.crc32(h[:92]))
  with disk.open('wb') as f:f.truncate(capacity);f.seek(512);f.write(h);f.write(table)
  for p in sys.iterdir():shutil.rmtree(p)
  for i in range(128):
   e=table[i*128:(i+1)*128]
   if e[:16]==bytes(16):continue
   first,end=struct.unpack_from('<QQ',e,32);name=e[56:128].decode('utf-16le').split('\0',1)[0];p=sys/f'mmcblk9p{i+1}';p.mkdir();(p/'start').write_text(str(first));(p/'size').write_text(str(end-first+1));(p/'partition').write_text(str(i+1));(p/'uevent').write_text(f'MAJOR=179\nMINOR={i+1}\nPARTNAME={name}\n')
 def audit(good=True,created=False,phrase=''):
  with disk.open('rb') as f:before=hashlib.sha256(f.read(34*512)).digest()
  result=subprocess.run([str(helper),'layout-audit',str(disk),str(sys),*(['created'] if created else [])],capture_output=True,text=True)
  with disk.open('rb') as f:after=hashlib.sha256(f.read(34*512)).digest()
  assert before==after,'read-only audit mutated GPT'
  assert (result.returncode==0)==good,(result.stdout,result.stderr)
  assert phrase in result.stderr,(phrase,result.stderr)
 def prepared(guest=True,apps=True,hide=True):
  t=bytearray(base_table);t[11*128:12*128]=bytes(128)
  def put(n,label,start,end,basic=False):
   e=bytearray(128);e[:16]=base_table[:16] if basic else base_table[128:144];e[16:32]=uuid.uuid5(uuid.NAMESPACE_DNS,label).bytes_le;struct.pack_into('<QQ',e,32,start,end);e[56:56+len(label)*2]=label.encode('utf-16le');t[(n-1)*128:n*128]=e
  tail=(last+1-5*1024**3//512)//2048*2048 if guest else last+1
  end=65349631;apps_start=(end+1-4194304)//2048*2048
  put(6,'userdata',5701632,apps_start-1 if apps else end)
  if hide:struct.pack_into('<Q',t,5*128+48,1<<63)
  if apps:put(13,'AUTOAPK',apps_start,end,True)
  put(7,'EASYROMS',end+1,tail-1,True)
  if guest:put(14,'DARKOS',tail,last)
  return t
 publish(base_table);audit() # raw image backup GPT still at the image's old end
 for guest,apps in [(False,False),(False,True),(True,True),(True,False)]:publish(prepared(guest,apps),True);audit()
 publish(prepared(True,True,False),True);audit() # attribute63 absence alone is supported
 compact=bytearray(16384);used=[base_table[i:i+128] for i in range(0,16384,128) if base_table[i:i+16]!=bytes(16)]
 for i,e in enumerate(used):compact[i*128:(i+1)*128]=e
 publish(compact);audit(False,phrase='GPT p6 expected userdata, got vendor')
 for number,label in [(6,'vendor'),(7,'metadata'),(13,'metadata')]:
  t=prepared();off=(number-1)*128+56;t[off:off+72]=bytes(72);t[off:off+len(label)*2]=label.encode('utf-16le');publish(t,True);audit(False)
 for number in [6,7,13]:
  t=prepared();off=(number-1)*128;t[off:off+16]=base_table[:16] if number==6 else base_table[128:144];publish(t,True);audit(False,phrase='GPT type changed')
 publish(prepared(),True);(sys/'mmcblk9p6/uevent').write_text('MAJOR=179\nMINOR=6\n');audit(False,phrase='no PARTNAME');audit(created=True)
 (sys/'mmcblk9p6/uevent').write_text('PARTNAME=vendor\n');audit(False,created=True,phrase='PARTNAME mismatch')
 publish(prepared(),True);(sys/'mmcblk9p6/start').write_text('5701633');audit(False,phrase='start mismatch')
 publish(base_table);p=sys/'mmcblk9p6';p.mkdir();(p/'partition').write_text('6');audit(False,phrase='absent from GPT')
 print('PASS real raw/prepared/post-reclaim GPTs, compacted #12 p6 vendor, names/types6/7/13, optional hide flag, kernel names/geometry/stale holes; GPT never changed')
 # Compile the same warning text constants the native renderer includes.
 cpp=b/'warning.cpp';header=Path('/andr36oid/src/frameworks/base/cmds/bootanimation/StorageLayoutWarning.h')
 cpp.write_text('#include <cassert>\n#include <string>\n#include "'+str(header)+'"\nint main(){using namespace android;assert(std::string(kStorageLayoutErrorProp)=="sys.andr36oid.storage_layout_error");assert(std::string(kLayoutWarnTitle)=="Partition table changed");assert(std::string(kLayoutWarnBody[1]).find("andr36oid SD flasher or a raw image writer")!=std::string::npos);assert(std::string(kLayoutWarnLast).find("Do not format")!=std::string::npos);}\n')
 subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror',str(cpp),'-o',str(b/'warning')],check=True);subprocess.run([str(b/'warning')],check=True)
 print('PASS native warning text/property contract')
