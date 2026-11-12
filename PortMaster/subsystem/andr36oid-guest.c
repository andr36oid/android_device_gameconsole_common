#define _GNU_SOURCE
#include <dirent.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/input.h>
#include <linux/netlink.h>
#include <drm/drm.h>
#include <drm/drm_mode.h>
#include <stdint.h>
#include <linux/capability.h>
#include <sound/asound.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
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
#include <sys/statvfs.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/sysmacros.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include "andr36oid-input.h"
#include "andr36oid-properties.h"
#include "andr36oid-stage.h"

#define PR_ANDR36OID_GUEST 0x41333601
static volatile sig_atomic_t stopping;
static void stop(int signo) { (void)signo; stopping = 1; }
static void fatal(const char *what) { perror(what); exit(125); }
static void guest_phase(const char *phase) {
    struct timespec t;
    if (!clock_gettime(CLOCK_BOOTTIME, &t))
        fprintf(stderr, "guest phase: %s boot_ms=%lld\n", phase,
                (long long)t.tv_sec*1000+t.tv_nsec/1000000);
}

static int check_drm(void) {
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    if(fd<0) {perror("DRM handoff");return 1;}
    int rc=ioctl(fd,DRM_IOCTL_SET_MASTER,0);
    if(rc)perror("DRM master still held");
    close(fd);
    return rc?1:0;
}
/* tinymix prints failed scalar reads as -1/On while returning success. Read
 * ALSA directly and validate every type, channel and value before admission. */
static int capture_mixer(void) {
    errno=0;
    static const struct { const char *name; snd_ctl_elem_type_t type; unsigned count; } controls[]={
        {"Playback Path",SNDRV_CTL_ELEM_TYPE_ENUMERATED,1},
        {"Master Playback Volume",SNDRV_CTL_ELEM_TYPE_INTEGER,2},
        {"Playback Volume",SNDRV_CTL_ELEM_TYPE_INTEGER,2},
        {"Playback Hardware Master Switch",SNDRV_CTL_ELEM_TYPE_BOOLEAN,1}
    };
    int fd=open("/dev/snd/controlC0",O_RDONLY|O_CLOEXEC),rc=1;
    if(fd<0) {perror("capture mixer");return 1;}
    for(unsigned n=0;n<sizeof(controls)/sizeof(*controls);n++) {
        struct snd_ctl_elem_info info={0};
        info.id.iface=SNDRV_CTL_ELEM_IFACE_MIXER;
        snprintf((char *)info.id.name,sizeof(info.id.name),"%s",controls[n].name);
        if(ioctl(fd,SNDRV_CTL_IOCTL_ELEM_INFO,&info) || info.type!=controls[n].type ||
           info.count!=controls[n].count || !(info.access&SNDRV_CTL_ELEM_ACCESS_READ))goto done;
        struct snd_ctl_elem_value value={.id=info.id};
        if(ioctl(fd,SNDRV_CTL_IOCTL_ELEM_READ,&value))goto done;
        if(info.type==SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
            unsigned item=value.value.enumerated.item[0];
            if(item>=info.value.enumerated.items)goto done;
            info.value.enumerated.item=item;
            if(ioctl(fd,SNDRV_CTL_IOCTL_ELEM_INFO,&info))goto done;
            const char *name=(const char *)info.value.enumerated.name;
            if(!memchr(name,0,sizeof(info.value.enumerated.name)) || !*name ||
               strspn(name,"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_")!=strlen(name))goto done;
            printf("%s\t%s\n",controls[n].name,name);
        } else {
            for(unsigned i=0;i<info.count;i++) {
                long v=value.value.integer.value[i];
                if(v<0 || (info.type==SNDRV_CTL_ELEM_TYPE_BOOLEAN?v>1:
                    (v<info.value.integer.min || v>info.value.integer.max)))goto done;
            }
            printf("%s\t",controls[n].name);
            for(unsigned i=0;i<info.count;i++)printf("%s%ld",i?" ":"",value.value.integer.value[i]);
            putchar('\n');
        }
    }
    rc=fflush(stdout)?1:0;
done:
    if(rc) {if(!errno)errno=EINVAL;perror("capture mixer scalar");}
    close(fd);return rc;
}
/* The kernel reports the debounced jack after DT polarity XOR hp_det_flip.
 * Validate ALSA directly: tinymix can report failed booleans as On/success. */
