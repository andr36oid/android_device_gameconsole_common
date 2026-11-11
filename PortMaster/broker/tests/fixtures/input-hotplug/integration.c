#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/reboot.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CK(x) do { if (!(x)) { \
    fprintf(stderr, "INPUT_HOTPLUG_FAIL line %d: %s (errno %d: %s)\n", \
            __LINE__, #x, errno, strerror(errno)); \
    if (getpid() == 1) reboot(RB_POWER_OFF); \
    _exit(99); \
} } while (0)
struct device {
    int fd;
    dev_t dev;
    char name[80], node[128], record[128];
    long long began,kernel_ready,sysfs_ready;
};
static long long ms(void) {
    struct timespec t;
    CK(!clock_gettime(CLOCK_MONOTONIC, &t));
    return (long long)t.tv_sec*1000 + t.tv_nsec/1000000;
}
static void dir(const char *p) { CK(!mkdir(p,0755) || errno==EEXIST); }
enum input_kind { KEYBOARD, PAD, MIXED_MOUSE, REL_MOUSE };
static struct device attach(const char *name, enum input_kind kind) {
    struct device d={0};
    d.fd=open("/dev/uinput",O_WRONLY|O_CLOEXEC);
    CK(d.fd>=0 && !ioctl(d.fd,UI_SET_EVBIT,EV_KEY));
    CK(!ioctl(d.fd,UI_SET_KEYBIT,kind==PAD?BTN_SOUTH:kind==REL_MOUSE?BTN_LEFT:KEY_ENTER));
    if(kind==MIXED_MOUSE || kind==REL_MOUSE) {
        CK(!ioctl(d.fd,UI_SET_EVBIT,EV_REL));
        CK(!ioctl(d.fd,UI_SET_RELBIT,REL_X));CK(!ioctl(d.fd,UI_SET_RELBIT,REL_Y));
        CK(!ioctl(d.fd,UI_SET_KEYBIT,BTN_LEFT));
    }
    struct uinput_setup setup={.id={.bustype=BUS_USB,.vendor=0x1234,.product=0x5678,.version=1}};
    snprintf(setup.name,sizeof(setup.name),"%s",name);
    snprintf(d.name,sizeof(d.name),"%s",name);
    CK(!ioctl(d.fd,UI_DEV_SETUP,&setup));
    d.began=ms();
    CK(!ioctl(d.fd,UI_DEV_CREATE));d.kernel_ready=ms();
    char sysname[64]={0},path[256],event[64]={0};
    CK(ioctl(d.fd,UI_GET_SYSNAME(sizeof(sysname)),sysname)>0);
    snprintf(path,sizeof(path),"/sys/class/input/%s",sysname);
    // Allow the kernel to finish registering evdev after the creator wakes.
    for(int i=0;i<250 && !event[0];i++) {
        DIR *entries=opendir(path);
        if(entries) {
            struct dirent *e;
            while((e=readdir(entries)))if(!strncmp(e->d_name,"event",5)) {
                snprintf(event,sizeof(event),"%.63s",e->d_name);break;
            }
            closedir(entries);
        }
        if(!event[0])usleep(1000);
    }
    CK(event[0]);
    snprintf(path,sizeof(path),"/sys/class/input/%s/dev",event);
    FILE *f=fopen(path,"r");unsigned maj,min;
    CK(f && fscanf(f,"%u:%u",&maj,&min)==2);
    fclose(f);
    d.dev=makedev(maj,min);d.sysfs_ready=ms();
    snprintf(d.node,sizeof(d.node),"/dev/input/%s",event);
    snprintf(d.record,sizeof(d.record),"/run/udev/data/c%u:%u",maj,min);
    return d;
}
static bool record_has(const char *path,const char *tag) {
    char buf[1024];
    FILE *f=fopen(path,"r");if(!f)return false;
    size_t n=fread(buf,1,sizeof(buf)-1,f);fclose(f);buf[n]=0;
    return strstr(buf,tag)!=NULL;
}
static const char alias[]="/dev/input/by-path/platform-odroidgo3-joypad-event-joystick";
static bool ready(struct device *d,bool pad) {
    struct stat s;
    return !stat(d->node,&s) && S_ISCHR(s.st_mode) && s.st_rdev==d->dev &&
        (s.st_mode&07777)==0666 && !stat(d->record,&s) && (s.st_mode&0777)==0644 &&
        record_has(d->record,pad?"E:ID_INPUT_JOYSTICK=1\n":"E:ID_INPUT_KEYBOARD=1\n") &&
        (!pad || (!stat(alias,&s) && s.st_rdev==d->dev));
}
static void wait_input(const char *mode,struct device *d,bool pad) {
    while(!ready(d,pad) && ms()-d->began<250)usleep(1000);
    long long elapsed=ms()-d->began;
    CK(elapsed<250 && ready(d,pad));
    printf("INPUT_ADD %s %s %lldms kernel-ready-to-node-and-tags=%lldms sysfs-ready-to-node-and-tags=%lldms\n",mode,d->node,elapsed,ms()-d->kernel_ready,ms()-d->sysfs_ready);
}
static bool absent(const char *path) {
    struct stat s;
    return lstat(path,&s)<0 && errno==ENOENT;
}
static long long detach(struct device *d) {
    long long start=ms();
    CK(!ioctl(d->fd,UI_DEV_DESTROY));close(d->fd);
    return start;
}
static void wait_gone(const char *mode,struct device *d,long long start) {
    while((!absent(d->node) || !absent(d->record)) && ms()-start<250)usleep(1000);
    CK(absent(d->node) && absent(d->record));
    printf("INPUT_REMOVE %s %s %lldms\n",mode,d->node,ms()-start);
}
static void keyboard_read_once(struct device *d) {
    // Model a runtime that opens the keyboard only on startup; a real key
    // through the mirrored node must reach its original evdev stream.
    int fd=open(d->node,O_RDONLY|O_NONBLOCK|O_CLOEXEC);CK(fd>=0);
    char name[80]={0};
    CK(ioctl(fd,EVIOCGNAME(sizeof(name)),name)>0 && !strcmp(name,d->name));
    struct input_event write_events[]={
        {.type=EV_KEY,.code=KEY_ENTER,.value=1},
        {.type=EV_SYN,.code=SYN_REPORT}
    };
    CK(write(d->fd,write_events,sizeof(write_events))==(ssize_t)sizeof(write_events));
    struct pollfd p={.fd=fd,.events=POLLIN};CK(poll(&p,1,250)==1 && p.revents&POLLIN);
    struct input_event events[8];ssize_t n=read(fd,events,sizeof(events));
    bool key=false;
    for(ssize_t i=0;i<n/(ssize_t)sizeof(events[0]);i++)
        if(events[i].type==EV_KEY && events[i].code==KEY_ENTER && events[i].value==1)key=true;
    CK(key);close(fd);
}
static void mouse_read_once(const char *mode,enum input_kind kind) {
    struct device d=attach(kind==MIXED_MOUSE?"Fake Keyboard":"Relative Mouse",kind);
    while(ms()-d.began<250 && !record_has(d.record,"E:ID_INPUT_MOUSE=1\n"))usleep(1000);
    CK(record_has(d.record,"E:ID_INPUT_MOUSE=1\n"));
    CK(record_has(d.record,"E:ID_INPUT_KEYBOARD=1\n")== (kind==MIXED_MOUSE));
    CK(record_has(d.record,"E:ID_INPUT_KEY=1\n")== (kind==MIXED_MOUSE));
    CK(!record_has(d.record,"E:ID_INPUT_JOYSTICK=1\n"));
    printf("INPUT_MOUSE_READY %s %s kernel-ready-to-node-and-tags=%lldms\n",mode,kind==MIXED_MOUSE?"combined":"pure",ms()-d.kernel_ready);
    int fd=open(d.node,O_RDONLY|O_NONBLOCK|O_CLOEXEC);CK(fd>=0);
    struct input_event sent[]={
        {.type=EV_REL,.code=REL_X,.value=13},
        {.type=EV_REL,.code=REL_Y,.value=-7},
        {.type=EV_KEY,.code=BTN_LEFT,.value=1},
        {.type=EV_SYN,.code=SYN_REPORT}
    };
    CK(write(d.fd,sent,sizeof(sent))==(ssize_t)sizeof(sent));
    struct pollfd p={.fd=fd,.events=POLLIN};CK(poll(&p,1,250)==1);
    struct input_event received[16];ssize_t count=read(fd,received,sizeof(received));
    bool x=false,y=false,button=false;
    for(ssize_t i=0;i<count/(ssize_t)sizeof(received[0]);i++) {
        if(received[i].type==EV_REL && received[i].code==REL_X && received[i].value==13)x=true;
        if(received[i].type==EV_REL && received[i].code==REL_Y && received[i].value==-7)y=true;
        if(received[i].type==EV_KEY && received[i].code==BTN_LEFT && received[i].value==1)button=true;
    }
    CK(x && y && button);close(fd);
    if(kind==MIXED_MOUSE)keyboard_read_once(&d);
    long long start=detach(&d);wait_gone(mode,&d,start);
    printf("INPUT_MOUSE_PASS %s %s: correct tags, REL_X/Y, button%s\n",mode,kind==MIXED_MOUSE?"combined":"pure",kind==MIXED_MOUSE?", keyboard key":"");
}
static void processed_late_mouse(void) {
    for(int repeat=0;repeat<3;repeat++) {
        const char *markers[]={"/monitor-ready","/monitor-open","/monitor-got-input","/monitor-done"};
        for(unsigned i=0;i<sizeof(markers)/sizeof(*markers);i++)unlink(markers[i]);
        pid_t p=fork();CK(p>=0);
        if(!p) {execl("/lib/ld-linux-aarch64.so.1","ld-linux","--library-path","/lib","/udev-probe",NULL);_exit(127);}
        long long start=ms();while(access("/monitor-ready",F_OK) && ms()-start<2000)usleep(1000);
        CK(!access("/monitor-ready",F_OK));
        struct device d=attach("Late Fake Keyboard",MIXED_MOUSE);
        start=ms();while(access("/monitor-open",F_OK) && ms()-start<1000)usleep(1000);
        CK(!access("/monitor-open",F_OK));
        printf("INPUT_LATE_LIBUDEV_OPEN kernel-ready-to-open=%lldms\n",ms()-d.kernel_ready);
        struct input_event events[]={
            {.type=EV_REL,.code=REL_X,.value=19},{.type=EV_REL,.code=REL_Y,.value=-9},
            {.type=EV_KEY,.code=KEY_ENTER,.value=1},{.type=EV_SYN,.code=SYN_REPORT}
        };
        CK(write(d.fd,events,sizeof(events))==(ssize_t)sizeof(events));
        start=ms();while(access("/monitor-got-input",F_OK) && ms()-start<1000)usleep(1000);
        CK(!access("/monitor-got-input",F_OK));detach(&d);
        int status;CK(waitpid(p,&status,0)==p && WIFEXITED(status) && !WEXITSTATUS(status));
        CK(!access("/monitor-done",F_OK));
    }
}
static int payload(const char *mode,const char *seed,const char *record) {
    struct stat s;
    CK(!stat(seed,&s) && (s.st_mode&07777)==0666);
    CK(record_has(record,"E:ID_INPUT_KEYBOARD=1\n"));
    CK(umask(0077)==0077); // Node/database creation must not change payload umask.
    struct device keyboard=attach("Fake Keyboard",KEYBOARD);
    wait_input(mode,&keyboard,false);keyboard_read_once(&keyboard);
    long long start=detach(&keyboard);
    // A remove/add pair can reuse the same event number between probes.
    struct device pad=attach("GO-Super Gamepad",PAD);
    wait_input(mode,&pad,true);
    if(pad.dev!=keyboard.dev)wait_gone(mode,&keyboard,start);
    CK(!stat(alias,&s) && s.st_rdev==pad.dev);
    start=detach(&pad);wait_gone(mode,&pad,start);CK(absent(alias));
    for(int i=0;i<12;i++) {
        usleep((unsigned)(7000+i*11000));
        keyboard=attach("Fake Keyboard",KEYBOARD);
        wait_input(mode,&keyboard,false);keyboard_read_once(&keyboard);
        start=detach(&keyboard);wait_gone(mode,&keyboard,start);
    }
    mouse_read_once(mode,MIXED_MOUSE);mouse_read_once(mode,REL_MOUSE);
    if(!strcmp(mode,"netlink") && !access("/udev-probe",F_OK))processed_late_mouse();
    printf("INPUT_MODE_PASS %s: seed repaired, repeated add/remove, type swap, alias cleanup, real key delivery\n",mode);
    return 0;
}
static void deny_netlink(void) {
    // Test-only: make NETLINK unavailable without affecting the Unix handshake.
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AF_NETLINK,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EAFNOSUPPORT),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog filter={.len=sizeof(code)/sizeof(code[0]),.filter=code};
    CK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    CK(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
}
static void copy(const char *from,const char *to) {
    int src=open(from,O_RDONLY),dst=open(to,O_CREAT|O_WRONLY|O_TRUNC,0755);
    CK(src>=0 && dst>=0);
    char b[8192];ssize_t n;
    while((n=read(src,b,sizeof(b)))>0)CK(write(dst,b,n)==n);
    CK(n==0);close(src);close(dst);
}
int main(int argc,char **argv) {
    setbuf(stdout,NULL);
    if(argc==5 && !strcmp(argv[1],"payload"))return payload(argv[2],argv[3],argv[4]);
    dir("/proc");dir("/sys");dir("/dev");
    CK(!mount("proc","/proc","proc",0,NULL));CK(!mount("sysfs","/sys","sysfs",0,NULL));
    CK(!mount("devtmpfs","/dev","devtmpfs",0,NULL));
    struct device seed=attach("Seed Keyboard",KEYBOARD);
    const char *paths[]={"/g","/g/proc","/g/sys","/g/dev","/g/dev/input","/g/run","/g/tmp"};
    for(unsigned i=0;i<sizeof(paths)/sizeof(*paths);i++)dir(paths[i]);
    CK(!mount("/sys","/g/sys",NULL,MS_BIND,NULL));
    CK(!mount(NULL,"/g/sys",NULL,MS_BIND|MS_REMOUNT|MS_RDONLY,NULL));
    CK(!mknod("/g/dev/null",S_IFCHR|0666,makedev(1,3)));
    CK(!mknod("/g/dev/uinput",S_IFCHR|0666,makedev(10,223)));
    if(!access("/udev-probe",F_OK)) {
        dir("/g/lib");CK(!mount("/lib","/g/lib",NULL,MS_BIND,NULL));
        copy("/udev-probe","/g/udev-probe");
    }
    char seed_node[160],seed_record[160];
    snprintf(seed_node,sizeof(seed_node),"/g%s",seed.node);
    snprintf(seed_record,sizeof(seed_record),"/g%s",seed.record);
    CK(!mknod(seed_node,S_IFCHR|0644,seed.dev));copy("/init","/g/payload");
    for(int fallback=0;fallback<2;fallback++) {
        const char *mode=fallback?"fallback":"netlink";
        CK(!chmod(seed_node,0644));unlink(seed_record);
        pid_t child=fork();CK(child>=0);
        if(!child) {
            umask(0077);
            if(fallback)deny_netlink();
            execl("/guest","/guest","/g","generic","quiet","--","/payload","payload",mode,seed.node,seed.record,NULL);
            _exit(127);
        }
        int status;
        CK(waitpid(child,&status,0)==child && WIFEXITED(status) && WEXITSTATUS(status)==0);
    }
    detach(&seed);
    puts("INPUT_HOTPLUG_INTEGRATION_PASS");reboot(RB_POWER_OFF);return 0;
}
