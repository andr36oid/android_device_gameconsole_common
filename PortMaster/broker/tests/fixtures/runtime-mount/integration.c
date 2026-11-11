#define _GNU_SOURCE
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <linux/loop.h>
#include <linux/capability.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#define CK(x) do{if(!(x)){printf("RUNTIME_MOUNT_INTEGRATION_FAIL line%d errno%d %s\n",__LINE__,errno,strerror(errno));fflush(stdout);if(getpid()==1)reboot(RB_POWER_OFF);_exit(99);}}while(0)
static const char *source="/roms/ports/a port with spaces/runtime/long directory name for a runtime beyond losetup limits/frt_3.5.2.aarch64.squashfs";
static const char *target="/roms/ports/a port with spaces/.runtime";
static void dir(const char *p){CK(!mkdir(p,0777)||errno==EEXIST);}
static void put(const char *p){int fd=open(p,O_CREAT|O_WRONLY,0666);CK(fd>=0);close(fd);}
static int run(char *const args[]){pid_t p=fork();CK(p>=0);if(!p){execv(args[0],args);perror(args[0]);_exit(127);}int s;CK(waitpid(p,&s,0)==p&&WIFEXITED(s));return WEXITSTATUS(s);}
static int call(const char *verb,const char *opts,const char *file,const char *to){
    char *args[8];int i=0;args[i++]=!strcmp(verb,"mount")?"/bin/mount":"/bin/umount";
    if(opts){if(!strcmp(verb,"mount"))args[i++]="-o";args[i++]=(char *)opts;}
    if(file)args[i++]=(char *)file;args[i++]=(char *)to;args[i]=NULL;return run(args);
}
static void loose_loop(void){CK(!run((char *[]){"/bin/losetup","--find","--show",(char *)source,NULL}));CK(!run((char *[]){"/bin/losetup","--find","--show","/misc/image.sq",NULL}));}
static int squash(const char *p){struct statfs s;CK(!statfs(p,&s));return s.f_type==0x73717368;}
static void read_marker(void){char path[4096],b[32]={0};snprintf(path,sizeof(path),"%s/marker",target);int fd=open(path,O_RDONLY);CK(fd>=0&&read(fd,b,sizeof(b))>0&&!strcmp(b,"runtime works\n"));close(fd);}
static void wait_marker(const char *p){for(int i=0;access(p,F_OK)&&i<6000;i++)usleep(10000);CK(!access(p,F_OK));}
static void leak(void){CK(!call("mount","loop,ro",source,target));loose_loop();put("/run/test-leak-ready");wait_marker("/run/test-leak-continue");}
static void port(void){
    puts("PORT_TEST_BEGIN");
    CK(prctl(PR_GET_NO_NEW_PRIVS,0,0,0,0)==1);
    struct __user_cap_header_struct h={.version=_LINUX_CAPABILITY_VERSION_3};struct __user_cap_data_struct c[2];CK(!syscall(SYS_capget,&h,c));CK(c[0].effective&(1U<<CAP_SYS_ADMIN));CK(prctl(PR_CAPBSET_READ,CAP_SYS_ADMIN)==1);CK(!(c[0].effective&(1U<<CAP_MKNOD)));
    CK(access("/dev/loop0",F_OK)&&access("/dev/loop1",F_OK));CK(access("/dev/mmcblk0",F_OK)&&access("/dev/mmcblk1",F_OK)&&access("/dev/block",F_OK));
    errno=0;CK(mknod("/dev/mmcblk0",S_IFBLK|0600,makedev(179,0))<0&&errno==EPERM);
    errno=0;CK(open("/sys/power/state",O_WRONLY)<0&&errno==EROFS);
    errno=0;CK(open("/proc/sys/kernel/hostname",O_WRONLY)<0&&(errno==EROFS||errno==EACCES));
    errno=0;CK(mount(NULL,"/roms",NULL,MS_REMOUNT|MS_RDONLY,NULL)<0&&errno==EPERM);
    errno=0;CK(syscall(SYS_swapon,source,0)<0&&errno==EPERM);errno=0;CK(syscall(SYS_swapoff,source)<0&&errno==EPERM);
    CK(!sethostname("guest-port",10));
    // Real tmpfs, bind, bind-remount, overlay and unmount operations.
    dir("/tmp/work");CK(!mount("tmpfs","/tmp/work","tmpfs",0,"size=16m"));
    for(const char **p=(const char *[]){"/tmp/work/lower","/tmp/work/upper","/tmp/work/work","/tmp/work/merged",NULL};*p;p++)dir(*p);
    put("/tmp/work/lower/original");CK(!mount("overlay","/tmp/work/merged","overlay",0,"lowerdir=/tmp/work/lower,upperdir=/tmp/work/upper,workdir=/tmp/work/work"));
    put("/tmp/work/merged/new");CK(!access("/tmp/work/upper/new",F_OK)&&access("/tmp/work/lower/new",F_OK));CK(!umount2("/tmp/work/merged",0));
    CK(!mount("/tmp/work",target,NULL,MS_BIND,NULL));put("/roms/ports/a port with spaces/.runtime/private-only");
    CK(!mount(NULL,target,NULL,MS_BIND|MS_REMOUNT|MS_RDONLY,NULL));errno=0;CK(open("/roms/ports/a port with spaces/.runtime/no-write",O_CREAT|O_WRONLY,0600)<0&&errno==EROFS);
    put("/tmp/work/still-writable");CK(!umount2(target,0));CK(!umount2("/tmp/work",0));puts("GENERAL_MOUNT_PASS bind tmpfs overlay bind-remount umount");
    const char *opts[]={"loop,ro","loop",NULL};
    for(unsigned i=0;i<3;i++){
        printf("PORT_FORM %u\n",i);CK(!call("mount",opts[i],source,target));CK(squash(target));read_marker();
        pid_t app=fork();CK(app>=0);if(!app){char exe[4096];snprintf(exe,sizeof(exe),"%s/runtime-app",target);execl(exe,exe,NULL);_exit(127);}int s;CK(waitpid(app,&s,0)==app&&WIFEXITED(s)&&WEXITSTATUS(s)==23);
        CK(!call("umount",i==1?"-l":NULL,NULL,target));CK(!squash(target));
    }
    CK(!call("mount","loop,ro",source,target));char path[4096];snprintf(path,sizeof(path),"%s/marker",target);int held=open(path,O_RDONLY);CK(held>=0);CK(!call("umount","-l",NULL,target));char b[32];CK(read(held,b,sizeof(b))>0);close(held);
    CK(!call("mount","loop,ro",source,target));loose_loop();read_marker();put("/run/test-port-ready");wait_marker("/run/test-port-continue");
    // Deliberately leave both mounted and manually attached loops behind.
}
static void frontend(void){
    pid_t p=fork();CK(p>=0);if(!p){execl("/payload","/payload","port",NULL);_exit(127);}
    wait_marker("/run/test-port-ready");wait_marker("/run/test-observer-checked");put("/run/test-port-continue");int s;CK(waitpid(p,&s,0)==p&&WIFEXITED(s)&&!WEXITSTATUS(s));
    for(int i=0;squash(target)&&i<500;i++)usleep(10000);CK(!squash(target));puts("PORT_EXIT_CLEANUP_PASS");
    put("/run/test-ready");wait_marker("/run/test-continue");
    p=fork();CK(p>=0);if(!p){execl("/payload","/payload","leak",NULL);_exit(127);}CK(waitpid(p,&s,0)==p&&WIFEXITED(s)&&!WEXITSTATUS(s));
    // Removing only this namespace's shared-card mount cannot unmount Android's.
    CK(!umount2("/roms",MNT_DETACH));puts("GUEST_CARD_UNMOUNT_PASS");
}
static void copy(const char *src,const char *dst){int a=open(src,O_RDONLY),b=open(dst,O_CREAT|O_WRONLY|O_TRUNC,0666);CK(a>=0&&b>=0);char buf[8192];ssize_t n;while((n=read(a,buf,sizeof(buf)))>0)CK(write(b,buf,n)==n);CK(!n);close(a);close(b);}
static pid_t guest_pid;
static void wait_file(const char *p){for(int i=0;access(p,F_OK)&&i<12000;i++){int dead;pid_t done=waitpid(guest_pid,&dead,WNOHANG);if(done==guest_pid){printf("GUEST_EARLY_EXIT status=%d\n",dead);CK(0);}usleep(10000);}CK(!access(p,F_OK));}
static struct loop_info64 protected[2];
static int loop_open(int n){char p[64];snprintf(p,sizeof(p),"/dev/loop%d",n);mknod(p,S_IFBLK|0600,makedev(7,n));return open(p,O_RDONLY);}
static void protect_loops(void){for(int n=0;n<2;n++){char p[80];snprintf(p,sizeof(p),"/android-%s.img",n?"rootfs":"apex");int backing=open(p,O_CREAT|O_RDWR,0600);CK(backing>=0&&!ftruncate(backing,1<<20));int fd=loop_open(n);CK(fd>=0&&!ioctl(fd,LOOP_SET_FD,backing));struct loop_info64 i={0};snprintf((char *)i.lo_file_name,LO_NAME_SIZE,"/data/apex/active/%d.apex",n);CK(!ioctl(fd,LOOP_SET_STATUS64,&i));CK(!ioctl(fd,LOOP_GET_STATUS64,&protected[n]));close(fd);close(backing);}}
static int loop_count(void){int count=0;for(int n=0;n<64;n++){int fd=loop_open(n);if(fd<0)continue;struct loop_info64 i;int rc=ioctl(fd,LOOP_GET_STATUS64,&i),e=errno;close(fd);if(n<2)CK(!rc&&i.lo_inode==protected[n].lo_inode&&i.lo_device==protected[n].lo_device);else if(!rc)count++;else CK(e==ENXIO);}return count;}
static void no_guest_loops(void){int count=0;for(int k=0;k<500;k++){count=loop_count();if(!count)return;usleep(10000);}printf("LOOPS_LEFT %d\n",count);CK(0);}
int main(int argc,char **argv){setbuf(stdout,NULL);
    if(argc>1&&!strcmp(argv[1],"port")){port();return 0;}
    if(argc>1&&!strcmp(argv[1],"leak")){leak();return 0;}
    if(argc>1&&!strcmp(argv[1],"frontend")){frontend();return 0;}
    dir("/proc");dir("/sys");dir("/dev");CK(!mount("proc","/proc","proc",0,NULL));CK(!mount("sysfs","/sys","sysfs",0,NULL));CK(!mount("devtmpfs","/dev","devtmpfs",0,NULL));dir("/card");dir("/state");
    for(int i=0;access("/dev/vda",F_OK)&&i<500;i++)usleep(10000);CK(!mount("/dev/vda","/card","exfat",MS_NODEV|MS_NOSUID,"uid=0,gid=0,fmask=0000,dmask=0000"));
    dir("/card/ports");dir("/card/ports/a port with spaces");dir("/card/ports/a port with spaces/runtime");dir("/card/ports/a port with spaces/runtime/long directory name for a runtime beyond losetup limits");dir("/card/ports/a port with spaces/.runtime");char dest[4096];snprintf(dest,sizeof(dest),"/card%s",source+5);copy("/runtime.squashfs",dest);
    dir("/g");for(const char **p=(const char *[]){"/g/proc","/g/sys","/g/dev","/g/run","/g/tmp","/g/roms","/g/misc",NULL};*p;p++)dir(*p);
    copy("/runtime.squashfs","/g/misc/image.sq");
    copy("/init","/g/payload");CK(!chmod("/g/payload",0755));copy("/init","/g/emulationstation");CK(!chmod("/g/emulationstation",0755));CK(!mknod("/g/dev/null",S_IFCHR|0666,makedev(1,3)));
    CK(!mount("/sys","/g/sys",NULL,MS_BIND|MS_REC,NULL));CK(!mount(NULL,"/g/sys",NULL,MS_BIND|MS_REMOUNT|MS_RDONLY|MS_NOSUID|MS_NODEV|MS_NOEXEC,NULL));protect_loops();
    char android_host[80];CK(!gethostname(android_host,sizeof(android_host)));
    for(int sd=0;sd<2;sd++)for(int clean=0;clean<2;clean++){
        const char *markers[]={"test-port-ready","test-port-continue","test-ready","test-continue","test-leak-ready","test-leak-continue","test-observer-checked"};for(unsigned i=0;i<7;i++){snprintf(dest,sizeof(dest),"/g/run/%s",markers[i]);unlink(dest);}
        CK(!mount("/card","/g/roms",sd?"sdcardfs":NULL,sd?MS_NODEV|MS_NOSUID:MS_BIND,sd?"fsuid=0,fsgid=0,gid=0,mask=0":NULL));
        pid_t helper=fork();CK(helper>=0);if(!helper){CK(!setenv("A36_LOOP_JOURNAL","/state/loops",1));execl("/guest","/guest","/g","generic","quiet","--","/emulationstation","frontend",NULL);_exit(127);}
        guest_pid=helper;wait_file("/g/run/test-port-ready");snprintf(dest,sizeof(dest),"/g%s",target);CK(!squash(dest));CK(access("/card/ports/a port with spaces/.runtime/private-only",F_OK));struct statfs flags;CK(!statfs("/card",&flags)&&!(flags.f_flags&MS_RDONLY));char host[80];CK(!gethostname(host,sizeof(host))&&!strcmp(host,android_host));puts("ANDROID_NAMESPACE_UNCHANGED_PASS mounts card-flags hostname protected-loop-nodes");put("/g/run/test-observer-checked");wait_file("/g/run/test-ready");no_guest_loops();
        // Freeze the observer so manual losetup is killed BEFORE it can be
        // journaled. The baseline + canonical guest backing filename recover it.
        if(!clean)CK(!kill(helper,SIGSTOP));put("/g/run/test-continue");wait_file("/g/run/test-leak-ready");int status;
        if(clean){put("/g/run/test-leak-continue");CK(waitpid(helper,&status,0)==helper&&WIFEXITED(status)&&!WEXITSTATUS(status));}
        else{CK(!kill(helper,SIGKILL));CK(waitpid(helper,&status,0)==helper&&WIFSIGNALED(status));usleep(500000);CK(loop_count()>0);puts("UNOBSERVED_CRASH_LOOP_PRESENT");}
        CK(!run((char *[]){"/guest","--cleanup-guest-loops","/g","/state/loops",NULL}));CK(!run((char *[]){"/guest","--cleanup-guest-loops","/g","/state/loops",NULL}));no_guest_loops();CK(access("/state/loops",F_OK));CK(!squash(dest));CK(!statfs("/card",&flags)&&!(flags.f_flags&MS_RDONLY));CK(!umount2("/g/roms",0));printf("RUNTIME_FS_PASS %s %s protected-loops-intact\n",sd?"sdcardfs-on-exfat":"exfat",clean?"clean-session":"unobserved-helper-crash");
    }
    puts("RUNTIME_MOUNT_INTEGRATION_PASS normal-util-linux bind tmpfs overlay long-path per-port session crash Android-unchanged");reboot(RB_POWER_OFF);return 0;
}