static int headphone_state(void) {
    int fd=open("/dev/snd/controlC0",O_RDONLY|O_CLOEXEC),rc=1;
    if(fd<0)return 1;
    struct snd_ctl_elem_info info={0};
    info.id.iface=SNDRV_CTL_ELEM_IFACE_CARD;
    snprintf((char *)info.id.name,sizeof(info.id.name),"Headphones Jack");
    if(ioctl(fd,SNDRV_CTL_IOCTL_ELEM_INFO,&info) ||
       info.type!=SNDRV_CTL_ELEM_TYPE_BOOLEAN || info.count!=1 ||
       !(info.access&SNDRV_CTL_ELEM_ACCESS_READ))goto done;
    struct snd_ctl_elem_value value={.id=info.id};
    if(ioctl(fd,SNDRV_CTL_IOCTL_ELEM_READ,&value) ||
       value.value.integer.value[0]<0 || value.value.integer.value[0]>1)goto done;
    printf("%ld\n",value.value.integer.value[0]);
    rc=fflush(stdout)?1:0;
done:
    close(fd);return rc;
}
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
    struct drm_mode_crtc_lut pending[64]={0};
    if(restore) {
        if(fread(header,sizeof(header),1,f)!=1 || header[0]!=0x41334731 || header[1]>64)goto done;
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
        if(restore) {
            pending[i]=lut;
            ok=fread(ramps,sizeof(*ramps),3*size,f)==3*size;
        }
        else ok=!ioctl(fd,DRM_IOCTL_MODE_GETGAMMA,&lut) && fwrite(ramps,sizeof(*ramps),3*size,f)==3*size;
        if(!restore)free(ramps);
        if(!ok)goto done;
    }
    if(restore) {
        // Validate the whole snapshot before acquiring master or changing a CRTC.
        if(fgetc(f)!=EOF || ferror(f))goto done;
        if(header[1] && ioctl(fd,DRM_IOCTL_SET_MASTER,0)) {perror("restore gamma master");goto done;}
        for(uint32_t i=0;i<header[1];i++)
            if(pending[i].gamma_size && ioctl(fd,DRM_IOCTL_MODE_SETGAMMA,&pending[i]))goto done;
    }
    else if(fflush(f) || fsync(fileno(f)) || rename(temporary,path))goto done;
    result=0;
