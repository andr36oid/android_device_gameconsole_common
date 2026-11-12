#!/usr/bin/env python3
"""Run complete firstresize paths against a fake prepared-card sysfs/GPT.
No host mounts/loops/block writes: destructive commands are recorded and trapped.
"""
from pathlib import Path
import json,os,shlex,shutil,subprocess,tempfile
source=Path(__file__).resolve().parents[1]/'andr36oid-firstresize.sh'
with tempfile.TemporaryDirectory(prefix='a36-prepared-') as tmp:
 b=Path(tmp);bin=b/'bin';bin.mkdir();disk='mmcblk9';sys=b/'sys'/disk;sys.mkdir(parents=True);dev=b/'dev';dev.mkdir();boot=b/'boot';boot.mkdir();by=b/'by';by.mkdir();apps=b/'apps';apps.mkdir()
 sectors=64000000000//512;last=sectors-34
 (sys/'size').write_text(str(sectors));(b/'cmdline').write_text('androidboot.boot_devices=ff370000.dwmmc\n')
 text=source.read_text().replace('[ ! -b "$DEV/${disk}p$1" ]','[ ! -f "$DEV/${disk}p$1" ]').replace('[ -b "$DEV/${disk}p$1" ]','[ -f "$DEV/${disk}p$1" ]')
 script=b/'firstresize.sh';script.write_text(text)
 tool=bin/'tool'
 tool.write_text('''#!/usr/bin/python3
from pathlib import Path
import os,sys,shutil
root=Path(os.environ['CASE_ROOT']);name=Path(sys.argv[0]).name;args=sys.argv[1:];s=root/'sys/mmcblk9'
with (root/'commands').open('a') as f:f.write(name+' '+repr(args)+'\\n')
if name=='support':
 if not args or args[0]!='layout-audit':sys.exit(3)
 for n,want,kind in [(6,'userdata','linux'),(7,'EASYROMS','basic'),(13,'AUTOAPK','basic')]:
  p=s/('mmcblk9p'+str(n))
  if p.exists():
   label=(p/'uevent').read_text().strip().split('=',1)[1]
   if label!=want or (p/'gpt_type').read_text()!=kind:
    print('GPT p'+str(n)+' expected '+want+' type '+kind+', got '+label,file=sys.stderr);sys.exit(1)
 if (s/'mmcblk9p7').exists() and not (s/'mmcblk9p6').exists():sys.exit(1)
 sys.exit(0)
if name=='mount':sys.exit(1 if args[args.index('-t')+1]=='exfat' else 0)
if name=='mountpoint':sys.exit(1)
if name in ['sync','umount','setprop','fsync','addpart','resize','fsck']:sys.exit(0)
if name=='sgdisk':
 if args[0]=='--print':
  for p in sorted(s.glob('mmcblk9p*')):
   n=p.name.split('p')[-1];first=int((p/'start').read_text());end=first+int((p/'size').read_text())-1
   label=(p/'uevent').read_text().strip().split('=',1)[1];print(n,first,end,'0 0000',label)
  sys.exit(0)
 if any(a.startswith('--new=6:') for a in args) and '--delete=13' in args and '--delete=6' in args:
  val=next(a for a in args if a.startswith('--new=6:')).split(':');first,end=map(int,val[1:]);shutil.rmtree(s/'mmcblk9p13');(s/'mmcblk9p6/start').write_text(str(first));(s/'mmcblk9p6/size').write_text(str(end-first+1));sys.exit(0)
raise SystemExit('UNEXPECTED DESTRUCTIVE COMMAND '+name+' '+repr(args))
''');tool.chmod(0o755)
 for name in ['mount','mountpoint','sync','umount','setprop','fsync','addpart','resize','fsck','sgdisk','dd','mkfs.exfat','mkfs.vfat','support']:(bin/name).symlink_to(tool.name)
 def part(n,first,end,label):
  p=sys/(disk+'p'+str(n));p.mkdir(exist_ok=True);(p/'start').write_text(str(first));(p/'size').write_text(str(end-first+1));(p/'uevent').write_text('PARTNAME='+label+'\n');(p/'gpt_type').write_text('basic' if label in ['BOOT','EASYROMS','AUTOAPK'] else 'linux');(dev/p.name).touch()
 def reset(guest=True,roms=True,reclaimed=False):
  for p in sys.glob(disk+'p*'):shutil.rmtree(p)
  for p in boot.iterdir():p.unlink()
  (b/'done').unlink(missing_ok=True);(b/'commands').write_text('')
  tail=(last+1-5*1024**3//512)//2048*2048 if guest else last+1
  apps_end=65349631 if roms else tail-1;apps_start=(apps_end+1-4194304)//2048*2048
  part(2,1081344,5701008,'system');part(6,5701632,apps_end if reclaimed else apps_start-1,'userdata')
  if not reclaimed:part(13,apps_start,apps_end,'AUTOAPK')
  spans=[('userdata',5701632,apps_start-1),('AUTOAPK',apps_start,apps_end)]
  if roms:part(7,apps_end+1,tail-1,'EASYROMS');spans.append(('EASYROMS',apps_end+1,tail-1))
  else:(boot/'.noroms').touch()
  if guest:part(14,tail,last,'DARKOS');spans.append(('DARKOS',tail,last))
  (boot/'andr36oid-layout.txt').write_text('prepared-1\n'+''.join(f'{n} {f} {e}\n' for n,f,e in spans))
  for name,n in [('system',2),('BOOT',1)]:
   p=by/name;p.unlink(missing_ok=True);p.symlink_to(dev/(disk+'p'+str(n)))
  return spans
 env={**os.environ,'PATH':str(bin)+':'+os.environ['PATH'],'CASE_ROOT':str(b),'RESIZE_BYNAME':str(by),'RESIZE_SYS':str(b/'sys'),'RESIZE_DEV':str(dev),'RESIZE_KMSG':str(b/'kmsg'),'RESIZE_BOOT_MNT':str(boot),'RESIZE_ADDPART':str(bin/'addpart'),'RESIZE_FSYNC':str(bin/'fsync'),'RESIZE_APPS_MNT':str(apps),'RESIZE_APPS_DONE':str(b/'done'),'RESIZE_RESIZE_F2FS':str(bin/'resize'),'RESIZE_FSCK_F2FS':str(bin/'fsck'),'RESIZE_CMDLINE':str(b/'cmdline')}
 env['RESIZE_F2FS']=str(bin/'resize');env['RESIZE_SUPPORT']=str(bin/'support');env['RESIZE_CLASS_SYS']=str(b/'sys')
 def run(shell,good=True):
  p=subprocess.run([shell,str(script)],env=env,text=True,capture_output=True,timeout=10)
  assert (p.returncode==0)==good,(shell,p,(b/'commands').read_text(),(boot/'resize-log.txt').read_text() if (boot/'resize-log.txt').exists() else '')
  calls=(b/'commands').read_text();assert 'dd ' not in calls and 'mkfs.' not in calls,calls
  return calls
 for shell in ['sh','bash','mksh']:
  for guest,roms in [(False,True),(False,False),(True,True),(True,False)]:
   reset(guest,roms);calls=run(shell)
   assert "'-t', 'exfat'" in calls and "'-t', 'vfat'" in calls and 'sgdisk' not in calls,calls
   if not roms:assert (boot/'.noroms').exists()
  reset();(boot/'resize-pending').touch();run(shell,False)
  reset();(boot/'andr36oid-layout.txt').write_text('prepared-1\nwrong 1 2\n');run(shell,False)
  reset();shutil.rmtree(sys/(disk+'p6'));run(shell,False)
  reset(False,False);shutil.rmtree(sys/(disk+'p6'));run(shell,False)
  reset();old=(sys/(disk+'p14')/'start').read_text();(b/'done').touch();calls=run(shell)
  assert not (sys/(disk+'p13')).exists() and (sys/(disk+'p14')/'start').read_text()==old
  assert '--delete=13' in calls and '--delete=14' not in calls
  run(shell) # stale inventory is valid after p13 union reclaim
  # A compacted GA table must stop before p6's fast path or a stale pending
  # marker can interpret vendor/metadata as userdata/EASYROMS.
  for prepared in [False,True]:
   for number,label in [(6,'vendor'),(7,'metadata'),(13,'metadata')]:
    reset()
    if not prepared:(boot/'andr36oid-layout.txt').unlink()
    (sys/(disk+'p'+str(number))/'uevent').write_text('PARTNAME='+label+'\n')
    (boot/'resize-pending').touch()
    calls=run(shell,False)
    assert 'sys.andr36oid.storage_layout_error' in calls
    text=(boot/'resize-log.txt').read_text()
    assert 'rewrite' in text.lower() and 'raw writer' in text.lower() and 'partition table' in text.lower()
    assert 'sgdisk' not in calls and 'resize ' not in calls
   reset()
   if not prepared:(boot/'andr36oid-layout.txt').unlink()
   (sys/(disk+'p6')/'gpt_type').write_text('basic')
   calls=run(shell,False);assert 'sys.andr36oid.storage_layout_error' in calls
  print('PASS',shell,'prepared fast path, FAT32 fallback, .noroms, pending/malformed/missing-p6 refusal, p13-only reclaim and reboot')
print('PASS prepared-card firstresize host integration')
