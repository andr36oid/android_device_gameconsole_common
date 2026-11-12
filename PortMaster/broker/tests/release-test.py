#!/usr/bin/env python3
"""Real request parser/SHA/stream/EROFS + crash-recovery host integration.
Only Android properties, card enumeration, loop/mount and power are simulated.
No device, host block device, host /sys write, or global sync is used.
"""
import copy, hashlib, json, lzma, os, shlex, shutil, struct, subprocess, tempfile, sys
from pathlib import Path
common = Path(__file__).resolve().parents[3]
tools = common / 'PortMaster/erofs-tools'
libs = common / 'PortMaster/broker'
with tempfile.TemporaryDirectory(prefix='a36-release-') as tmp:
    b = Path(tmp)
    native = b/'native'
    subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror',str(tools/'andr36oid-erofs-support.c'),'-o',str(native)],check=True)
    qemu = shutil.which('qemu-aarch64') or shutil.which('qemu-aarch64-static')
    assert qemu
    tree = b/'tree'
    for file in ['etc/passwd','etc/group','bin/sh','sbin/init','usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1','usr/bin/emulationstation/emulationstation']:
        p=tree/file;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('fixture\n');p.chmod(0o755)
    images=[]
    for n in range(2):
        (tree/'etc/version').write_text(str(n))
        image=b/f'fixture{n}.erofs'
        subprocess.run([qemu,str(tools/'mkfs.erofs'),'-b4096','-zlz4hc,9','-E^xattr-name-filter','--quiet',str(image),str(tree)],check=True,stdout=subprocess.DEVNULL)
        images.append(image.read_bytes())
    card=b/'card/subsystem/darkosen';card.mkdir(parents=True)
    props=b/'props';props.mkdir();power=b/'power';power.mkdir()
    for name in ['wake_lock','wake_unlock']: (power/name).touch()
    # Keep a native btrfs image present: standalone release must win only when
    # selection is empty, while explicit legacy selection remains honored.
    plain=bytearray(512+131072);plain[510:512]=b'\x55\xaa';plain[450]=0x83;struct.pack_into('<II',plain,454,1,256)
    off=512+65536;plain[off+32:off+48]=bytes(range(16));struct.pack_into('<Q',plain,off+48,65536);plain[off+64:off+72]=b'_BHRfS_M'
    for offset,value in [(72,1),(112,131072),(136,1)]:struct.pack_into('<Q',plain,off+offset,value)
    (card/'old.img').write_bytes(plain)
    wrapper=b/'support'
    wrapper.write_text('''#!/usr/bin/python3
import os,sys,subprocess
from pathlib import Path
real=NATIVE
args=sys.argv[1:]
if args[0] in ('detach-owned','retire-owned'):sys.exit(0)
if args[0]=='fsync' and os.environ.get('FAULT') in ('readycrash','readyfail') and args[1].endswith('/release-lifecycle'):
    intent=Path(args[1])/'intent'
    hit=intent.exists() and 'phase=ready\\n' in intent.read_text() and not Path(HIT).exists()
    if hit and os.environ.get('FAULT')=='readyfail':
        Path(HIT).touch();sys.exit(1)
    rc=subprocess.call([real]+args)
    if rc==0 and hit:
        Path(HIT).touch();os.kill(os.getppid(),9)
    sys.exit(rc)
if args[0]=='publish':
    mode=os.environ.get('FAULT','')
    dest=Path(args[2]).name
    point=('oldmeta' if dest.endswith('.old.meta') else 'oldimage' if dest.endswith('.old') else 'newmeta' if dest.endswith('.img.meta') else 'newimage' if dest.endswith('.img') else '')
    rc=subprocess.call([real]+args)
    if rc==0 and mode==point and not Path(HIT).exists():
        Path(HIT).touch();os.kill(os.getppid(),9)
    sys.exit(rc)
os.execv(real,[real]+args)
'''.replace('NATIVE',repr(str(native))).replace('HIT',repr(str(b/'hit'))))
    wrapper.chmod(0o755)
    fsck=b/'fsck';fsck.write_text('#!/bin/sh\nexec '+shlex.quote(qemu)+' '+shlex.quote(str(tools/'fsck.erofs'))+' "$@"\n');fsck.chmod(0o755)
    script='''
set -u
BASE=BASE_VALUE
STATE=$BASE/state;ROOT=$BASE/root;IMAGE=;LOG=$BASE/broker.log
A36_CONTROL=$BASE/control;A36_STORAGE_LOCK=$BASE/storage.lock;A36_USB_STATE=$BASE/usb
A36_EROFS_SUPPORT=$BASE/support;A36_OPT_PRIVATE=1;A36_RELEASE_POLL=0.05;A36_POWER_DIR=$BASE/power
A36_UNXZ=UNXZ_VALUE;A36_FSCK_EROFS=$BASE/fsck
mkdir -p "$STATE" "$ROOT"
getprop() { if [ "$1" = ro.build.version.incremental ]; then echo eng.test.20261111.220000; else cat "$BASE/props/$1" 2>/dev/null || true; fi; }
setprop() { printf '%s' "$2" > "$BASE/props/$1"; }
log() { printf '%s\\n' "$*" >> "$LOG"; }
. IMAGES_LIB
. RELEASE_LIB
if [ "${A36_TEST_LIFECYCLE:-0}" = 1 ]; then
  . UPDATE_LIB
  A36_RELEASE_TEST_INLINE=1; A36_RELEASE_WAKE_MS=10
  export A36_RELEASE_WAKE_MS
  prestage_unmount_locked() { printf 'unmount\\n' >> "$BASE/events"; rm -f "$STATE/prestage.ready"; }
  stage_mounts() {
    resolve_selected_image || return 1
    printf 'setup %s\\n' "$GENERATION" >> "$BASE/events"
    if [ "${FAULT:-}" = setupfail ] && [ "$GENERATION" != "$(getprop test.oldgeneration)" ]; then return 1; fi
    if [ "${FAULT:-}" = setupcrash ] && [ ! -f "$BASE/hit" ]; then touch "$BASE/hit"; kill -KILL $$; fi
    if [ "${FAULT:-}" = toggleoff ]; then setprop persist.andr36oid.subsystem.prestage 0; fi
    mkdir -p "$CONTROL/ready"
    printf '%s\\n' "$EFFECTIVE" > "$CONTROL/ready/$(ready_key)"
    STAGE_COMPLETE=1
  }
  prestage_park() {
    [ -s "$CONTROL/ready/$(ready_key)" ] || return 1
    # Parts and the rollback copy must still exist at the parking boundary.
    [ -f "$IMAGE_DIR/payload.xz.001" ] || [ "${FAULT:-}" = recover ] || return 1
    printf 'park %s\\n' "$GENERATION" >> "$BASE/events"
    touch "$STATE/prestage.ready"
  }
fi
image_cards() { printf '%s\\tabcd-1234\\tfake\\trw\\n' "$BASE/card"; }
losetup() { [ "${1:-}" != -f ] || printf '/dev/loopMOCK\\n'; return 0; }
mount() { for target; do :; done; cp -a "$BASE/tree/." "$target/"; }
umount() { return 0; }
case "$1" in
 install) setprop "$PROP.rel_dispatch_job" "$2"; install_release_worker "$2" 'release.erofs.img.request.json';;
 recover) acquire_operation scan || exit 90; release_operation;;
 resolve)
   resolve_selected_image || exit 91
   [ "$EFFECTIVE_FORMAT" = erofs ] && [ "$(field "$RECORD" origin)" = release ] || exit 92
   prepare_writable_state
   write_catalog
   [ "$(wc -l < "$STATE/images.tsv")" = 2 ] || exit 93
   awk -F '\t' '$2=="release.erofs.img" && $4==0 && $5>0 {found=1} END {exit !found}' "$STATE/images.tsv" || exit 94
   printf '%s\\n%s\\n' "$UPPER" "$GENERATION";;
 plain)
   setprop "$SELECT_PROP" "$(selection_id abcd-1234 old.img)"
   resolve_selected_image && [ "$EFFECTIVE_FORMAT" = btrfs ];;
esac
'''.replace('BASE_VALUE',shlex.quote(str(b))).replace('UNXZ_VALUE',shlex.quote(shutil.which('unxz'))).replace('IMAGES_LIB',shlex.quote(str(libs/'andr36oid-images.sh'))).replace('RELEASE_LIB',shlex.quote(str(libs/'andr36oid-release.sh'))).replace('UPDATE_LIB',shlex.quote(str(libs/'andr36oid-update.sh')))
    worker=b/'worker.sh';worker.write_text(script)
    def run(shell,verb,*args,fault='',ok=True):
        result=subprocess.run([shell,str(worker),verb,*args],text=True,capture_output=True,timeout=60,env={**os.environ,'FAULT':fault})
        if ok: assert result.returncode==0,(shell,verb,result,(b/'state/release-install.log').read_text() if (b/'state/release-install.log').exists() else '')
        else: assert result.returncode!=0,(verb,result)
        return result
    def prop(key):
        p=props/('sys.andr36oid.subsystem.'+key)
        return p.read_text() if p.exists() else ''
    counter=0
    def request(index=0):
        global counter
        counter+=1;job=f'{counter:016x}'
        data=lzma.compress(images[index]);split=len(data)//2
        parts=[]
        for i,part in enumerate([data[:split],data[split:]],1):
            name=f'payload.xz.{i:03d}';(card/name).write_bytes(part)
            parts.append(dict(name=name,size=len(part),sha256=hashlib.sha256(part).hexdigest()))
        manifest=dict(format=1,job=job,darkosen='11112026',revision=index+1,minimum_andr36oid='',image=dict(name='release.erofs.img',size=len(images[index]),sha256=hashlib.sha256(images[index]).hexdigest()),download=dict(compression='xz',size=len(data),sha256=hashlib.sha256(data).hexdigest(),parts=parts))
        (card/'release.erofs.img.request.json').write_text(json.dumps(manifest))
        return job,manifest
    def clear_installed():
        for p in list(card.iterdir()):
            if p.name!='old.img':p.unlink()
        for directory in ['control','state']:
            shutil.rmtree(b/directory,ignore_errors=True)
        for p in props.iterdir():p.unlink()
        (b/'hit').unlink(missing_ok=True)
        (b/'events').unlink(missing_ok=True)
    if os.environ.get('A36_TEST_LIFECYCLE') == '1':
        def persist(value='1'):
            (props/'persist.andr36oid.subsystem.prestage').write_text(value)
        def installed(shell):
            clear_installed(); job,m=request(); run(shell,'install',job)
            old=prop('resolved'); record=subprocess.check_output([str(native),'record',str(card/'release.erofs.img'),'abcd-1234'],text=True)
            generation=dict(line.split('=',1) for line in record.splitlines())['generation']
            (props/'test.oldgeneration').write_text(generation)
            persist(); (b/'state/prestage.ready').touch()
            (b/'events').write_text('')
            return generation
        for shell in ['sh','bash','mksh']:
            if not shutil.which(shell): continue
            old=installed(shell); job,m=request(1); run(shell,'install',job)
            events=(b/'events').read_text().splitlines()
            assert events[0]=='unmount' and events[1].startswith('setup ') and events[2].startswith('park '),events
            assert (card/'release.erofs.img').read_bytes()==images[1]
            assert not list(card.glob('.a36-release-*')) and not list(card.glob('payload.xz.*'))
            assert (b/'state/prestage.ready').exists() and not (b/'control/release-lifecycle/intent').exists()
            assert prop('rel_state')=='done' and (props/'persist.andr36oid.subsystem.prestage').read_text()=='1'
            assert (power/'wake_unlock').read_text().strip()=='a36-release-'+job
            old=installed(shell); job,m=request(1); run(shell,'install',job,fault='setupfail',ok=False)
            assert prop('rel_error')=='setup_failed' and prop('rel_cleanup_pending')=='0'
            assert (card/'release.erofs.img').read_bytes()==images[0]
            assert (b/'events').read_text().splitlines()[-1]=='park '+old
            assert (card/'payload.xz.001').exists() and not list(card.glob('.a36-release-*'))
            old=installed(shell); job,m=request(1); run(shell,'install',job,fault='toggleoff')
            assert not (b/'state/prestage.ready').exists() and 'park ' not in (b/'events').read_text()
            assert (props/'persist.andr36oid.subsystem.prestage').read_text()=='0'
            print('PASS lifecycle',shell,'evict, SETUP then park, old-pair restoration on SETUP failure, property OFF respected, wake release')
        installed('bash'); job,m=request(1); run('bash','install',job,fault='readyfail',ok=False)
        assert prop('rel_error')=='io_error' and prop('rel_cleanup_pending')=='1'
        assert (card/'payload.xz.001').exists() and list(card.glob('*.old'))
        run('bash','recover',fault='recover')
        assert not (b/'control/release-lifecycle/intent').exists()
        print('PASS lifecycle failed durable ready write retains parts and rollback pair until recovery')
        for point in ['oldmeta','oldimage','newmeta','newimage','setupcrash','readycrash']:
            old=installed('bash'); job,m=request(1); run('bash','install',job,fault=point,ok=False)
            assert (b/'hit').exists(),point
            run('bash','recover',fault='recover')
            committed=point=='readycrash'
            assert (card/'release.erofs.img').read_bytes()==images[int(committed)],point
            assert not (b/'control/release-lifecycle/intent').exists(),point
            assert (b/'state/prestage.ready').exists(),point
            assert not list(card.glob('.a36-release-*')),point
            run('bash','recover',fault='recover')
            print('PASS lifecycle crash',point,'ready commit' if committed else 'rollback and re-stage previous generation')
        clear_installed(); persist(); (b/'state').mkdir(exist_ok=True); (b/'state/session.lock').mkdir()
        job,m=request(); run('bash','install',job,ok=False)
        assert prop('rel_error')=='busy' and not (b/'control/release-lifecycle/intent').exists()
        print('PASS lifecycle live session refused; no incomplete lifecycle admission')
        sys.exit(0)
    # Schema/parser and native stream checks independent of shell/loops.
    job,valid=request()
    def check(obj,expected=True):
        p=b/'request.json';p.write_text(json.dumps(obj))
        result=subprocess.run([str(native),'release-check',str(p),job],capture_output=True)
        assert (result.returncode==0)==expected,result
    check(valid)
    for mutate in [lambda m:m.update(format=2),lambda m:m.update(job='f'*16),lambda m:m.update(revision=-1),lambda m:m.update(revision=2**64),lambda m:m['image'].update(name='../x.img'),lambda m:m['image'].update(size=4095),lambda m:m['download']['parts'][0].update(name='/dev/block/mmcblk0'),lambda m:m['download'].update(size=1),lambda m:m['download'].update(parts=[]),lambda m:m['image'].update(sha256='X'*64),lambda m:m.update(extra='not accepted'),lambda m:m['download']['parts'].append(m['download']['parts'][0])]:
        bad=copy.deepcopy(valid);mutate(bad);check(bad,False)
    for data in ['{','{"format":1,"format":1}',json.dumps(valid)+'x',json.dumps(valid).replace('"job":','"job": "bad", "job":',1)]:
        (b/'request.json').write_text(data)
        assert subprocess.run([str(native),'release-check',str(b/'request.json'),job],capture_output=True).returncode
    (b/'request.json').write_text(json.dumps(valid))
    stream=subprocess.check_output([str(native),'release-stream',str(b/'request.json'),job,str(card),'-'])
    assert lzma.decompress(stream)==images[0]
    clear_installed()
    for shell in ['sh','bash','mksh']:
        if not shutil.which(shell):continue
        clear_installed();job,m=request()
        run(shell,'install',job)
        assert prop('rel_state')=='done' and prop('rel_done_job')==job and prop('rel_cleanup_pending')=='0'
        assert prop('ready')=='0' and prop('resolved')==prop('rel_image')
        assert (card/'release.erofs.img').read_bytes()==images[0]
        assert not list(card.glob('payload.xz.*')) and (card/'release.erofs.img.request.json').exists()
        assert not list(card.glob('.a36-release-*'))
        (props/'persist.sys.andr36oid.subsystem.selected').unlink()
        first=run(shell,'resolve').stdout.strip().splitlines();Path(first[0]).mkdir(parents=True,exist_ok=True);(Path(first[0])/'sentinel').write_text('old upper')
        run(shell,'plain')
        # Same name, newer generation, old upper retained but never reused.
        job,m=request(1);run(shell,'install',job);second=run(shell,'resolve').stdout.strip().splitlines()
        assert first[1]!=second[1] and (Path(first[0])/'sentinel').exists() and not (Path(second[0])/'sentinel').exists()
        assert (card/'release.erofs.img').read_bytes()==images[1]
        # Corrupt input is retained; committed earlier release stays valid.
        job,m=request(0);part=card/m['download']['parts'][0]['name'];part.write_bytes(b'X'*part.stat().st_size)
        run(shell,'install',job,ok=False)
        assert prop('rel_error')=='download_invalid' and prop('rel_done_job')==job and part.exists()
        assert (card/'release.erofs.img').read_bytes()==images[1] and not list(card.glob('.a36-release-*'))
        # Wrong final SHA (parts themselves valid) cannot publish.
        job,m=request(0);m['image']['sha256']='0'*64;(card/'release.erofs.img.request.json').write_text(json.dumps(m));run(shell,'install',job,ok=False)
        assert prop('rel_state')=='failed' and (card/'payload.xz.001').exists() and not list(card.glob('.a36-release-*'))
        # Minimum Android build rejects before reserving any image.
        job,m=request();m['minimum_andr36oid']='eng.test.20990101.000000';(card/'release.erofs.img.request.json').write_text(json.dumps(m));run(shell,'install',job,ok=False)
        assert prop('rel_error')=='incompatible_build' and not list(card.glob('.a36-release-*'))
        print('PASS',shell,'real EROFS install/stream, generation isolation, legacy selection, input/final hash failures, minimum build, part retention')
    # Final name collisions and symlinked downloads must never modify a user
    # file; malformed XZ is rejected even when all manifest hashes match it.
    clear_installed();job,m=request();(card/'release.erofs.img').write_text('foreign sentinel')
    run('bash','install',job,ok=False)
    assert prop('rel_error')=='output_exists' and (card/'release.erofs.img').read_text()=='foreign sentinel'
    clear_installed();job,m=request();part=card/m['download']['parts'][0]['name'];saved=b/'saved-part';part.rename(saved);part.symlink_to(saved)
    run('bash','install',job,ok=False)
    assert prop('rel_error')=='download_invalid' and saved.exists() and part.is_symlink()
    clear_installed();job,m=request();part=card/m['download']['parts'][-1]['name'];payload=bytearray(part.read_bytes());payload[-1]^=1;part.write_bytes(payload)
    m['download']['parts'][-1]['sha256']=hashlib.sha256(payload).hexdigest()
    m['download']['sha256']=hashlib.sha256(b''.join((card/p['name']).read_bytes() for p in m['download']['parts'])).hexdigest()
    (card/'release.erofs.img.request.json').write_text(json.dumps(m));run('bash','install',job,ok=False)
    assert prop('rel_error')=='unpack_failed' and part.exists() and not (card/'release.erofs.img').exists()
    print('PASS collisions, symlink rejection and decoder CRC failure (valid compressed hashes)')
    # Crash after each rename, followed by normal catalog-lock recovery.
    for point in ['oldmeta','oldimage','newmeta','newimage']:
        clear_installed();job,m=request();run('bash','install',job)
        job,m=request(1);run('bash','install',job,fault=point,ok=False)
        assert (b/'hit').exists(),point
        run('bash','recover')
        committed=point=='newimage'
        assert (card/'release.erofs.img').read_bytes()==images[int(committed)],point
        assert prop('rel_state')==('done' if committed else 'failed'),(point,prop('rel_state'))
        assert (card/'payload.xz.001').exists()!=committed
        assert not list(card.glob('.a36-release-*')) and not (b/'control/release/journal').exists()
        run('bash','recover')
        print('PASS crash',point,'durable commit' if committed else 'rollback, parts retained')
print('PASS release worker integration')