done:
    for(unsigned i=0;i<64;i++)free((void *)(uintptr_t)pending[i].red);
    if(result) {if(!errno)errno=EINVAL;perror(restore?"restore gamma":"save gamma");}
    fclose(f);close(fd);if(!restore)unlink(temporary);
    return result;
}
static bool input_node_name(const char *name) {
    const char *num;
    if(!strncmp(name,"event",5))num=name+5;
    else if(!strncmp(name,"js",2))num=name+2;
    else return false;
    return *num && strlen(name)<=32 && strspn(num,"0123456789")==strlen(num);
}
static const char input_pad_alias[]="/dev/input/by-path/platform-odroidgo3-joypad-event-joystick";
// Publish processed input events after the private node/database are ready.
// SDL subscribes to libudev group2, not our kernel group1 wakeup socket. A
// faster scan alone cannot recover an input created after SDL's initial scan.
static int input_publish_fd=-1;
static bool input_publish_retry=true,input_publish_warned;
static bool input_record_read(const char *path,char *out,size_t size) {
    FILE *f=fopen(path,"re");if(!f)return false;
    size_t n=fread(out,1,size-1,f);bool ok=!ferror(f) && feof(f);fclose(f);
    out[n]=0;return ok;
}
static bool input_publish(const char *action,const char *record) {
    struct input_udev_header {
        char prefix[8];uint32_t magic,header_size,properties_off,properties_len;
        uint32_t subsystem_hash,devtype_hash,tag_bloom_hi,tag_bloom_lo;
    } header={.prefix="libudev",.magic=htonl(0xfeedcafe)};
    // libudev wire ABI: MurmurHash2("input",5,0), in network byte order.
    header.subsystem_hash=htonl(0xc1a28470);
    header.header_size=header.properties_off=sizeof(header);
    char props[8192];size_t length=0;static unsigned long long seq;
    int n=snprintf(props,sizeof(props),"ACTION=%s",action);
    if(n<0 || n>=(int)sizeof(props))return false;
    length=(size_t)n+1;
    n=snprintf(props+length,sizeof(props)-length,"SEQNUM=%llu",++seq);
    if(n<0 || (size_t)n>=sizeof(props)-length)return false;
    length+=(size_t)n+1;
    for(const char *line=record;*line;) {
        const char *end=strchr(line,'\n');if(!end)end=line+strlen(line);
        if(end-line>2 && line[0]=='E' && line[1]==':') {
            size_t bytes=(size_t)(end-line)-2;
            if(bytes+1>sizeof(props)-length)return false;
            memcpy(props+length,line+2,bytes);length+=bytes;props[length++]=0;
        }
        line=*end?end+1:end;
    }
    header.properties_len=(uint32_t)length;
    if(input_publish_fd<0) {
        input_publish_fd=socket(AF_NETLINK,SOCK_DGRAM|SOCK_NONBLOCK|SOCK_CLOEXEC,NETLINK_KOBJECT_UEVENT);
        struct sockaddr_nl local={.nl_family=AF_NETLINK};
        if(input_publish_fd>=0 && bind(input_publish_fd,(struct sockaddr *)&local,sizeof(local))) {
            close(input_publish_fd);input_publish_fd=-1;
        }
    }
    if(input_publish_fd>=0) {
        // Real device properties only, on the processed-udev group. Android's
        // uevent listener explicitly rejects all non-kernel sender port IDs.
        struct sockaddr_nl dest={.nl_family=AF_NETLINK,.nl_groups=2};
        struct iovec iov[]={{.iov_base=&header,.iov_len=sizeof(header)},{.iov_base=props,.iov_len=length}};
        struct msghdr msg={.msg_name=&dest,.msg_namelen=sizeof(dest),.msg_iov=iov,.msg_iovlen=2};
        if(sendmsg(input_publish_fd,&msg,MSG_DONTWAIT)>=0 || errno==ESRCH || errno==ECONNREFUSED)return true;
    }
    input_publish_retry=true;
    if(!input_publish_warned) {perror("guest processed input notification");input_publish_warned=true;}
    return false;
}
static void forget_input_node(const char *node, const struct stat *st) {
    if(unlink(node))return;
    // A device can vanish between the add scan and the removal sweep.
    if(access(input_pad_alias,F_OK)<0 && errno==ENOENT)unlink(input_pad_alias);
    if(!S_ISCHR(st->st_mode))return;
    char record[64];
    snprintf(record,sizeof(record),"/run/udev/data/c%u:%u",major(st->st_rdev),minor(st->st_rdev));
    char previous[8192];
    if(input_record_read(record,previous,sizeof(previous)))input_publish("remove",previous);
    unlink(record);
}
static void mirror_inputs(void) {
    DIR *d=opendir("/sys/class/input"); if(!d) return;
    mode_t previous_mask=umask(0022);
    mkdir("/dev/input",0755);mkdir("/dev/input/by-path",0755);
    mkdir("/run/udev",0755);mkdir("/run/udev/data",0755);
    // libudev disables processed-event subscriptions in a private tmpfs /dev
    // unless this marker exists. We supply that channel for input devices;
    // this is not a udevd control server (udevadm control remains unsupported).
    int marker=open("/run/udev/control",O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);
    if(marker>=0)close(marker);
    const char *alias=input_pad_alias;
    bool found_alias=false,republish=input_publish_retry;input_publish_retry=false;
    struct dirent *e; char path[256],node[256],buf[256],temporary[272];
    while((e=readdir(d))) {
        if(!input_node_name(e->d_name))continue;
        snprintf(path,sizeof(path),"/sys/class/input/%.32s/dev",e->d_name);
        FILE *f=fopen(path,"re"); if(!f) continue;
        unsigned major_num,minor_num; int n=fscanf(f,"%u:%u",&major_num,&minor_num);fclose(f);
        if(n!=2) continue;
        snprintf(node,sizeof(node),"/dev/input/%.32s",e->d_name);
        struct stat st; dev_t dev=makedev(major_num,minor_num);
        bool exists=lstat(node,&st)==0;
        if(exists && S_ISCHR(st.st_mode) && st.st_rdev==dev) {
            if((st.st_mode&07777)!=0666 && chmod(node,0666))continue;
        } else {
            if(exists)forget_input_node(node,&st);
            // The single-threaded supervisor owns creation; publish 0666 even
            // with an inherited 022/077 umask. Restore it before any fork.
            mode_t mask=umask(0);
            int rc=mknod(node,S_IFCHR|0666,dev);
            umask(mask);
            if(rc)continue;
        }
        bool js=e->d_name[0]=='j',pad=js,keyboard=false,mouse=false;
        int fd=js?-1:open(node,O_RDONLY|O_NONBLOCK|O_CLOEXEC);
        if(!js && fd<0)continue;
        unsigned long keys[(KEY_MAX+8*sizeof(long))/(8*sizeof(long))]={0};
        if(fd>=0 && ioctl(fd,EVIOCGBIT(EV_KEY,sizeof(keys)),keys)>=0) {
            for(unsigned key=0;key<BTN_MISC;key++)
                if(keys[key/(8*sizeof(long))]&(1UL<<(key%(8*sizeof(long))))) keyboard=true;
            for(unsigned key=BTN_JOYSTICK;key<=BTN_THUMBR;key++)
                if(keys[key/(8*sizeof(long))]&(1UL<<(key%(8*sizeof(long))))) pad=true;
        }
        unsigned long rel[(REL_MAX+8*sizeof(long))/(8*sizeof(long))]={0};
        if(fd>=0 && ioctl(fd,EVIOCGBIT(EV_REL,sizeof(rel)),rel)>=0)
            mouse=(rel[REL_X/(8*sizeof(long))]&(1UL<<(REL_X%(8*sizeof(long))))) &&
                  (rel[REL_Y/(8*sizeof(long))]&(1UL<<(REL_Y%(8*sizeof(long)))));
        // uinput mappers commonly combine keys and relative axes in one device.
        // libudev/SDL classifies each capability independently, not exclusively.
        // Retain the prior keyboard fallback for otherwise unclassified inputs.
        if(!pad && !mouse)keyboard=true;
        char sysnode[256],resolved[PATH_MAX],record[8192],previous[8192];
        snprintf(sysnode,sizeof(sysnode),"/sys/class/input/%.32s",e->d_name);
        if(!realpath(sysnode,resolved) || strncmp(resolved,"/sys/",5)) {if(fd>=0)close(fd);continue;}
        int length=snprintf(record,sizeof(record),
            "I:1\nE:ID_INPUT=1\n%s%s%sE:DEVNAME=%s\nE:DEVPATH=%s\nE:SUBSYSTEM=input\nE:MAJOR=%u\nE:MINOR=%u\nV:1\n",
            pad?"E:ID_INPUT_JOYSTICK=1\n":"",keyboard?"E:ID_INPUT_KEY=1\nE:ID_INPUT_KEYBOARD=1\n":"",
            mouse?"E:ID_INPUT_MOUSE=1\n":"",node,resolved+4,major_num,minor_num);
        if(length<0 || (size_t)length>=sizeof(record)) {if(fd>=0)close(fd);continue;}
        snprintf(path,sizeof(path),"/run/udev/data/c%u:%u",major_num,minor_num);
        bool existed=input_record_read(path,previous,sizeof(previous));
        bool same=existed && !strcmp(previous,record);
        bool published=same;
        if(!same) {
            snprintf(temporary,sizeof(temporary),"%s.tmp",path);
            f=fopen(temporary,"we");
            if(f) {
                bool written=fwrite(record,1,(size_t)length,f)==(size_t)length;
                int closed=fclose(f);
                if(written && !closed && !rename(temporary,path))published=true;
                else unlink(temporary);
            }
        }
        if(published && (!same || republish)) {
            // A remove/add can reuse the same event number before a scan. SDL
            // must retire its old FD before accepting the replacement path.
            if(existed && !same)input_publish("remove",previous);
            input_publish("add",record);
        }
        if(fd>=0 && pad && ioctl(fd,EVIOCGNAME(sizeof(buf)),buf)>=0 && !strcmp(buf,"GO-Super Gamepad")) {
            found_alias=true;
            snprintf(path,sizeof(path),"../%.32s",e->d_name);
            ssize_t len=readlink(alias,buf,sizeof(buf));
            if(len!=(ssize_t)strlen(path) || memcmp(buf,path,strlen(path))) {
                unlink(alias);
                if(symlink(path,alias))perror("pad alias");
            }
        }
        if(fd>=0)close(fd);
    }
    closedir(d);
    umask(previous_mask);
    if(!found_alias)unlink(alias);
    d=opendir("/dev/input");if(!d)return;
    while((e=readdir(d))) {
        if(!input_node_name(e->d_name))continue;
        snprintf(path,sizeof(path),"/sys/class/input/%.32s",e->d_name);
        if(access(path,F_OK)==0 || errno!=ENOENT)continue;
        snprintf(node,sizeof(node),"/dev/input/%.32s",e->d_name);
        struct stat st;
        if(!lstat(node,&st) && S_ISCHR(st.st_mode))forget_input_node(node,&st);
    }
    closedir(d);
}

