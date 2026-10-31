"""Exercise the real guest supervisor on a Linux host, with no hardware exposed."""
from pathlib import Path
import os, subprocess, tempfile, time, signal
w=Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory(prefix='a36-namespace-') as temporary:
 b=Path(temporary);root=b/'root';root.mkdir()
 for name in ['proc','sys','run','tmp']: (root/name).mkdir()
 subprocess.run(['cc','-Wall','-Wextra','-Werror','-O2',str(w/'PortMaster/subsystem/andr36oid-guest.c'),'-o',str(b/'guest')],check=True)
 source=r'''
#define main guest_program_main
#include "GUEST_SOURCE"
#undef main
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/capability.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>
int main(int argc,char **argv) {
 assert(getpid()>1);
 if(argc==2 && !strcmp(argv[1],"frontend")) {

 // The real exit-game handler must preserve ES and its input helper.
 assert(!prctl(PR_SET_NAME,"emulationstatio"));
 int gate[2];assert(!pipe(gate));
 pid_t game=fork();assert(game>=0);
 if(!game){prctl(PR_SET_NAME,"test-port");assert(write(gate[1],"g",1)==1);for(;;)pause();}
 pid_t helper=fork();assert(helper>=0);
 if(!helper){prctl(PR_SET_NAME,"gptokeyb");assert(write(gate[1],"h",1)==1);for(;;)pause();}
 char ready;assert(read(gate[0],&ready,1)==1);assert(read(gate[0],&ready,1)==1);
 assert(exit_game(getpid()));int status;assert(waitpid(game,&status,0)==game && WIFSIGNALED(status));
 assert(kill(helper,0)==0);kill(helper,SIGTERM);waitpid(helper,&status,0);
 return 0;
 }
 // The broker must not pass a host directory FD through the chroot.
 errno=0;assert(fcntl(55,F_GETFD)==-1 && errno==EBADF);
 // A wrapper script and the ES binary both have comm=emulationstatio.
 assert(getppid()==1);
 char host[64];assert(!gethostname(host,sizeof(host)));assert(!strcmp(host,"andr36oid-linux"));
 struct __user_cap_header_struct h={.version=_LINUX_CAPABILITY_VERSION_3};struct __user_cap_data_struct c[2];assert(!syscall(SYS_capget,&h,c));
 int deny[]={CAP_SYS_ADMIN,CAP_SYS_MODULE,CAP_SYS_BOOT,CAP_SYS_RAWIO,CAP_SYS_TIME,CAP_NET_ADMIN,CAP_MKNOD,CAP_SYS_CHROOT,CAP_SYS_PTRACE,CAP_SETPCAP,CAP_DAC_READ_SEARCH,CAP_NET_RAW};
 for(unsigned i=0;i<sizeof(deny)/sizeof(*deny);i++) {assert(!((c[deny[i]/32].effective|c[deny[i]/32].permitted|c[deny[i]/32].inheritable)&(1U<<(deny[i]%32))));assert(prctl(PR_CAPBSET_READ,deny[i])==0);}
 assert(prctl(PR_GET_NO_NEW_PRIVS,0,0,0,0)==1);
 errno=0;assert(chroot("/")==-1 && errno==EPERM);
 errno=0;int fd=open("/proc/sys/kernel/hostname",O_WRONLY);fprintf(stderr,"readonly control: fd=%d errno=%d\n",fd,errno);assert(fd<0 && (errno==EROFS || errno==EACCES || errno==EPERM));

 assert(!prctl(PR_SET_NAME,"emulationstatio"));
 pid_t frontend=fork();assert(frontend>=0);
 if(!frontend){execl("/emulationstation","emulationstation","frontend",NULL);_exit(127);}
 int status;assert(waitpid(frontend,&status,0)==frontend && WIFEXITED(status) && WEXITSTATUS(status)==0);
 assert(!prctl(PR_SET_NAME,"test-standalone"));
 int gate[2];assert(!pipe(gate));char ready;
 pid_t direct=fork();assert(direct>=0);if(!direct){assert(write(gate[1],"d",1)==1);for(;;)pause();}
 assert(read(gate[0],&ready,1)==1);assert(exit_game(direct));
 assert(waitpid(direct,&status,0)==direct && WIFSIGNALED(status));
 close(gate[0]);close(gate[1]);
 pid_t child=fork();assert(child>=0);if(!child){for(;;)pause();}
 FILE *f=fopen("/ready","w");assert(f);fputs("ready",f);fclose(f);
 if(argc==2 && !strcmp(argv[1],"wait")){for(;;)pause();}
 return 37;
}
'''
 (b/'payload.c').write_text(source.replace('GUEST_SOURCE',str(w/'PortMaster/subsystem/andr36oid-guest.c')))
 subprocess.run(['cc','-static','-O2',str(b/'payload.c'),'-o',str(root/'payload')],check=True)
 os.link(root/'payload',root/'emulationstation')
 def processes():
  found=[]
  for proc in Path('/proc').glob('[0-9]*'):
   try:
    if os.readlink(proc/'root')==str(root):found.append(int(proc.name))
   except OSError:pass
  return found
 def wait_for(test):
  for _ in range(100):
   if test():return
   time.sleep(.05)
  raise AssertionError('timeout')
 for mode in ['exit','term','parent-death']:
  (root/'ready').unlink(missing_ok=True)
  sentinel=os.open('/',os.O_RDONLY);os.dup2(sentinel,55);os.close(sentinel)
  p=subprocess.Popen([str(b/'guest'),str(root),'generic','quiet','--','/payload','exit' if mode=='exit' else 'wait'],pass_fds=(55,))
  os.close(55)
  try:
   wait_for(lambda:(root/'ready').exists() or p.poll() is not None)
   assert (root/'ready').exists(),p.poll()
   if mode!='exit':p.send_signal(signal.SIGTERM if mode=='term' else signal.SIGKILL)
   rc=p.wait(timeout=5)
   assert rc==({'exit':37,'term':0,'parent-death':-9}[mode]),(mode,rc)
   wait_for(lambda:not processes())
  finally:
   if p.poll() is None:p.kill();p.wait(timeout=5)

 print('PASS: exit-game preserves ES/input helpers and stops standalone ports; PID/UTS isolation, capability restrictions, read-only proc controls, exit-code propagation, TERM cleanup and parent-death cleanup')
