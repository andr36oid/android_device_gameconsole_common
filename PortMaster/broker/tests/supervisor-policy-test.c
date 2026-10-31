/* Exercise the production DRM/mixer reads and inherited mute filter. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdarg.h>
#define open policy_open
#define ioctl policy_ioctl
#define main guest_program_main
#include "../../subsystem/andr36oid-guest.c"
#undef main
#undef open
#undef ioctl
static int fault, master_busy;
int policy_open(const char *path,int flags,...) {
    (void)path;
    return syscall(SYS_openat,AT_FDCWD,"/dev/null",flags,0);
}
int policy_ioctl(int fd,unsigned long request,...) {
    (void)fd;
    if(request==DRM_IOCTL_SET_MASTER) {if(master_busy){errno=EBUSY;return -1;}return 0;}
    va_list ap;va_start(ap,request);void *arg=va_arg(ap,void *);va_end(ap);
    if(request==SNDRV_CTL_IOCTL_ELEM_INFO) {
        struct snd_ctl_elem_info *i=arg;
        if(!strcmp((char *)i->id.name,"Playback Path")) {
            i->type=SNDRV_CTL_ELEM_TYPE_ENUMERATED;i->count=1;
            i->value.enumerated.items=2;strcpy((char *)i->value.enumerated.name,"SPK_HP");
        } else if(!strcmp((char *)i->id.name,"Playback Hardware Master Switch")) {
            i->type=SNDRV_CTL_ELEM_TYPE_BOOLEAN;i->count=1;
        } else {i->type=SNDRV_CTL_ELEM_TYPE_INTEGER;i->count=2;i->value.integer.min=0;i->value.integer.max=255;}
        i->access=SNDRV_CTL_ELEM_ACCESS_READ;
        if(fault==5)i->count=129;
        return 0;
    }
    assert(request==SNDRV_CTL_IOCTL_ELEM_READ);
    struct snd_ctl_elem_value *v=arg;
    if(!strcmp((char *)v->id.name,"Playback Hardware Master Switch")) {
        if(fault==1){errno=EIO;return -1;}
        v->value.integer.value[0]=fault==2?-1:1;
    } else if(!strcmp((char *)v->id.name,"Playback Path"))v->value.enumerated.item[0]=fault==3?99:1;
    else {v->value.integer.value[0]=235;v->value.integer.value[1]=fault==4?256:234;}
    return 0;
}
static void filtered_checks(void) {
    const unsigned commands[]={0x13,0x14,0x15,0x17,0x18,0x19,0x1b,0x1c,0xd0,0xff};
    for(unsigned i=0;i<sizeof(commands)/sizeof(*commands);i++) {
        errno=0;assert(syscall(SYS_ioctl,-1,0xc0005500U|commands[i],0)==-1 && errno==EPERM);
        errno=0;assert(syscall(SYS_ioctl,-1,0x40045500U|commands[i],0)==-1 && errno==EPERM);
    }
    errno=0;assert(syscall(SYS_ioctl,-1,SNDRV_CTL_IOCTL_ELEM_READ,0)==-1 && errno==EBADF);
    errno=0;assert(syscall(SYS_ioctl,-1,SNDRV_PCM_IOCTL_PREPARE,0)==-1 && errno==EBADF);
    errno=0;assert(syscall(SYS_ioctl,-1,SNDRV_CTL_IOCTL_PCM_PREFER_SUBDEVICE,0)==-1 && errno==EBADF);
}
int main(int argc,char **argv) {
    if(argc==2){filtered_checks();return 0;}
    errno=0;assert(syscall(SYS_ioctl,-1,SNDRV_CTL_IOCTL_ELEM_WRITE,0)==-1 && errno==EBADF);
    assert(!check_drm());master_busy=1;assert(check_drm());
    int saved=dup(STDOUT_FILENO),sink=policy_open("/dev/null",O_WRONLY);assert(saved>=0 && sink>=0);
    assert(dup2(sink,STDOUT_FILENO)>=0);close(sink);
    assert(!capture_mixer());
    for(fault=1;fault<=5;fault++)assert(capture_mixer());
    fflush(stdout);assert(dup2(saved,STDOUT_FILENO)>=0);close(saved);
    pid_t child=fork();assert(child>=0);
    if(!child) {
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));hold_mixer_mute();filtered_checks();
        execl(argv[0],argv[0],"inherited",NULL);_exit(127);
    }
    int status;assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS: DRM ownership failure, scalar read/type/range failures, mute writes rejected before ioctl, PCM/query allowed, exec inherits mute policy");
}