// The netlink datagram is a NUL-separated header and environment. Use it only
// as a wakeup: sysfs is authoritative for device numbers and current presence.
static bool input_uevent(const char *buf, size_t len) {
    bool input=false,change=false;
    for(size_t i=0;i<len;) {
        const char *field=buf+i,*end=memchr(field,0,len-i);
        if(!end)return false;
        if(!strcmp(field,"SUBSYSTEM=input"))input=true;
        if(!strcmp(field,"ACTION=add") || !strcmp(field,"ACTION=remove"))change=true;
        i=(size_t)(end-buf)+1;
    }
    return input && change;
}
static int input_uevent_open(void) {
    int fd=socket(AF_NETLINK,SOCK_DGRAM|SOCK_NONBLOCK|SOCK_CLOEXEC,NETLINK_KOBJECT_UEVENT);
    if(fd<0)return -1;
    struct sockaddr_nl addr={.nl_family=AF_NETLINK,.nl_groups=1};
    if(bind(fd,(struct sockaddr *)&addr,sizeof(addr))) {close(fd);return -1;}
    return fd;
}
static bool input_uevents_pending(int fd) {
    bool changed=false;
    // Bound the drain so a burst of unrelated uevents cannot starve hotkeys.
    for(int n=0;n<64;n++) {
        char buf[4096];struct sockaddr_nl src={0};
        struct iovec iov={.iov_base=buf,.iov_len=sizeof(buf)};
        struct msghdr msg={.msg_name=&src,.msg_namelen=sizeof(src),.msg_iov=&iov,.msg_iovlen=1};
        ssize_t len=recvmsg(fd,&msg,MSG_DONTWAIT);
        if(len<=0)return changed || (len<0 && errno==ENOBUFS);
        if(msg.msg_namelen==sizeof(src) && src.nl_family==AF_NETLINK && !src.nl_pid &&
           ((msg.msg_flags&MSG_TRUNC) || input_uevent(buf,(size_t)len)))changed=true;
    }
    return changed;
}
static uint64_t input_signature(void) {
    DIR *d=opendir("/sys/class/input");if(!d)return 0;
    // A cheap directory probe also works when a socket binds but delivery is
    // filtered. Include inode numbers, not just count, for same-count swaps.
    uint64_t sig=14695981039346656037ULL;struct dirent *e;
    while((e=readdir(d))) {
        if(!input_node_name(e->d_name))continue;
        sig=(sig^(uint64_t)e->d_ino)*1099511628211ULL;
        for(const char *p=e->d_name;*p;p++)sig=(sig^(unsigned char)*p)*1099511628211ULL;
    }
    closedir(d);return sig;
}
struct input_watch {
    bool enabled,seen_uevent;
    int fd;
    uint64_t signature;
    long long next_probe,next_full;
};
static void input_watch_update(struct input_watch *w, long long now, bool changed) {
    if(!w->enabled)return;
    if(now>=w->next_probe) {
        uint64_t sig=input_signature();
        if(sig && sig!=w->signature)changed=true;
        w->signature=sig;
        w->next_probe=now+50;
    }
    if(changed || now>=w->next_full) {
        mirror_inputs();
        w->next_full=now+2000;
    }
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
static bool is_emulationstation(pid_t pid) {
    // comm truncates both emulationstation and emulationstation.sh identically.
    // The wrapper's executable is its shell; only target the actual frontend.
    char path[64],exe[4096];
    snprintf(path,sizeof(path),"/proc/%d/exe",pid);
    ssize_t n=readlink(path,exe,sizeof(exe)-1);
    if(n<0 || n>=(ssize_t)sizeof(exe)-1)return false;
    exe[n]=0;
    char *deleted=strstr(exe," (deleted)");if(deleted && !deleted[10])*deleted=0;
    char *name=strrchr(exe,'/');
    return name && !strcmp(name+1,"emulationstation");
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
        if(p>1 && is_emulationstation(p)) { es=p; break; }
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
#include "andr36oid-guest-teardown.h"
#include "andr36oid-guest-loops.h"
/* Mount propagation is private. Ordinary storage tools must not change a shared
 * filesystem's GLOBAL superblock flags or Android's global swap configuration.
 * Bind remounts change only this namespace's view and remain available. This is
 * deliberately not a syscall sandbox for hostile root code. */
static void storage_mount_policy(void) {
#if defined(__aarch64__)
    const unsigned native=AUDIT_ARCH_AARCH64,compat=AUDIT_ARCH_ARM;
#elif defined(__x86_64__)
    const unsigned native=AUDIT_ARCH_X86_64,compat=AUDIT_ARCH_I386;
#else
#error "Define storage guard ABI"
#endif
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,native,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,compat,6,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_swapon,12,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_swapoff,11,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mount,5,11),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,87,7,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,115,6,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,21,0,6),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[3])),
        BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,MS_REMOUNT,0,4),
        BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,MS_BIND,3,2),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
    };
    struct sock_fprog policy={.len=sizeof(code)/sizeof(code[0]),.filter=code};
    if(prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&policy))fatal("storage mount policy");
}
/* Keep reboot/module/raw-I/O/network/mknod capabilities denied; normal mount(8)
 * works in the private namespace. Host raw block nodes are never exposed. */
