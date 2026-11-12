#!/usr/bin/env python3
"""GPT/EROFS/cache tests on sparse regular fixtures, never a real block device.
Only the fixture binary is built with A36_PARTITION_FIXTURE; the shipped binary
is also compiled and must reject these exact regular objects.
"""
from pathlib import Path
import copy,hashlib,json,os,shutil,struct,subprocess,tempfile,zlib
common=Path(__file__).resolve().parents[3];source=common/'PortMaster/erofs-tools/andr36oid-erofs-support.c'
with tempfile.TemporaryDirectory(prefix='a36-partition-',dir='/ramdisk') as temp:
 b=Path(temp);test=b/'fixture-helper';production=b/'production-helper'
 for target,extra in [(test,['-DA36_PARTITION_FIXTURE']),(production,[])]:
  subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror',*extra,str(source),'-o',str(target)],check=True)
 disk=b/'mmcblk9';part=b/'mmcblk9p14';sys=b/'sys';pdir=sys/part.name;pdir.mkdir(parents=True);cache=b/'cache';cache.mkdir()
 n=16777216;first=(n-33-16384)//2048*2048;last=n-34
 (pdir/'partition').write_text('14\n');(pdir/'start').write_text(str(first));(pdir/'size').write_text(str(last-first+1))
 entries=bytearray(16384)
 def entry(index,name,start,end):
  e=bytearray(128);e[:16]=bytes.fromhex('af3dc60f838472478e793d69d8477de4');e[16:32]=hashlib.md5(name.encode()).digest();struct.pack_into('<QQ',e,32,start,end);e[56:56+len(name)*2]=name.encode('utf-16le');entries[index*128:(index+1)*128]=e
 entry(0,'BOOT',32768,557055);entry(1,'system',1081344,5701008);entry(5,'userdata',5701632,first-1);entry(13,'DARKOS',first,last)
 def gpt():
  header=bytearray(512);header[:8]=b'EFI PART';struct.pack_into('<II',header,8,0x10000,92);struct.pack_into('<QQQQ',header,24,1,n-1,34,n-34);header[56:72]=bytes(range(1,17));struct.pack_into('<QIII',header,72,2,128,128,zlib.crc32(entries));struct.pack_into('<I',header,16,zlib.crc32(header[:92]))
  with disk.open('wb') as f:f.truncate(n*512);f.seek(512);f.write(header);f.write(entries)
 gpt()
 tree=b/'tree'
 for name in ['etc/passwd','etc/group','bin/sh','sbin/init','usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1','usr/bin/emulationstation/emulationstation']:
  p=tree/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('fixture\n');p.chmod(0o755)
 qemu=shutil.which('qemu-aarch64') or shutil.which('qemu-aarch64-static');image=b/'root.erofs'
 subprocess.run([qemu,str(common/'PortMaster/erofs-tools/mkfs.erofs'),'-b4096','-zlz4hc,9','-E^xattr-name-filter','--quiet',str(image),str(tree)],check=True,stdout=subprocess.DEVNULL)
 content=image.read_bytes()
 with part.open('wb') as f:f.write(content);f.seek((last-first+1)*512-1);f.write(b'X')
 good=dict(format=1,darkosen='11112026',revision=1,minimum_andr36oid='',image=dict(name='dArkOSen_R36_11112026.erofs.img',size=len(content),sha256=hashlib.sha256(content).hexdigest()))
 manifest=b/'manifest.json';manifest.write_text(json.dumps(good));progress=b/'progress';progress.touch()
 def run(command='partition-info',expect=True,helper=test):
  result=subprocess.run([str(helper),command,str(manifest),str(disk),str(part),str(sys),str(cache),'eng.test.20261111.220000',str(progress)],text=True,capture_output=True,timeout=10)
  assert (result.returncode==0)==expect,result
  return result
 info=run().stdout;data=dict(line.split('=',1) for line in info.splitlines());assert data['origin']=='release' and data['transport']=='partition' and data['cache_hit']=='0'
 assert int(progress.read_text())==len(content)
 assert run('partition-cached').stdout.endswith('cache_hit=1\n')
 family=data['source_id'];old_cache=(cache/(family+'.meta')).read_bytes()
 # Tail padding is ignored: only manifest image.size bytes are hashed.
 assert part.stat().st_size>len(content) and part.read_bytes()[-1:]==b'X'
 assert 'requires block device' in run(expect=False,helper=production).stderr
 # Changed content under the same UUID is rehashed after the cache is invalidated.
 (cache/(family+'.meta')).unlink()
 with part.open('r+b') as f:f.seek(len(content)-1);f.write(bytes([content[-1]^1]))
 assert 'image checksum' in run(expect=False).stderr and not (cache/(family+'.meta')).exists()
 with part.open('r+b') as f:f.write(content)
 run()
 # New partition GUID changes source family even with byte-identical content.
 entries[13*128+16]^=1;gpt();assert 'not yet validated' in run('partition-cached',False).stderr
 newer=dict(line.split('=',1) for line in run().stdout.splitlines());assert newer['source_id']!=family and newer['generation']==data['generation']
 # Wrong geometry, GPT CRC, manifest capacity and EROFS feature bits fail closed.
 (pdir/'start').write_text(str(first+1));assert 'partition start' in run(expect=False).stderr;(pdir/'start').write_text(str(first))
 with disk.open('r+b') as f:f.seek(512+16);f.write(b'BAD!')
 assert 'header checksum' in run(expect=False).stderr;gpt()
 bad=copy.deepcopy(good);bad['image']['size']=part.stat().st_size+4096;manifest.write_text(json.dumps(bad));assert 'image length' in run(expect=False).stderr
 manifest.write_text(json.dumps(good))
 with part.open('r+b') as f:f.seek(1104);f.write(struct.pack('<I',3))
 assert 'EROFS features' in run(expect=False).stderr
 with part.open('r+b') as f:f.write(content)
 bad=copy.deepcopy(good);bad['minimum_andr36oid']='eng.test.20990101.000000';manifest.write_text(json.dumps(bad));assert 'newer Android' in run(expect=False).stderr
 print('PASS partition: real EROFS, same-card GPT/kernel geometry, bounded prefix SHA, durable cache/readback, GUID generation separation, CRC/type/features/minimum failures; production rejects regular objects')
 # Exercise the actual broker resolver/mount/prestage functions. Only block
 # predicates and mount syscalls are adapted to this regular-file fixture.
 manifest.write_text(json.dumps(good));run()
 for number in [1,2]:(b/f'mmcblk9p{number}').touch()
 by=b/'by-name';by.mkdir();(by/'system').symlink_to(b/'mmcblk9p2');(by/'BOOT').symlink_to(b/'mmcblk9p1')
 card=b/'card/subsystem/darkosen';card.mkdir(parents=True)
 newer=card/'newer.erofs.img';newer.write_bytes(content)
 request=copy.deepcopy(good);request.update(job='0123456789abcdef',darkosen='12112026',download=dict(compression='xz',size=1,sha256='0'*64,parts=[dict(name='dummy.xz',size=1,sha256='0'*64)]));request['image']['name']=newer.name
 req=b/'request.json'
 def new_record(version,revision):
  request['darkosen']=version;request['revision']=revision;req.write_text(json.dumps(request))
  Path(str(newer)+'.meta').write_bytes(subprocess.check_output([str(test),'release-record',str(req),request['job'],'aaaa-1111',str(newer)]))
 new_record('12112026',1)
 import shlex
 image_lib=b/'images.sh';image_lib.write_text((common/'PortMaster/broker/andr36oid-images.sh').read_text().replace('/proc/mounts',str(b/'mounts')).replace('[ -b "$source" ]','[ -f "$source" ]'))
 part_lib=b/'prepared.sh';part_lib.write_text((common/'PortMaster/broker/andr36oid-prepared.sh').read_text().replace('/proc/mounts',str(b/'mounts')).replace('[ -b "$PREPARED_PART" ]','[ -f "$PREPARED_PART" ]'))
 script=r'''
set -eu
BASE=BASE_VALUE
STATE=$BASE/state;ROOT=$BASE/root;IMAGE=;LOG=$BASE/log;HANDOFF=1
A36_CONTROL=$BASE/control;A36_EROFS_SUPPORT=$BASE/fixture-helper;A36_STORAGE_LOCK=$BASE/storage.lock
A36_BYNAME=$BASE/by-name;A36_BLOCK_SYS=$BASE/sys;A36_POWER_DIR=$BASE/power
mkdir -p "$STATE" "$ROOT" "$BASE/props" "$A36_POWER_DIR"
: > "$BASE/mounts";: > "$A36_POWER_DIR/wake_lock";: > "$A36_POWER_DIR/wake_unlock"
getprop(){ if [ "$1" = ro.build.version.incremental ];then echo eng.test.20261111.220000;else cat "$BASE/props/$1" 2>/dev/null || true;fi; }
setprop(){ printf '%s' "$2" > "$BASE/props/$1"; }
log(){ printf '%s\n' "$*" >> "$LOG"; }
. IMAGE_LIB_VALUE
. PREPARED_LIB_VALUE
image_cards(){ printf '%s\taaaa-1111\tfake\trw\n' "$BASE/card"; }
prepared_pin_manifest(){ "$SUPPORT" partition-pin "$BASE/manifest.json" "$PREPARED_DIR/manifest.json"; }
prepared_probe verify
part_id=$(field "$PREPARED_INFO" source_id)
resolve_selected_image
[ "$SOURCE_TRANSPORT" = file ] && [ "$SOURCE" = "$BASE/card/subsystem/darkosen/newer.erofs.img" ]
setprop "$SELECT_PROP" "$part_id"
resolve_selected_image
[ "$SOURCE_TRANSPORT" = partition ] && [ "$SOURCE" = "$BASE/mmcblk9p14" ]
# The direct root path must never allocate or journal a loop device.
losetup(){ echo unexpected-losetup >&2;exit 97; }
record_session_loop(){ echo unexpected-session-loop >&2;exit 98; }
umount(){ return 0; }
track_mount(){ printf '%s\n' "$1" >> "$STATE/session.mounts"; }
forget_mount(){ :; }
mount(){
 if [ "$2" = erofs ];then
  [ "$3" = -o ] && [ "$4" = ro ] && [ "$5" = "$BASE/mmcblk9p14" ]
  cp -a "$BASE/tree/." "$6/";printf '%s %s erofs ro 0 0\n' "$5" "$6" >> "$BASE/mounts"
 else
  cp -a "$LOWER/." "$ROOT/";printf 'overlay %s overlay rw 0 0\n' "$ROOT" >> "$BASE/mounts"
 fi
}
: > "$STATE/session.mounts"
mount_managed_rootfs
[ -z "$LOOPDEV" ] && [ "$MOUNTED" = 1 ] && [ -f "$ROOT/sbin/init" ] && [ ! -s "$STATE/session.loop" ]
mount_managed_rootfs # reuse verifies the actual direct read-only lower source
write_catalog
[ "$(wc -l < "$STATE/images.tsv")" = 2 ]
prestage_storage_token(){ echo fixture-card; }
: > "$STATE/roms-map"
setprop persist.andr36oid.subsystem.prestage 1
printf '%s\n' "$(boot_id)" > "$STATE/prestage.boot"
prestage_signature resolved > "$STATE/prestage.ready"
prestage_valid
[ -z "$LOOPDEV" ]
setprop "$SELECT_PROP" ""
'''.replace('BASE_VALUE',shlex.quote(str(b))).replace('IMAGE_LIB_VALUE',shlex.quote(str(image_lib))).replace('PREPARED_LIB_VALUE',shlex.quote(str(part_lib)))
 worker=b/'integration.sh';worker.write_text(script)
 for shell in ['sh','bash','mksh']:
  subprocess.run([shell,str(worker)],check=True,timeout=30)
  shutil.rmtree(b/'state');shutil.rmtree(b/'root')
  print('PASS',shell,'newer file wins, explicit partition selection, direct ro mount/reuse, catalog and resident stage without loop')
