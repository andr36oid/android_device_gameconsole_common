#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <drm/drm.h>
#include <drm/drm_mode.h>
#include <stdint.h>
#include <linux/capability.h>
#include <sys/syscall.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/sysmacros.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define PR_ANDR36OID_GUEST 0x41333601
static volatile sig_atomic_t stopping;
static void stop(int signo) { (void)signo; stopping = 1; }
static long long now_ms(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static void fatal(const char *what) { perror(what); exit(125); }
/* dArkOSen's gamma tool uses the legacy CRTC gamma ioctl. Save that state before
 * the handoff and restore it after guest FDs close, before Android reacquires DRM. */
static int gamma_state(const char *path,bool restore) {
    errno=0;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);if(fd<0) {perror("gamma DRM");return 1;}
    char temporary[4096];
    if(snprintf(temporary,sizeof(temporary),"%s.tmp",path)>=(int)sizeof(temporary)) {close(fd);return 1;}
    FILE *f=fopen(restore?path:temporary,restore?"re":"we");
    if(!f) {close(fd);return 1;}
    if(!restore) fchmod(fileno(f),0600);
    uint32_t header[2]={0x41334731,0},ids[64];int result=1;
    if(restore) {
        if(fread(header,sizeof(header),1,f)!=1 || header[0]!=0x41334731 || header[1]>64)goto done;
        if(header[1] && ioctl(fd,DRM_IOCTL_SET_MASTER,0)) {perror("restore gamma master");goto done;}
    } else {
        struct drm_mode_card_res res={0};
        if(ioctl(fd,DRM_IOCTL_MODE_GETRESOURCES,&res) || res.count_crtcs>64)goto done;
        uint32_t count=res.count_crtcs;res.crtc_id_ptr=(uintptr_t)ids;
        if(ioctl(fd,DRM_IOCTL_MODE_GETRESOURCES,&res) || res.count_crtcs>count)goto done;
        header[1]=res.count_crtcs;
        if(fwrite(header,sizeof(header),1,f)!=1)goto done;
    }
    for(uint32_t i=0;i<header[1];i++) {
        uint32_t entry[2];
        if(restore) {if(fread(entry,sizeof(entry),1,f)!=1 || entry[1]>65536)goto done;}
        else {
            struct drm_mode_crtc crtc={.crtc_id=ids[i]};
            if(ioctl(fd,DRM_IOCTL_MODE_GETCRTC,&crtc) || crtc.gamma_size>65536)goto done;
            entry[0]=ids[i];entry[1]=crtc.mode_valid?crtc.gamma_size:0;
            if(fwrite(entry,sizeof(entry),1,f)!=1)goto done;
        }
        uint32_t size=entry[1];if(!size)continue;
        uint16_t *ramps=calloc(size,3*sizeof(*ramps));if(!ramps)goto done;
        struct drm_mode_crtc_lut lut={.crtc_id=entry[0],.gamma_size=size,
            .red=(uintptr_t)ramps,.green=(uintptr_t)(ramps+size),.blue=(uintptr_t)(ramps+2*size)};
        bool ok;
        if(restore) ok=fread(ramps,sizeof(*ramps),3*size,f)==3*size && !ioctl(fd,DRM_IOCTL_MODE_SETGAMMA,&lut);
        else ok=!ioctl(fd,DRM_IOCTL_MODE_GETGAMMA,&lut) && fwrite(ramps,sizeof(*ramps),3*size,f)==3*size;
        free(ramps);if(!ok)goto done;
    }
    if(restore) {if(fgetc(f)!=EOF)goto done;}
    else if(fflush(f) || fsync(fileno(f)) || rename(temporary,path))goto done;
    result=0;
done:
    if(result) {if(!errno)errno=EINVAL;perror(restore?"restore gamma":"save gamma");}
    fclose(f);close(fd);if(!restore)unlink(temporary);
    return result;
}
static void mirror_inputs(void) {
    DIR *d=opendir("/sys/class/input"); if(!d) return;
    mkdir("/dev/input",0755);mkdir("/dev/input/by-path",0755);
    mkdir("/run/udev",0755);mkdir("/run/udev/data",0755);
    struct dirent *e; char path[256],node[256],buf[256],temporary[272];
    while((e=readdir(d))) {
        if(strncmp(e->d_name,"event",5) && strncmp(e->d_name,"js",2)) continue;
        if(strlen(e->d_name)>32) continue;
        snprintf(path,sizeof(path),"/sys/class/input/%s/dev",e->d_name);
        FILE *f=fopen(path,"re"); if(!f) continue;
        unsigned major_num,minor_num; int n=fscanf(f,"%u:%u",&major_num,&minor_num);fclose(f);
        if(n!=2) continue;
        snprintf(node,sizeof(node),"/dev/input/%s",e->d_name);
        struct stat st; dev_t dev=makedev(major_num,minor_num);
        if(lstat(node,&st)==0 && (!S_ISCHR(st.st_mode)||st.st_rdev!=dev)) unlink(node);
        if(mknod(node,S_IFCHR|0666,dev) && errno!=EEXIST) continue;
        if(strncmp(e->d_name,"event",5)) continue;
        int fd=open(node,O_RDONLY|O_NONBLOCK|O_CLOEXEC);if(fd<0) continue;
        unsigned long keys[(KEY_MAX+8*sizeof(long))/(8*sizeof(long))];memset(keys,0,sizeof(keys));
        bool pad=false;
        if(ioctl(fd,EVIOCGBIT(EV_KEY,sizeof(keys)),keys)>=0)
            for(unsigned key=BTN_JOYSTICK;key<=BTN_THUMBR;key++)
                if(keys[key/(8*sizeof(long))]&(1UL<<(key%(8*sizeof(long))))) pad=true;
        snprintf(path,sizeof(path),"/run/udev/data/c%u:%u",major_num,minor_num);
        snprintf(temporary,sizeof(temporary),"%s.tmp",path);
        f=fopen(temporary,"we");
        if(f) { fprintf(f,"I:1\nE:ID_INPUT=1\nE:ID_INPUT_%s=1\nE:DEVNAME=%s\nV:1\n",pad?"JOYSTICK":"KEYBOARD",node); if(fclose(f)==0 && rename(temporary,path)) perror("udev record"); }
        if(pad && ioctl(fd,EVIOCGNAME(sizeof(buf)),buf)>=0 && !strcmp(buf,"GO-Super Gamepad")) {
            unlink("/dev/input/by-path/platform-odroidgo3-joypad-event-joystick");
            snprintf(path,sizeof(path),"../%s",e->d_name);
            if(symlink(path,"/dev/input/by-path/platform-odroidgo3-joypad-event-joystick")) perror("pad alias");
        }
        close(fd);
    }
    closedir(d);
}
static int pad_open(void) {
    char path[128], name[128];
    for (int i=0; i<128; ++i) {
        snprintf(path,sizeof(path),"/dev/input/event%d",i);
        int fd=open(path,O_RDONLY|O_NONBLOCK|O_CLOEXEC);
        if(fd<0) continue;
        if(ioctl(fd,EVIOCGNAME(sizeof(name)),name)>=0 && !strcmp(name,"GO-Super Gamepad")) return fd;
        close(fd);
    }
    return -1;
}
static pid_t parent_of(pid_t pid) {
    char path[64], buf[4096];
    snprintf(path,sizeof(path),"/proc/%d/stat",pid);
    FILE *f=fopen(path,"re"); if(!f) return -1;
    char *ok=fgets(buf,sizeof(buf),f); fclose(f);
    if(!ok) return -1;
    char *end=strrchr(buf,')'); int parent=-1;
    if(end) sscanf(end+1," %*c %d",&parent);
    return parent;
}
static bool process_named(pid_t pid, const char *name) {
    char path[64], buf[256];
    snprintf(path,sizeof(path),"/proc/%d/comm",pid);
    FILE *f=fopen(path,"re"); if(!f) return false;
    char *ok=fgets(buf,sizeof(buf),f); fclose(f);
    if(!ok) return false;
    buf[strcspn(buf,"\n")]=0; return !strcmp(buf,name);
}
static void signal_tree(pid_t root,int sig,unsigned depth) {
    if(root<=1 || depth>64) return;
    DIR *d=opendir("/proc"); if(!d) return;
    struct dirent *e;
    while((e=readdir(d))) {
        pid_t p=atoi(e->d_name);
        if(p>1 && p!=getpid() && parent_of(p)==root) signal_tree(p,sig,depth+1);
    }
    closedir(d); kill(root,sig);
}
/* Work only on this PID namespace's processes. Keep ES and its input helpers
 * alive; killing the game's shell subtree lets ES's system() return normally. */