static void limit_guest(void) {
    struct __user_cap_header_struct header={.version=_LINUX_CAPABILITY_VERSION_3,.pid=0};
    struct __user_cap_data_struct caps[2];
    if(syscall(SYS_capget,&header,caps)) fatal("capget");
    /* File installation, guest services and real mount tools need these. */
    const uint64_t allow=(1ULL<<CAP_CHOWN)|(1ULL<<CAP_DAC_OVERRIDE)|
        (1ULL<<CAP_FOWNER)|(1ULL<<CAP_FSETID)|(1ULL<<CAP_KILL)|
        (1ULL<<CAP_SETGID)|(1ULL<<CAP_SETUID)|(1ULL<<CAP_SYS_ADMIN);
    for(int cap=0;cap<64;cap++) {
        if(allow&(1ULL<<cap))continue;
        errno=0;
        if(prctl(PR_CAPBSET_READ,cap,0,0,0)<0 && errno==EINVAL)break;
        if(prctl(PR_CAPBSET_DROP,cap,0,0,0)) fatal("capability bounding set");
        unsigned mask=~(1U<<(cap%32));
        caps[cap/32].effective&=mask;caps[cap/32].permitted&=mask;caps[cap/32].inheritable&=mask;
    }
    if(syscall(SYS_capset,&header,caps)) fatal("capset");
    if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0))fatal("no new privileges");
    storage_mount_policy();
}
/* Hold host-established OFF/zero controls for the entire muted guest lifetime.
 * Match the ioctl type/number independently of its structure size, so compat
 * ALSA ioctls cannot bypass the policy. PCM ioctls remain available. No filter
 * is installed for an audible session. Descendants cannot remove this filter.
 * Only queries, subscriptions and per-file PCM/MIDI device preferences are
 * admitted on ALSA control devices. */
