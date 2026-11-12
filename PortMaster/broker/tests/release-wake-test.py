#!/usr/bin/env python3
"""Native renewal survives long work, stops before unlock, dies with its worker.
Fake regular power files only; no host /sys access or global sync.
"""
from pathlib import Path
import ctypes, os, signal, subprocess, tempfile, time
common=Path(__file__).resolve().parents[3]
# Reap the orphaned renewer ourselves; do not leave a zombie in host PID1.
assert ctypes.CDLL(None,use_errno=True).prctl(36,1,0,0,0)==0
with tempfile.TemporaryDirectory(prefix='a36-wake-') as temp:
    base=Path(temp); native=base/'native'
    subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror',str(common/'PortMaster/erofs-tools/andr36oid-erofs-support.c'),'-o',str(native)],check=True)
    (base/'wake_lock').touch();(base/'wake_unlock').touch()
    job='0123456789abcdef'
    child=subprocess.Popen([str(native),'wake-hold',job,str(base),'10'])
    time.sleep(.12)
    assert child.poll() is None
    assert (base/'wake_lock').read_text()=='a36-release-'+job+' 60000000000\n'
    before=(base/'wake_lock').stat().st_mtime_ns; time.sleep(.06)
    assert (base/'wake_lock').stat().st_mtime_ns>before
    child.kill();child.wait()
    (base/'wake_unlock').write_text('a36-release-'+job+'\n')
    before=(base/'wake_lock').stat().st_mtime_ns; time.sleep(.06)
    assert (base/'wake_lock').stat().st_mtime_ns==before
    parent=subprocess.Popen(['python3','-c','import subprocess,sys; p=subprocess.Popen(sys.argv[1:]); print(p.pid,flush=True);sys.stdin.read()',str(native),'wake-hold',job,str(base),'10'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
    pid=int(parent.stdout.readline());time.sleep(.08);parent.kill();parent.wait()
    waited,status=os.waitpid(pid,0)
    assert waited==pid and os.WIFSIGNALED(status) and os.WTERMSIG(status)==signal.SIGKILL
    before=(base/'wake_lock').stat().st_mtime_ns;time.sleep(.06)
    assert (base/'wake_lock').stat().st_mtime_ns==before
    # A failed power write cannot silently leave a live renewer.
    (base/'wake_lock').unlink()
    # open(O_WRONLY), without O_CREAT, must fail.
    assert subprocess.run([str(native),'wake-hold',job,str(base),'10'],capture_output=True).returncode
print('PASS release wake: renewal, kill/wait-before-unlock, worker-death SIGKILL, failed acquire exits')