static bool exit_game(pid_t payload) {
    DIR *d=opendir("/proc"); if(!d) return false;
    struct dirent *e; pid_t es=0; bool killed=false;
    while((e=readdir(d))) {
        pid_t p=atoi(e->d_name);
        if(p>1 && process_named(p,"emulationstatio")) { es=p; break; }
    }
    rewinddir(d);
    if(es) {
        while((e=readdir(d))) {
            pid_t p=atoi(e->d_name);
            if(p<=1 || parent_of(p)!=es || process_named(p,"gptokeyb") || process_named(p,"gptokeyb2")) continue;
            signal_tree(p,SIGTERM,0); killed=true;
        }
    } else { signal_tree(payload,SIGTERM,0); killed=true; }
    closedir(d); return killed;
}
/* Guest programs cannot reload drivers, suspend/reboot the host, change its
 * network configuration, or remount shared kernel controls writable. */
static void limit_guest(void) {
    struct __user_cap_header_struct header={.version=_LINUX_CAPABILITY_VERSION_3,.pid=0};
    struct __user_cap_data_struct caps[2];
    if(syscall(SYS_capget,&header,caps)) fatal("capget");
    const int deny[]={CAP_SYS_MODULE,CAP_SYS_RAWIO,CAP_SYS_BOOT,CAP_SYS_TIME,CAP_NET_ADMIN,CAP_SYS_ADMIN,CAP_MKNOD};
    for(unsigned i=0;i<sizeof(deny)/sizeof(*deny);i++) {
        int cap=deny[i];
        if(prctl(PR_CAPBSET_DROP,cap,0,0,0)) fatal("capability bounding set");
        unsigned mask=~(1U<<(cap%32));
        caps[cap/32].effective&=mask;caps[cap/32].permitted&=mask;caps[cap/32].inheritable&=mask;
    }
    if(syscall(SYS_capset,&header,caps)) fatal("capset");
}
static int supervise(char **argv,bool native,bool hotkeys) {
    if(native && prctl(PR_ANDR36OID_GUEST,1UL,0UL,0UL,0UL)) fatal("guest input ABI unavailable; update the kernel");
    struct sigaction sa={.sa_handler=stop}; sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM,&sa,NULL); sigaction(SIGINT,&sa,NULL); sigaction(SIGHUP,&sa,NULL);
    bool inputs=access("/dev/input",F_OK)==0;
    if(inputs) mirror_inputs();
    pid_t payload=fork(); if(payload<0) fatal("fork payload");
    if(!payload) {
        signal(SIGTERM,SIG_DFL); signal(SIGINT,SIG_DFL); signal(SIGHUP,SIG_DFL);
        limit_guest();
        // Internal shared storage must also remain writable by Android media_rw.
        if(native) { if(setgid(1023))fatal("shared storage group");umask(0002); }
        execvp(argv[0],argv); fatal("exec guest");
    }
    int pad=hotkeys?pad_open():-1, status=0; long long combo=0, force=0, last_retry=0;
    long long mirrored=now_ms();
    bool select=false,start=false,fn=false,fired=false;
    for(;;) {
        pid_t done;
        while((done=waitpid(-1,&status,WNOHANG))>0) {
            if(done==payload) { kill(-1,SIGTERM); return WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status); }
        }
        if(stopping) {
            kill(-1,SIGTERM);
            for(int n=0;n<20;++n) { if(waitpid(payload,&status,WNOHANG)==payload) return 0; usleep(100000); }
            kill(-1,SIGKILL); return 0;
        }
        long long now=now_ms();
        if(inputs && now-mirrored>2000) { mirror_inputs();mirrored=now; }
        if(hotkeys && pad<0 && now-last_retry>2000) { pad=pad_open(); last_retry=now; }
        struct pollfd poller={.fd=pad,.events=POLLIN}; poll(&poller,1,50);
        struct input_event ev;
        if(poller.revents&(POLLHUP|POLLERR)) { close(pad);pad=-1;select=start=fn=false; }
        while(pad>=0 && read(pad,&ev,sizeof(ev))==sizeof(ev)) {
            if(ev.type==EV_SYN && ev.code==SYN_DROPPED) {
                unsigned long keys[(KEY_MAX+8*sizeof(long))/(8*sizeof(long))];
                memset(keys,0,sizeof(keys));
                if(ioctl(pad,EVIOCGKEY(sizeof(keys)),keys)>=0) {
#define HELD(k) ((keys[(k)/(8*sizeof(long))] >> ((k)%(8*sizeof(long))))&1)
                    select=HELD(native?BTN_TRIGGER_HAPPY1:BTN_SELECT);start=HELD(native?BTN_TRIGGER_HAPPY2:BTN_START);fn=HELD(native?BTN_TRIGGER_HAPPY5:KEY_HOMEPAGE);
                } else select=start=fn=false;
            }
            if(ev.type!=EV_KEY) continue;
            if(ev.code==(native?BTN_TRIGGER_HAPPY1:BTN_SELECT)) select=ev.value!=0;
            if(ev.code==(native?BTN_TRIGGER_HAPPY2:BTN_START)) start=ev.value!=0;
            if(ev.code==(native?BTN_TRIGGER_HAPPY5:KEY_HOMEPAGE)) fn=ev.value!=0;
        }
        if(fn&&start) { if(!force) force=now; if(now-force>=2000) stopping=1; } else force=0;
        if(select&&start&&!fn) {
            if(!combo) combo=now;
            if(now-combo>=1200 && !fired) { exit_game(payload); fired=true; }
            /* A stuck game must not defeat the escape chord. */
            if(now-combo>=4000) stopping=1;
        } else { combo=0; fired=false; }
    }
}
int main(int argc,char **argv) {
    if(argc==3 && !strcmp(argv[1],"--save-gamma"))return gamma_state(argv[2],false);
    if(argc==3 && !strcmp(argv[1],"--restore-gamma"))return gamma_state(argv[2],true);
    if(argc==2 && !strcmp(argv[1],"--probe")) {
        if(unshare(CLONE_NEWPID|CLONE_NEWIPC)) return 125;
        pid_t p=fork(); if(p<0) return 125;
        if(!p) _exit(prctl(PR_ANDR36OID_GUEST,1UL,0UL,0UL,0UL)?125:0);
        int status; if(waitpid(p,&status,0)!=p)return 125;
        return WIFEXITED(status)?WEXITSTATUS(status):125;
    }
    if(argc==4 && !strcmp(argv[1],"--bind")) {
        if(mount(argv[2],argv[3],NULL,MS_BIND,NULL)) fatal("bind guest hardware control");
        return 0;
    }
    if(argc<6 || strcmp(argv[4],"--")) { fprintf(stderr,"usage: andr36oid-guest ROOT native|generic hotkeys|quiet -- command...\n"); return 2; }
    bool native=!strcmp(argv[2],"native")||!strcmp(argv[2],"native-init"),hotkeys=!strcmp(argv[3],"hotkeys");
    int gate[2];
    if(socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,gate)) fatal("guest handshake");
    if(unshare(CLONE_NEWPID|CLONE_NEWNS|CLONE_NEWIPC|CLONE_NEWUTS)) fatal("unshare guest");
    if(mount(NULL,"/",NULL,MS_REC|MS_PRIVATE,NULL)) fatal("private mounts");
    pid_t init=fork(); if(init<0) fatal("fork init");
    if(init) {
        struct sigaction sa={.sa_handler=stop};sigemptyset(&sa.sa_mask);
        sigaction(SIGTERM,&sa,NULL);sigaction(SIGINT,&sa,NULL);sigaction(SIGHUP,&sa,NULL);
        close(gate[1]); char byte;
        if(read(gate[0],&byte,1)!=1 || write(gate[0],"1",1)!=1) {kill(init,SIGKILL);return 125;}
        close(gate[0]);
        int status; for(;;) {
            if(stopping) kill(init,SIGTERM);
            if(waitpid(init,&status,0)==init) return WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status);
            if(errno!=EINTR) fatal("wait guest");
        }
    }
    close(gate[0]);
    if(prctl(PR_SET_PDEATHSIG,SIGKILL)) fatal("guest parent death");
    char byte;
    if(write(gate[1],"1",1)!=1 || read(gate[1],&byte,1)!=1) return 125;
    close(gate[1]);
    if(chroot(argv[1]) || chdir("/")) fatal("chroot");
    if(mount("proc","/proc","proc",MS_NOSUID|MS_NODEV|MS_NOEXEC,NULL)) fatal("guest proc");
    const char *readonly[]={"/proc/sys","/proc/sysrq-trigger","/proc/irq","/proc/bus"};
    for(unsigned i=0;i<sizeof(readonly)/sizeof(*readonly);i++) {
        if(access(readonly[i],F_OK)) continue;
        if(mount(readonly[i],readonly[i],NULL,MS_BIND,NULL) ||
           mount(NULL,readonly[i],NULL,MS_BIND|MS_REMOUNT|MS_RDONLY|MS_NOSUID|MS_NODEV|MS_NOEXEC,NULL)) fatal("read-only host proc controls");
    }
    if(sethostname("andr36oid-linux",sizeof("andr36oid-linux")-1)) fatal("hostname");
    if(!strcmp(argv[2],"init") || !strcmp(argv[2],"native-init")) {
        if(native && prctl(PR_ANDR36OID_GUEST,1UL,0UL,0UL,0UL)) fatal("guest input ABI unavailable");
        setenv("container","andr36oid-subsystem",1);
        if(mount("tmpfs","/sys/fs/cgroup","tmpfs",MS_NOSUID|MS_NODEV|MS_NOEXEC,"mode=0755")==0) {
            if(mkdir("/sys/fs/cgroup/systemd",0755) && errno!=EEXIST) fatal("cgroup directory");
            if(mount("cgroup","/sys/fs/cgroup/systemd","cgroup",MS_NOSUID|MS_NODEV|MS_NOEXEC,"none,name=systemd")) perror("guest systemd hierarchy");
        }
        limit_guest();
        execvp(argv[5],argv+5); fatal("exec init");
    }
    return supervise(argv+5,native,hotkeys);
}