static void hold_mixer_mute(void) {
#if defined(__aarch64__)
#define A36_ARCH AUDIT_ARCH_AARCH64
#define A36_COMPAT_ARCH AUDIT_ARCH_ARM
#define A36_COMPAT_IOCTL 54
#elif defined(__x86_64__)
#define A36_ARCH AUDIT_ARCH_X86_64
#define A36_COMPAT_ARCH AUDIT_ARCH_I386
#define A36_COMPAT_IOCTL 54
#else
#error "Define the native and compat ioctl ABI for the mute filter"
#endif
#define CTL_QUERY(n) BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(n),0,1), BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,A36_ARCH,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_STMT(BPF_ALU|BPF_AND|BPF_K,~0x40000000U), /* x32 ioctl too */
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_ioctl,7,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,A36_COMPAT_ARCH,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_STMT(BPF_ALU|BPF_AND|BPF_K,~0x40000000U),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,A36_COMPAT_IOCTL,2,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_STMT(BPF_ALU|BPF_AND|BPF_K,0xff00),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0x5500,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_STMT(BPF_ALU|BPF_AND|BPF_K,0xff),
        CTL_QUERY(0x00),CTL_QUERY(0x01),CTL_QUERY(0x10),CTL_QUERY(0x11),
        CTL_QUERY(0x12),CTL_QUERY(0x16),CTL_QUERY(0x1a),CTL_QUERY(0x20),
        CTL_QUERY(0x21),CTL_QUERY(0x30),CTL_QUERY(0x31),CTL_QUERY(0x32),
        CTL_QUERY(0x40),CTL_QUERY(0x41),CTL_QUERY(0x42),CTL_QUERY(0xd1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM)
    };
    struct sock_fprog program={.len=sizeof(filter)/sizeof(*filter),.filter=filter};
    if(prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program))fatal("hold codec mute");
#undef CTL_QUERY
}
static void close_inherited_fds(void) {
    DIR *d=opendir("/proc/self/fd");if(!d)fatal("inherited descriptors");
    struct dirent *e;
    while((e=readdir(d))) {
        int fd=atoi(e->d_name);
        if(fd>2 && fd!=dirfd(d))close(fd);
    }
    closedir(d);
}
static int supervise(char **argv,bool native,bool hotkeys) {
    const int exit_hold_ms=hotkey_exit_hold_ms(getenv("A36_EXIT_HOLD_MS"));
    if(native && prctl(PR_ANDR36OID_GUEST,1UL,0UL,0UL,0UL)) fatal("guest input ABI unavailable; update the kernel");
    struct sigaction sa={.sa_handler=stop}; sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM,&sa,NULL); sigaction(SIGINT,&sa,NULL); sigaction(SIGHUP,&sa,NULL);
    struct input_watch inputs={.enabled=access("/dev/input",F_OK)==0,.fd=-1};
    if(inputs.enabled) {
        // Subscribe before the initial scan so creation cannot fall in a gap.
        inputs.fd=input_uevent_open();
        fprintf(stderr,"guest input hotplug: %s50 ms sysfs probe\n",inputs.fd>=0?"kernel uevents + ":"");
        input_watch_update(&inputs,now_ms(),true);
    }
    bool muted=access("/run/.a36-muted",F_OK)==0;
    pid_t payload=fork(); if(payload<0) fatal("fork payload");
    if(!payload) {
        signal(SIGTERM,SIG_DFL); signal(SIGINT,SIG_DFL); signal(SIGHUP,SIG_DFL);
        if(inputs.fd>=0)close(inputs.fd);
        if(input_publish_fd>=0)close(input_publish_fd);
        limit_guest();
        if(muted)hold_mixer_mute();
        // Internal shared storage must also remain writable by Android media_rw.
        if(native) { if(setgid(1023))fatal("shared storage group");umask(0002); }
        guest_phase("payload-exec");
        execvp(argv[0],argv); fatal("exec guest");
    }
    int pad=hotkeys?pad_open():-1, status=0; long long last_retry=now_ms();
    struct hotkey_state keys={0};
    if(pad>=0)hotkey_sync(pad,native,&keys);
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
        input_watch_update(&inputs,now,false);
        if(hotkeys && pad<0 && now-last_retry>=2000) {
            pad=pad_open();last_retry=now;
            if(pad>=0)hotkey_sync(pad,native,&keys);
        }
        struct pollfd pollers[]={{.fd=pad,.events=POLLIN},{.fd=inputs.fd,.events=POLLIN}};
        int timeout=50;
        if(inputs.enabled) {
            long long until=inputs.next_probe-now_ms();
            if(until<50)timeout=until>0?(int)until:0;
        }
        int ready=poll(pollers,inputs.fd>=0?2:1,timeout);
        if(ready<0 && errno==EINTR)continue;
        if(ready>0 && inputs.fd>=0 && pollers[1].revents) {
            bool changed=input_uevents_pending(inputs.fd);
            if(changed && !inputs.seen_uevent) {
                inputs.seen_uevent=true;
                fprintf(stderr,"guest input hotplug: kernel input uevents received\n");
            }
            if(pollers[1].revents&(POLLHUP|POLLERR|POLLNVAL)) {
                close(inputs.fd);inputs.fd=-1;changed=true;
                fprintf(stderr,"guest input hotplug: uevent listener lost; using sysfs probe\n");
            }
            input_watch_update(&inputs,now_ms(),changed);
        }
        struct input_event ev;
        if(ready<0 || (pollers[0].revents&(POLLHUP|POLLERR|POLLNVAL))) {
            if(pad>=0)close(pad);
            pad=-1;hotkey_invalidate(&keys,false);last_retry=now_ms();
            // A failed poll on an absent pad also needs a delay: otherwise
            // persistent resource errors can spin the supervisor at full CPU.
            if(ready<0)usleep(50000);
        }
        if(pad>=0 && keys.sync_needed && !keys.dropped)hotkey_sync(pad,native,&keys);
        while(pad>=0) {
            ssize_t n=read(pad,&ev,sizeof(ev));
            if(n<0 && errno==EINTR)continue;
            if(n<0 && (errno==EAGAIN || errno==EWOULDBLOCK))break;
            if(n!=sizeof(ev)) {
                close(pad);pad=-1;hotkey_invalidate(&keys,false);last_retry=now_ms();
                break;
            }
            hotkey_event(pad,native,&keys,&ev);
        }
        int action=hotkey_tick_with_hold(&keys,now_ms(),exit_hold_ms);
        if(action&HOTKEY_EXIT_GAME)exit_game(payload);
        if(action&HOTKEY_RETURN_ANDROID)stopping=1;
    }
}
/* Clear noexec on one bind mount, keeping its other per-mount flags. The broker's
 * `mount -o remount,exec,bind` (toybox 0.8.3) fails with EINVAL on the 5.10 kernel,
 * which left /roms noexec: PortMaster and every port script got "Permission denied". */
