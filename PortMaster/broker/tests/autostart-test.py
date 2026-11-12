#!/usr/bin/env python3
"""Boot policy + real file locks and native watch. No mounts/power/device access."""
from pathlib import Path
import json,os,shlex,shutil,subprocess,tempfile,time,fcntl,threading
common=Path(__file__).resolve().parents[3];libs=common/'PortMaster/broker';native_src=common/'PortMaster/erofs-tools/andr36oid-erofs-support.c'
with tempfile.TemporaryDirectory(prefix='a36-autostart-') as temp:
    b=Path(temp);props=b/'props';props.mkdir();native=b/'support'
    subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror',str(native_src),'-o',str(native)],check=True)
    script=r'''
set -eu
BASE=@BASE@
STATE=$BASE/state;ROOT=$BASE/root;IMAGE=;LOG=$BASE/log
A36_CONTROL=$BASE/control;A36_STORAGE_LOCK=$BASE/storage.lock;A36_USB_STATE=$BASE/usb;A36_BOOT_GUARD=$BASE/bootguard
mkdir -p "$STATE" "$ROOT"
getprop(){ cat "$BASE/props/$1" 2>/dev/null || :; }
setprop(){ printf '%s' "$2" > "$BASE/props/$1";printf 'prop %s %s\n' "$1" "$2" >> "$BASE/events"; }
log(){ printf '%s\n' "$*" >> "$LOG"; }
. @LIB@/andr36oid-images.sh
. @LIB@/andr36oid-boot.sh
boot_id(){ echo 11111111-2222-3333-4444-555555555555; }
KEYHELD=key_mock
key_mock(){ echo key >> "$BASE/events";[ "${MOCK_HELD:-0}" = 1 ]; }
session_active(){ [ "${MOCK_SESSION:-0}" = 1 ]; }
sleep(){ echo sleep >> "$BASE/events"; setprop sys.andr36oid.easyroms.ready 1; /bin/sleep .002; }
wait_games_card_mount(){ echo card_wait >> "$BASE/events";return 0; }
prepared_devices(){ PREPARED_DISK=fake;PREPARED_PART=fake; }
SUPPORT=support_mock
support_mock(){
  case "$1" in
    partition-target) [ "${MOCK_PART:-0}" = 1 ] || return 1;printf 'source_id=%s\ncapacity=10000000000\n' @PARTID@ ;;
    autostart-watch) @NATIVE@ "$@";;
    *) return 2;;
  esac
}
scan_images(){
  echo scan >> "$BASE/events"
  acquire_operation scan || return 1
  publish image "${MOCK_IMAGE:-1}";release_operation
}
case "$1" in
 decision) boot_decision;;
 waiting) boot_phase_write "$(prop boot_token)" waiting;;
 ended) boot_phase_write "$(prop boot_token)" ended;;
 watch) A36_BOOT_LAUNCH_WAIT_MS=100;A36_BOOT_SUCCESS_MS=80;boot_watch;;
 success)
  A36_BOOT_LAUNCH_WAIT_MS=200;A36_BOOT_SUCCESS_MS=80
  boot_autostart_capture;boot_autostart_running;boot_watch;boot_autostart_end;;
 blocked)
  prestage_blocked;;
 trim) . @TRIM@ ;;
esac
'''
    part_id='a'*64
    trim=b/'trim.sh';trim.write_text((libs/'andr36oid-boot-trim.sh').read_text().replace('/dev/.andr36oid-boot-trim-done',str(b/'trimdone')).replace('/system/bin/cmd',str(b/'cmd')))
    cmd=b/'cmd';cmd.write_text('#!/bin/sh\n[ "$*" = "activity kill-all" ] || exit 2\necho trimmed >> '+shlex.quote(str(b/'events'))+'\n');cmd.chmod(0o755)
    for key,value in {'BASE':b,'LIB':libs,'NATIVE':native,'TRIM':trim,'PARTID':part_id}.items():script=script.replace('@'+key+'@',shlex.quote(str(value)))
    worker=b/'worker';worker.write_text(script)
    def prop(name, value=None):
        p=props/name
        if value is not None:p.write_text(value)
        return p.read_text() if p.exists() else ''
    P='sys.andr36oid.subsystem.';F='persist.andr36oid.subsystem.autostart_failures';E='persist.andr36oid.subsystem.autostart_error'
    def reset(default='android',target='ES'):
        shutil.rmtree(b/'bootguard',ignore_errors=True);shutil.rmtree(b/'trimdone',ignore_errors=True)
        for p in props.iterdir():p.unlink()
        (b/'events').write_text('')
        prop('persist.andr36oid.subsystem.default',default);prop('persist.andr36oid.subsystem.default_target',target)
        prop('sys.andr36oid.easyroms.ready','1');prop('sys.andr36oid.easyroms.id','public:179,7')
    def run(shell,verb='decision',ok=True,**env):
        result=subprocess.run([shell,str(worker),verb],text=True,capture_output=True,timeout=15,env={**os.environ,**env})
        assert (result.returncode==0)==ok,(shell,verb,result,(b/'events').read_text())
        return result
    for shell in ['sh','bash','mksh']:
        for default,held,want in [('android','0',False),('android','1',True),('subsystem','0',True),('subsystem','1',False)]:
            reset(default,'PM');run(shell,MOCK_HELD=held)
            events=(b/'events').read_text().splitlines()
            assert events.index('key')<events.index('scan')
            assert (prop(P+'request')=='run')==want
            if want:assert prop(P+'port')=='PM' and prop(F)=='1' and prop(P+'boot_pending')=='1'
            else:assert prop(F)==''
            before=events.count('key');runs=events.count('prop '+P+'request run')
            run(shell,MOCK_HELD='1')
            events=(b/'events').read_text().splitlines()
            assert events.count('key')==before and events.count('prop '+P+'request run')==runs
            assert events.count('scan')==2
        reset('subsystem');prop('sys.andr36oid.easyroms.ready','0');run(shell,MOCK_PART='1')
        assert 'sleep' not in (b/'events').read_text() and 'card_wait' not in (b/'events').read_text()
        reset('subsystem');prop('sys.andr36oid.easyroms.ready','0');run(shell)
        events=(b/'events').read_text().splitlines();assert events.index('key')<events.index('sleep')<events.index('card_wait')<events.index('scan')
        reset('subsystem');run(shell,MOCK_IMAGE='0')
        assert prop(P+'request')=='' and prop(P+'boot_reason')=='no_image' and prop(F)==''
        reset('subsystem');run(shell,MOCK_SESSION='1')
        assert prop(P+'request')=='' and prop(F)==''
        # Pending/deciding block the real prestage predicate, not its persist setting.
        reset();prop(P+'boot_deciding','1');run(shell,'blocked')
        prop(P+'boot_deciding','0');prop(P+'boot_pending','1');run(shell,'blocked')
        prop(P+'boot_pending','0');run(shell,'blocked',ok=False)
        # Two separate boots that never run a payload inhibit the third boot.
        reset('subsystem')
        for attempt in [1,2]:
            shutil.rmtree(b/'bootguard',ignore_errors=True);run(shell)
            assert prop(F)==str(attempt)
            run(shell,'ended');run(shell,'watch')
            assert prop(E)=='failed_launch' and prop(F)==str(attempt)
        shutil.rmtree(b/'bootguard',ignore_errors=True);prop(P+'request','');run(shell)
        assert prop(P+'boot_reason')=='fail_safe' and prop(P+'request')=='' and prop(F)=='2'
        # A live watched interval (scaled80ms here, production30000) resets both.
        reset('subsystem');prop(F,'1');run(shell);assert prop(F)=='2'
        run(shell,'success');assert prop(F)=='0' and prop(E)==''
        # Trim is skipped for admission/session, runs once once Android is back.
        reset();prop('init.svc.zygote','running');prop(P+'boot_pending','1');run(shell,'trim')
        assert 'trimmed' not in (b/'events').read_text()
        prop(P+'boot_pending','0');prop(P+'session','running');run(shell,'trim')
        assert 'trimmed' not in (b/'events').read_text()
        prop(P+'session','ended');run(shell,'trim');run(shell,'trim')
        assert (b/'events').read_text().splitlines().count('trimmed')==1
        reset();prop('persist.andr36oid.boot_trim','0');run(shell,'trim');assert 'trimmed' not in (b/'events').read_text()
        print('PASS',shell,'single boot scan/guard, START inversion4ways, partition no-card/file wait, no-image fallback, two-failure inhibit/live reset, prestage+trim gates',flush=True)
    # Real storage flock held by another open description: boot scan retries,
    # never races or manufactures its own unlocked resolution.
    reset('subsystem');fd=os.open(b/'storage.lock',os.O_CREAT|os.O_WRONLY,0o600);fcntl.flock(fd,fcntl.LOCK_EX)
    proc=subprocess.Popen(['mksh',str(worker),'decision'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    time.sleep(.08);assert prop(P+'request')!='run'
    fcntl.flock(fd,fcntl.LOCK_UN);os.close(fd);out,err=proc.communicate(timeout=15)
    assert proc.returncode==0 and prop(P+'request')=='run',(out,err)
    print('PASS real storage lease: waits for previous owner, then one normal run request',flush=True)
    # Native watcher: actual proc identity, abrupt owner death, early exit,
    # stale token and deadline. Runs the shipped ARM helper too, when available.
    token='11111111-2222-3333-4444-555555555555';phase=b/'watch-phase'
    def stamp(pid):return Path(f'/proc/{pid}/stat').read_text().rsplit(')',1)[1].split()[19]
    def state(value,pid=os.getpid(),generation=token,owner_stamp=None):
        tmp=phase.with_suffix('.tmp');tmp.write_text(f'token={generation}\nphase={value}\npid={pid}\nstart={owner_stamp or stamp(pid)}\n');tmp.replace(phase)
    runners=[[str(native)]]
    arm=common/'PortMaster/erofs-tools/andr36oid-erofs-support'
    if shutil.which('qemu-aarch64'):runners.append(['qemu-aarch64','-cpu','cortex-a35',str(arm)])
    for runner in runners:
        state('running');result=subprocess.run(runner+['autostart-watch',str(phase),token,'300','80'],text=True,capture_output=True,timeout=5);assert result.returncode==0 and result.stdout.strip()=='success',result
        state('waiting');proc=subprocess.Popen(runner+['autostart-watch',str(phase),token,'500','300'],stdout=subprocess.PIPE,text=True)
        time.sleep(.06);state('running');time.sleep(.12);state('ended');assert proc.communicate(timeout=5)[0].strip()=='ended_early'
        child=subprocess.Popen(['sleep','5']);state('running',child.pid);proc=subprocess.Popen(runner+['autostart-watch',str(phase),token,'500','400'],stdout=subprocess.PIPE,text=True)
        time.sleep(.1);child.kill();child.wait();assert proc.communicate(timeout=5)[0].strip() in ['failed_launch','ended_early']
        state('running',owner_stamp='1');assert subprocess.check_output(runner+['autostart-watch',str(phase),token,'100','80'],text=True).strip()=='failed_launch'
        state('waiting');assert subprocess.check_output(runner+['autostart-watch',str(phase),token,'80','80'],text=True).strip()=='failed_launch'
        state('waiting',generation='22222222-2222-3333-4444-555555555555');assert subprocess.check_output(runner+['autostart-watch',str(phase),token,'80','80'],text=True).strip()=='changed'
        print('PASS native watch',runner[0],'live interval, early end, dead/reused owner, launch timeout, stale token',flush=True)
    rc=(common/'PortMaster/subsystem/andr36oid-subsystem.rc').read_text()
    assert 'Automatic subsystem boot is intentionally disabled' not in rc
    assert 'service andr36oid-subsystem-boot ' not in rc
    for trigger in ['on property:sys.boot_completed=1','on property:sys.andr36oid.easyroms.ready=1 && property:sys.boot_completed=1','on property:init.svc.andr36oid-subsystem=stopped && property:sys.andr36oid.subsystem.session=ended']:
        assert trigger+'\n    start andr36oid-subsystem-scan' in rc
    broker=(libs/'andr36oid-subsystem.sh').read_text()
    finish=broker.split('finish_session() {',1)[1].split('\n}',1)[0]
    assert finish.index('boot_autostart_end')<finish.index('restore_android')
    handoff=broker.split('handoff() {',1)[1].split('\n}',1)[0]
    assert 'for service in zygote zygote_secondary audioserver iorapd joyMouse' in handoff
    for service in ['adbd','wifi','netd','wificond','wpa_supplicant']:
        assert 'stop '+service not in handoff
    print('PASS trigger ordering, ended BEFORE44s framework restore, normal handoff keeps Wi-Fi/adbd/netd/wificond',flush=True)