static int remount_exec(const char *path) {
    struct statvfs v;
    if(statvfs(path,&v)) { perror("remount-exec: statvfs"); return 1; }
    unsigned long flags=MS_BIND|MS_REMOUNT;
    if(v.f_flag&ST_RDONLY)flags|=MS_RDONLY;
    if(v.f_flag&ST_NOSUID)flags|=MS_NOSUID;
    if(v.f_flag&ST_NODEV)flags|=MS_NODEV;
    if(v.f_flag&ST_NOATIME)flags|=MS_NOATIME;
    if(v.f_flag&ST_NODIRATIME)flags|=MS_NODIRATIME;
    if(v.f_flag&ST_RELATIME)flags|=MS_RELATIME;
    if(mount(NULL,path,NULL,flags,NULL)) { perror("remount-exec: mount"); return 1; }
    return 0;
}
/* Android init supplies this global control socket to the kept supplicant.
 * An autobound abstract client address leaves no socket file on /data. */
static int session_wifi_control(const char *operation) {
    const char *command;
    if (!strcmp(operation, "status")) command = "IFNAME=wlan0 STATUS";
    else if (!strcmp(operation, "reassociate")) command = "IFNAME=wlan0 REASSOCIATE";
    else return 2;

    struct sockaddr_un local = { .sun_family = AF_UNIX };
    struct sockaddr_un server = { .sun_family = AF_UNIX, .sun_path = "/dev/socket/wpa_wlan0" };
    int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) { perror("session Wi-Fi socket"); return 1; }
    int result = 1;
    if (bind(fd, (struct sockaddr *)&local, sizeof(local.sun_family)) ||
        connect(fd, (struct sockaddr *)&server, sizeof(server)) ||
        send(fd, command, strlen(command), 0) != (ssize_t)strlen(command)) {
        perror("session Wi-Fi supplicant control");
        goto done;
    }
    struct pollfd reply = { .fd = fd, .events = POLLIN };
    if (poll(&reply, 1, 750) <= 0) {
        fputs("session Wi-Fi supplicant reply timed out\n", stderr);
        goto done;
    }
    char data[4096];
    ssize_t length = recv(fd, data, sizeof(data) - 1, 0);
    if (length <= 0) goto done;
    data[length] = 0;
    if (!strcmp(operation, "status")) {
        /* Keep SSIDs, MACs other than the BSSID, and all other status fields
         * out of the broker's recovery logs and input parsing. */
        char *next;
        for (char *line = strtok_r(data, "\n", &next); line; line = strtok_r(NULL, "\n", &next)) {
            if (!strncmp(line, "wpa_state=", 10) || !strncmp(line, "bssid=", 6) ||
                !strncmp(line, "id=", 3)) puts(line);
        }
        result = 0;
    } else if (!strcmp(data, "OK\n")) {
        result = 0;
    } else {
        fputs("session Wi-Fi supplicant rejected reassociation\n", stderr);
    }
done:
    close(fd);
    return result;
}
#include "andr36oid-splash.h"
#include "andr36oid-splash-scanout.h"
int main(int argc,char **argv) {
    if(argc==4&&!strcmp(argv[1],"--populate-dev"))return stage_dev(argv[2],!strcmp(argv[3],"1"));
    if(argc==3&&!strcmp(argv[1],"--storage-groups"))return stage_groups(argv[2]);
    if(argc==3&&!strcmp(argv[1],"--network-masks"))return stage_network_masks(argv[2]);
    if(argc==3&&!strcmp(argv[1],"--properties"))return a36_properties(argv[2]);
    if(argc==8 && !strcmp(argv[1],"--splash-scanout")) {
        char *end;errno=0;unsigned long wait_ms=strtoul(argv[6],&end,10);
        if(errno||!*argv[6]||*end||wait_ms>10000)return 2;
        return splash_scanout(argv[2],argv[3],argv[4],argv[5],(unsigned)wait_ms,argv[7]);
    }
    if(argc==5 && !strcmp(argv[1],"--splash"))return splash_paint(argv[2],argv[3],argv[4]);
    if (argc == 3 && !strcmp(argv[1], "--session-wifi")) return session_wifi_control(argv[2]);
    if (argc == 5 && !strcmp(argv[1], "--stop-worker-tree"))
        return stop_worker_tree(argv[2], argv[3], argv[4]);
    if (argc == 3 && !strcmp(argv[1], "--kill-guest-root")) return stop_guest_root(argv[2], false);
    if (argc == 3 && !strcmp(argv[1], "--stop-guest-root")) return stop_guest_root(argv[2], true);
    if(argc==3&&!strcmp(argv[1],"--clean-port-mounts"))return gl_clean_ports(argv[2]);
    if(argc==4&&!strcmp(argv[1],"--cleanup-guest-loops"))return gl_recover(argv[2],argv[3]);
    if(argc==2 && !strcmp(argv[1],"--capture-mixer"))return capture_mixer();
    if(argc==2 && !strcmp(argv[1],"--headphone-state"))return headphone_state();
    if(argc==2 && !strcmp(argv[1],"--check-drm"))return check_drm();
    if(argc==3 && !strcmp(argv[1],"--save-gamma"))return gamma_state(argv[2],false);
    if(argc==3 && !strcmp(argv[1],"--restore-gamma"))return gamma_state(argv[2],true);
    if(argc==2 && !strcmp(argv[1],"--probe")) {
        if(unshare(CLONE_NEWPID|CLONE_NEWIPC)) return 125;
        pid_t p=fork(); if(p<0) return 125;
        if(!p) _exit(prctl(PR_ANDR36OID_GUEST,1UL,0UL,0UL,0UL)?125:0);
        int status; if(waitpid(p,&status,0)!=p)return 125;
        return WIFEXITED(status)?WEXITSTATUS(status):125;
    }
    if(argc==3 && !strcmp(argv[1],"--remount-exec"))return remount_exec(argv[2]);
    if(argc==4 && !strcmp(argv[1],"--bind")) {
        if(mount(argv[2],argv[3],NULL,MS_BIND,NULL)) fatal("bind guest hardware control");
        return 0;
    }
    if(argc<6 || strcmp(argv[4],"--")) { fprintf(stderr,"usage: andr36oid-guest ROOT native|generic hotkeys|quiet -- command...\n"); return 2; }
    guest_phase("supervisor-enter");
    close_inherited_fds();
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
        struct gl_state loops;
        if(gl_start(&loops,argv[1],getenv("A36_LOOP_JOURNAL"))){perror("guest loop baseline");kill(init,SIGKILL);return 125;}
        guest_phase("loop-baseline-ready");
        if(read(gate[0],&byte,1)!=1 || write(gate[0],"1",1)!=1) {kill(init,SIGKILL);return 125;}
        close(gate[0]);
        int status; for(;;) {
            if(stopping) kill(init,SIGTERM);
            pid_t done=waitpid(init,&status,WNOHANG);
            if(done==init){int rc=WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status);if(gl_finish(&loops,true))return 125;return rc;}
            if(done<0&&errno!=EINTR)fatal("wait guest");
            if(gl_tick(&loops,init))kill(init,SIGKILL);
            usleep(100000);
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
        if(access("/run/.a36-muted",F_OK)==0)hold_mixer_mute();
        execvp(argv[5],argv+5); fatal("exec init");
    }
    return supervise(argv+5,native,hotkeys);
}
