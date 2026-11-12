#ifndef ANDR36OID_STORAGE_H
#define ANDR36OID_STORAGE_H
/* Native dArkOSen menu transport only. Android/vold owns physical filesystems.
 * The existing outer supervisor imports directory FDs from its original mount
 * namespace. Detached mount FDs are imported only on a tool request. Older
 * kernels use the entry snapshot. Recursive PRIVATE entry never changes. */
#define A36_STORAGE_MAGIC 0x41335331u
#define A36_STORAGE_MAX 64
struct a36_volume { unsigned long id; unsigned maj,min,block_maj,block_min; bool usb; char path[PATH_MAX],fs[32]; };
struct a36_storage_reply { int error; char fs[32]; };
struct a36_storage {
    int host_ns,guest_ns,socket;
    bool mount_api;
    struct a36_volume *entry; int entry_count;
    char root[PATH_MAX],endpoint[108];
    struct a36_volume owned[2];
    bool bound[2]; long long next;
};
static int a36_storage_path(char *out,size_t size,const char *root,const char *tail) {
    if(snprintf(out,size,"%s%s",root,tail)>=(int)size){errno=ENAMETOOLONG;return -1;}return 0;
}
static bool a36_storage_usb(unsigned maj,unsigned min) {
    char path[PATH_MAX],resolved[PATH_MAX];const char *base="/sys/dev/block";
#ifdef A36_STORAGE_FIXTURE
    if(getenv("A36_STORAGE_SYS"))base=getenv("A36_STORAGE_SYS");
#endif
    if(snprintf(path,sizeof(path),"%s/%u:%u",base,maj,min)>=(int)sizeof(path)||!realpath(path,resolved))return false;
    return strstr(resolved,"/usb")!=NULL;
}
static bool a36_storage_public(const char *path) {
    const char *base="/mnt/media_rw/";size_t n=strlen(base);
    return !strncmp(path,base,n)&&path[n]&&strspn(path+n,"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-:,")==strlen(path+n);
}
static unsigned long a36_storage_fd_mount(int fd) {
    char path[64],line[256];unsigned long id=0;
    snprintf(path,sizeof(path),"/proc/self/fdinfo/%d",fd);
    FILE *f=fopen(path,"re");if(!f)return 0;
    while(fgets(line,sizeof(line),f))if(sscanf(line,"mnt_id: %lu",&id)==1)break;
    fclose(f);return id;
}
static int a36_storage_inventory(struct a36_volume out[A36_STORAGE_MAX]) {
    FILE *f=fopen("/proc/self/mountinfo","re");if(!f)return -1;
    char *line=NULL;size_t cap=0;int count=0;
    while(getline(&line,&cap,f)>0) {
        struct a36_volume v={0};char root[PATH_MAX],path[PATH_MAX],*end=strstr(line," - ");
        if(!end||sscanf(line,"%lu %*u %u:%u %4095s %4095s",&v.id,&v.maj,&v.min,root,path)!=5||strcmp(root,"/")||!a36_storage_public(path)||sscanf(end+3,"%31s",v.fs)!=1)continue;
        if(count==A36_STORAGE_MAX){free(line);fclose(f);errno=EOVERFLOW;return -1;}
        int dirfd=open(path,O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        if(dirfd<0)continue;
        bool visible=a36_storage_fd_mount(dirfd)==v.id;close(dirfd);if(!visible)continue;
        strcpy(v.path,path);v.block_maj=v.maj;v.block_min=v.min;
        char source[PATH_MAX];struct stat node;
        if(sscanf(end+3,"%*s %4095s",source)==1&&source[0]=='/'&&!stat(source,&node)&&S_ISBLK(node.st_mode)) {
            v.block_maj=major(node.st_rdev);v.block_min=minor(node.st_rdev);
        }
        v.usb=a36_storage_usb(v.block_maj,v.block_min);out[count++]=v;
    }
    bool bad=ferror(f);free(line);fclose(f);return bad?-1:count;
}
static bool a36_storage_same(const struct a36_volume *a,const struct a36_volume *b) {
    return a->id==b->id&&a->maj==b->maj&&a->min==b->min&&!strcmp(a->path,b->path);
}
static bool a36_storage_device_present(const struct a36_volume *v) {
    char p[PATH_MAX];const char *base="/sys/dev/block";
#ifdef A36_STORAGE_FIXTURE
    if(getenv("A36_STORAGE_SYS"))base=getenv("A36_STORAGE_SYS");
#endif
    return snprintf(p,sizeof(p),"%s/%u:%u",base,v->block_maj,v->block_min)<(int)sizeof(p)&&access(p,F_OK)==0;
}
static int a36_storage_enter(struct a36_storage *s) {return setns(s->host_ns,CLONE_NEWNS);}
static int a36_storage_leave(struct a36_storage *s) {
    /* Continuing in Android's namespace would be unsafe. Terminate instead. */
    if(setns(s->guest_ns,CLONE_NEWNS))fatal("restore private storage namespace");
    return 0;
}
static int a36_storage_directory(struct a36_storage *s,const char *tail) {
    char path[PATH_MAX];if(a36_storage_path(path,sizeof(path),s->root,tail))return -1;
    size_t start=strlen(s->root)+1;
    for(char *p=path+start;;p++)if(*p=='/'||!*p) {
        char saved=*p;*p=0;struct stat st;
        if(lstat(path,&st)) {if(errno!=ENOENT||mkdir(path,0755))return -1;}
        else if(!S_ISDIR(st.st_mode)){errno=ENOTDIR;return -1;}
        *p=saved;if(!saved)break;
    }
    return 0;
}
static int a36_storage_mount_id(const char *path,unsigned long *id) {
    FILE *f=fopen("/proc/self/mountinfo","re");if(!f)return -1;
    char *line=NULL;size_t cap=0;int found=0;
    while(getline(&line,&cap,f)>0){char dst[PATH_MAX];unsigned long n;
        if(sscanf(line,"%lu %*u %*u:%*u %*s %4095s",&n,dst)==2){gl_unescape(dst);if(!strcmp(dst,path)){*id=n;found=1;}}}
    free(line);fclose(f);return found;
}
static int a36_storage_detach_device(struct a36_storage *s,const struct a36_volume *v) {
    /* Include guest-created derived Tools/port binds. Deepest-first, no Android
     * mount or block ioctl. MNT_DETACH stays bounded even with open game files. */
    for(unsigned pass=0;pass<256;pass++) {
        FILE *f=fopen("/proc/self/mountinfo","re");if(!f)return -1;
        char *line=NULL;size_t cap=0,best_size=0;char best[PATH_MAX]={0};
        while(getline(&line,&cap,f)>0){unsigned maj,min;char dst[PATH_MAX];
            if(sscanf(line,"%*u %*u %u:%u %*s %4095s",&maj,&min,dst)!=3)continue;
            gl_unescape(dst);
            if(maj==v->maj&&min==v->min&&
               !strncmp(dst,s->root,strlen(s->root))&&dst[strlen(s->root)]=='/'&&strlen(dst)>best_size){strcpy(best,dst);best_size=strlen(dst);}}
        free(line);fclose(f);if(!best_size)return 0;
        if(umount2(best,MNT_DETACH)&&errno!=EINVAL&&errno!=ENOENT)return -1;
    }
    errno=ELOOP;return -1;
}
static void a36_storage_tick(struct a36_storage *s,long long now) {
    if(s->socket<0||(!(s->bound[0]&&s->owned[0].id)&&!(s->bound[1]&&s->owned[1].id))||now<s->next)return;
    s->next=now+250;struct a36_volume volumes[A36_STORAGE_MAX];bool live[2]={true,true};
    if(a36_storage_enter(s))return;
    int count=a36_storage_inventory(volumes);
    if(count>=0)for(unsigned j=0;j<2;j++)if(s->bound[j]&&s->owned[j].id) {
        live[j]=false;
        for(int i=0;i<count;i++)if(a36_storage_same(&s->owned[j],&volumes[i])&&a36_storage_device_present(&volumes[i]))live[j]=true;
    }
    a36_storage_leave(s);
    for(unsigned j=0;j<2;j++)if(s->bound[j]&&!live[j]) {
        if(!a36_storage_detach_device(s,&s->owned[j])) {
            fprintf(stderr,"guest storage: removed %s; guest views detached\n",s->owned[j].path);s->bound[j]=false;
        }
    }
}
#ifndef __NR_open_tree
#if defined(__aarch64__) || defined(__x86_64__)
#define __NR_open_tree 428
#define __NR_move_mount 429
#else
#error "Define native detached-mount syscall numbers"
#endif
#endif
#define A36_OPEN_TREE_CLONE 1u
#define A36_AT_EMPTY_PATH 0x1000u
#define A36_MOVE_MOUNT_F_EMPTY_PATH 4u
static bool a36_storage_api(void) {
    /* Invalid FDs cannot create or attach a mount. Probe functionality, never
     * uname or a property. EPERM is unavailable, not permission to bypass it. */
    errno=0;long n=syscall(__NR_open_tree,-1,"",A36_OPEN_TREE_CLONE|O_CLOEXEC|A36_AT_EMPTY_PATH);
    if(n>=0){close((int)n);return false;}
    if(errno!=EBADF)return false;
    errno=0;n=syscall(__NR_move_mount,-1,"",-1,"",A36_MOVE_MOUNT_F_EMPTY_PATH|0x40u);
    return n<0&&errno==EBADF;
}
static bool a36_storage_supported(const struct a36_volume *v) {
    return !strcmp(v->fs,"vfat")||!strcmp(v->fs,"exfat")||!strcmp(v->fs,"ntfs")||!strcmp(v->fs,"ntfs3")||!strcmp(v->fs,"fuseblk");
}
static int a36_storage_capture(struct a36_storage *s) {
    struct a36_volume local[A36_STORAGE_MAX];int count=a36_storage_inventory(local);
    if(count<0)return -1;
    if(count){s->entry=calloc((size_t)count,sizeof(*s->entry));if(!s->entry)return -1;}
    if(a36_storage_enter(s))return -1;
    struct a36_volume host[A36_STORAGE_MAX];int hosts=a36_storage_inventory(host),error=hosts<0?errno:0;
    for(int i=0;i<count;i++)for(int j=0;j<hosts;j++)if(local[i].maj==host[j].maj&&local[i].min==host[j].min&&!strcmp(local[i].path,host[j].path)) {
        s->entry[s->entry_count++]=host[j];break;
    }
    a36_storage_leave(s);
    if(error){errno=error;return -1;}return 0;
}
static int a36_storage_mount(struct a36_storage *s,unsigned which,char fs[32]) {
    const char *tail=which?"/roms2":"/mnt/usbdrive";char dst[PATH_MAX];struct a36_volume selected={0};int fd=-1,error=0;
    if(a36_storage_path(dst,sizeof(dst),s->root,tail))return errno;
    if(which) {
        char src[PATH_MAX];struct stat st;
        if(a36_storage_path(src,sizeof(src),s->root,"/roms")||(fd=open(src,O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW))<0)return errno;
        if(fstat(fd,&st)){error=errno;close(fd);return error;}
        selected.maj=major(st.st_dev);selected.min=minor(st.st_dev);strcpy(selected.fs,"auto");
    }
    if(a36_storage_enter(s)){error=errno;if(fd>=0)close(fd);return error;}
    struct a36_volume volumes[A36_STORAGE_MAX];int count=a36_storage_inventory(volumes);
    if(count<0)error=errno;
    else if(which) {
        for(int i=0;i<count;i++)if(selected.maj==volumes[i].maj&&selected.min==volumes[i].min){selected=volumes[i];break;}
    } else {
        int best=-1;
        for(int i=0;i<count;i++)if(volumes[i].usb&&a36_storage_device_present(&volumes[i])&&a36_storage_supported(&volumes[i])) {
            bool eligible=s->mount_api;
            if(!eligible)for(int j=0;j<s->entry_count;j++)if(a36_storage_same(&volumes[i],&s->entry[j]))eligible=true;
            if(eligible&&(best<0||volumes[i].block_maj<volumes[best].block_maj||(volumes[i].block_maj==volumes[best].block_maj&&volumes[i].block_min<volumes[best].block_min)))best=i;
        }
        if(best<0)error=s->mount_api?ENODEV:EOPNOTSUPP;
        else selected=volumes[best];
    }
    if(!which&&!error&&s->mount_api) {
        /* The temporary namespace belongs only to this supervisor thread.
         * Make its copy private BEFORE cloning, so the detached mount can
         * never expose a shared Android peer, even for one syscall window. */
        if(unshare(CLONE_NEWNS)||mount(NULL,"/",NULL,MS_REC|MS_PRIVATE,NULL))error=errno;
        else {
            int source=open(selected.path,O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);struct stat st;
            if(source<0)error=errno;
            else {
                if(fstat(source,&st)||major(st.st_dev)!=selected.maj||minor(st.st_dev)!=selected.min)error=ESTALE;
                else {fd=(int)syscall(__NR_open_tree,source,"",A36_OPEN_TREE_CLONE|O_CLOEXEC|A36_AT_EMPTY_PATH);if(fd<0)error=errno;}
                close(source);
            }
        }
    }
    a36_storage_leave(s);
    if(!which&&!error&&!s->mount_api) {
        fd=open(selected.path,O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);struct stat st;
        if(fd<0)error=errno;
        else if(fstat(fd,&st)||major(st.st_dev)!=selected.maj||minor(st.st_dev)!=selected.min)error=ESTALE;
    }
    if(error){if(fd>=0)close(fd);return error;}
    unsigned long id=0;int mounted=a36_storage_mount_id(dst,&id);
    if(mounted<0){error=errno;close(fd);return error;}
    if(mounted) {
        struct stat st;
        if(!which){close(fd);return EBUSY;}
        if(!stat(dst,&st)&&major(st.st_dev)==selected.maj&&minor(st.st_dev)==selected.min) {
            s->bound[which]=true;s->owned[which]=selected;strcpy(fs,selected.fs);close(fd);return 0;
        }
        /* The baseline may put another entry-snapshot volume in /roms2.
         * Only the explicit SD2 action replaces that private guest view. */
        if(umount2(dst,0)){error=errno;close(fd);return error;}
    }
    if(a36_storage_directory(s,tail)){error=errno;close(fd);return error;}
    if(!which&&s->mount_api) {
        if(syscall(__NR_move_mount,fd,"",AT_FDCWD,dst,A36_MOVE_MOUNT_F_EMPTY_PATH))error=errno;
    } else {
        char source[64];snprintf(source,sizeof(source),"/proc/self/fd/%d",fd);
        if(mount(source,dst,NULL,MS_BIND,NULL))error=errno;
    }
    if(!error&&remount_exec(dst)){error=errno?errno:EIO;int ignored=umount2(dst,MNT_DETACH);(void)ignored;}
    close(fd);if(error)return error;
    s->bound[which]=true;s->owned[which]=selected;strcpy(fs,selected.fs);
    if(!strcmp(fs,"ntfs")||!strcmp(fs,"ntfs3")||!strcmp(fs,"fuseblk"))strcpy(fs,"ntfs-3g");
    return 0;
}
static int a36_storage_main(struct a36_storage *s,char fs[32]) {
    char source[PATH_MAX];struct stat st;
    if(a36_storage_path(source,sizeof(source),s->root,"/roms")||stat(source,&st))return errno;
    if(a36_storage_enter(s))return errno;
    struct a36_volume volumes[A36_STORAGE_MAX];int count=a36_storage_inventory(volumes),error=count<0?errno:0;
    strcpy(fs,"auto");
    for(int i=0;i<count;i++)if(volumes[i].maj==major(st.st_dev)&&volumes[i].min==minor(st.st_dev)){strcpy(fs,volumes[i].fs);break;}
    a36_storage_leave(s);return error;
}
static int a36_storage_unmount(struct a36_storage *s) {
    char dst[PATH_MAX];if(a36_storage_path(dst,sizeof(dst),s->root,"/mnt/usbdrive"))return errno;
    unsigned long id=0;int mounted=a36_storage_mount_id(dst,&id);
    if(mounted<0)return errno;
    if(!mounted)return EINVAL;
    if(umount2(dst,0))return errno;
    // Keep watching any guest-created derivative bind until source removal.
    return 0;
}
static int a36_storage_config_file(const char *path,bool sd2,bool atomic) {
    FILE *f=fopen(path,"re");if(!f)return errno==ENOENT?0:errno;
    struct stat st;if(fstat(fileno(f),&st)||!S_ISREG(st.st_mode)||st.st_size<0||st.st_size>4194304){fclose(f);return EINVAL;}
    size_t size=(size_t)st.st_size;char *data=malloc(size+1);if(!data){fclose(f);return ENOMEM;}
    if(fread(data,1,size,f)!=size){free(data);fclose(f);return EIO;}fclose(f);data[size]=0;
    if(memchr(data,0,size)){free(data);return EINVAL;}
    const char *from=sd2?"<path>/roms/":"<path>/roms2/",*to=sd2?"<path>/roms2/":"<path>/roms/";
    char temp[PATH_MAX];if(snprintf(temp,sizeof(temp),"%s.a36-storage.tmp",path)>=(int)sizeof(temp)){free(data);return ENAMETOOLONG;}
    int fd=open(atomic?temp:path,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,st.st_mode&0777);
    if(fd<0){int error=errno;free(data);return error;}
    f=fdopen(fd,"w");if(!f){int error=errno;close(fd);free(data);return error;}
    /* Native sed substitutes once on each matching line, not every occurrence. */
    for(char *line=data;*line;){char *end=strchr(line,'\n');size_t n=end?(size_t)(end-line)+1:strlen(line);char *match=strstr(line,from);
        if(match&&match<line+n){fwrite(line,1,(size_t)(match-line),f);fputs(to,f);fwrite(match+strlen(from),1,n-(size_t)(match-line)-strlen(from),f);}
        else fwrite(line,1,n,f);
        line+=n;
    }
    int error=0;if(ferror(f)||fchown(fd,st.st_uid,st.st_gid)||fchmod(fd,st.st_mode&07777))error=errno?errno:EIO;
    if(fclose(f)&&!error)error=errno;
    free(data);
    if(atomic&&!error&&rename(temp,path))error=errno;
    if(atomic&&error){int ignored=unlink(temp);(void)ignored;}
    return error;
}
static int a36_storage_read(const char *path,char **data,size_t *size) {
    int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0)return errno;
    struct stat st;if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_size<0||st.st_size>4194368){close(fd);return EINVAL;}
    *size=(size_t)st.st_size;*data=malloc(*size+1);if(!*data){close(fd);return ENOMEM;}
    size_t at=0;while(at<*size){ssize_t n=read(fd,*data+at,*size-at);if(n<0&&errno==EINTR)continue;if(n<=0){free(*data);close(fd);return EIO;}at+=(size_t)n;}
    close(fd);(*data)[*size]=0;if(memchr(*data,0,*size)){free(*data);return EINVAL;}return 0;
}
static int a36_storage_write(const char *path,const char *data,size_t size) {
    int fd=open(path,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,0644);if(fd<0)return errno;
    size_t at=0;int error=0;while(at<size){ssize_t n=write(fd,data+at,size-at);if(n<0&&errno==EINTR)continue;if(n<=0){error=errno?errno:EIO;break;}at+=(size_t)n;}
    if(!error&&fsync(fd))error=errno;
    if(close(fd)&&!error)error=errno;
    return error;
}
static int a36_storage_sync_parent(const char *path) {
    char parent[PATH_MAX];if(strlen(path)>=sizeof(parent))return ENAMETOOLONG;strcpy(parent,path);
    char *slash=strrchr(parent,'/');if(!slash)return EINVAL;*slash=0;
    int fd=open(parent,O_DIRECTORY|O_RDONLY|O_CLOEXEC);if(fd<0)return errno;
    int error=fsync(fd)?errno:0;close(fd);return error;
}
static int a36_storage_port_view(const char *data,size_t size,char **out,size_t *length) {
    FILE *f=open_memstream(out,length);if(!f)return errno;
    const char *old="<command>sudo perfmax %GOVERNOR% %ROM%; nice -n -19 %ROM%; sudo perfnorm</command>";
    const char *replacement="<command>/run/andr36oid-port-launch %ROM% %GOVERNOR%</command>";bool ports=false;
    for(size_t at=0;at<size;){const char *line=data+at,*end=memchr(line,'\n',size-at);size_t n=end?(size_t)(end-line)+1:size-at;
        const char *system=strstr(line,"<system>");if(system&&system<line+n)ports=false;
        const char *name=strstr(line,"<name>ports</name>");if(name&&name<line+n)ports=true;
        const char *match=ports?strstr(line,old):NULL;
        if(match&&match+strlen(old)<=line+n){fwrite(line,1,(size_t)(match-line),f);fputs(replacement,f);fwrite(match+strlen(old),1,n-(size_t)(match-line)-strlen(old),f);}
        else fwrite(line,1,n,f);
        at+=n;
    }
    bool bad=ferror(f);if(fclose(f))bad=true;return bad?EIO:0;
}
static int a36_storage_recover_config(struct a36_storage *s) {
    char cfg[PATH_MAX],alias[PATH_MAX],original[PATH_MAX],generated[PATH_MAX],pending[PATH_MAX];
    if(a36_storage_path(cfg,sizeof(cfg),s->root,"/etc/emulationstation/es_systems.cfg")||
       a36_storage_path(alias,sizeof(alias),s->root,"/etc/emulationstation/.andr36oid-es-base")||
       a36_storage_path(original,sizeof(original),s->root,"/run/andr36oid-es-systems.original")||
       a36_storage_path(generated,sizeof(generated),s->root,"/run/andr36oid-es-systems.cfg")||
       a36_storage_path(pending,sizeof(pending),s->root,"/etc/emulationstation/.andr36oid-es-change"))return errno;
    char *record=NULL;size_t size;int error=a36_storage_read(pending,&record,&size);if(error==ENOENT)return 0;if(error)return error;
    if(size<9||(memcmp(record,"A36ES1 0\n",9)&&memcmp(record,"A36ES1 1\n",9))){free(record);return EINVAL;}
    struct stat a,b;bool wrapped=!stat(cfg,&a)&&!stat(generated,&b)&&a.st_dev==b.st_dev&&a.st_ino==b.st_ino;
    if(wrapped&&(lstat(alias,&a)||!S_ISREG(a.st_mode))){free(record);return ESTALE;}
    const char *base=wrapped?alias:cfg;
    /* Write through the saved original inode: never rename a dentry that is
     * still a broker mountpoint. The durable complete record is replayable. */
    error=a36_storage_write(base,record+9,size-9);
    if(!error&&wrapped)error=a36_storage_write(original,record+9,size-9);
    if(!error&&wrapped){char *view=NULL;size_t length=0;error=a36_storage_port_view(record+9,size-9,&view,&length);if(!error)error=a36_storage_write(generated,view,length);free(view);}
    if(!error) {
        char choice[PATH_MAX];
        if(a36_storage_path(choice,sizeof(choice),s->root,"/etc/emulationstation/.andr36oid-storage-choice"))error=errno;
        else {const char *value=record[7]=='1'?"sd2\n":"main\n";error=a36_storage_write(choice,value,strlen(value));if(!error)error=a36_storage_sync_parent(choice);}
    }
    free(record);
    if(!error&&unlink(pending))error=errno;
    if(!error)error=a36_storage_sync_parent(pending);
    return error;
}
static int a36_storage_config(struct a36_storage *s,bool sd2) {
    int error=a36_storage_recover_config(s);if(error)return error;
    char cfg[PATH_MAX],alias[PATH_MAX],generated[PATH_MAX],temp[PATH_MAX],pending[PATH_MAX];struct stat a,b;
    if(a36_storage_path(cfg,sizeof(cfg),s->root,"/etc/emulationstation/es_systems.cfg")||
       a36_storage_path(alias,sizeof(alias),s->root,"/etc/emulationstation/.andr36oid-es-base")||
       a36_storage_path(generated,sizeof(generated),s->root,"/run/andr36oid-es-systems.cfg")||
       a36_storage_path(temp,sizeof(temp),s->root,"/etc/emulationstation/.andr36oid-es-change.tmp")||
       a36_storage_path(pending,sizeof(pending),s->root,"/etc/emulationstation/.andr36oid-es-change"))return errno;
    bool wrapped=!stat(cfg,&a)&&!stat(generated,&b)&&a.st_dev==b.st_dev&&a.st_ino==b.st_ino;
    char *data=NULL;size_t size;error=a36_storage_read(wrapped?alias:cfg,&data,&size);if(error)return error;
    error=a36_storage_write(temp,data,size);free(data);if(error)return error;
    error=a36_storage_config_file(temp,sd2,false);if(error)return error;
    error=a36_storage_read(temp,&data,&size);if(error)return error;
    char *record=malloc(size+9);if(!record){free(data);return ENOMEM;}
    memcpy(record,sd2?"A36ES1 1\n":"A36ES1 0\n",9);memcpy(record+9,data,size);free(data);
    error=a36_storage_write(temp,record,size+9);free(record);if(error)return error;
    if(rename(temp,pending))return errno;
    error=a36_storage_sync_parent(pending);return error?error:a36_storage_recover_config(s);
}
static int a36_storage_recover_cli(const char *root) {
    struct a36_storage s={0};if(!realpath(root,s.root))return 1;
    int error=a36_storage_recover_config(&s);if(error){errno=error;perror("guest storage config recovery");return 1;}return 0;
}
static void a36_storage_serve(struct a36_storage *s,long long now) {
    if(s->socket<0)return;
    a36_storage_tick(s,now);
    int fd=accept4(s->socket,NULL,NULL,SOCK_NONBLOCK|SOCK_CLOEXEC);if(fd<0)return;
    struct {uint32_t magic,operation;} request;
    struct a36_storage_reply reply={.error=EINVAL};
    struct pollfd ready={.fd=fd,.events=POLLIN};
    s->next=0;a36_storage_tick(s,now);
    if(poll(&ready,1,250)>0&&recv(fd,&request,sizeof(request),MSG_DONTWAIT)==sizeof(request)&&request.magic==A36_STORAGE_MAGIC) {
        if(request.operation==1)reply.error=a36_storage_mount(s,0,reply.fs);
        if(request.operation==2)reply.error=a36_storage_unmount(s);
        if(request.operation==3)reply.error=a36_storage_mount(s,1,reply.fs);
        if(request.operation==4)reply.error=a36_storage_config(s,true);
        if(request.operation==5)reply.error=a36_storage_config(s,false);
        if(request.operation==6)reply.error=a36_storage_main(s,reply.fs);
    }
    ssize_t ignored=send(fd,&reply,sizeof(reply),MSG_NOSIGNAL);(void)ignored;close(fd);
}
static void a36_storage_init(struct a36_storage *s) {memset(s,0,sizeof(*s));s->host_ns=s->guest_ns=s->socket=-1;}
static int a36_storage_start(struct a36_storage *s,const char *root,int original_ns) {
    a36_storage_init(s);s->host_ns=original_ns;
    s->mount_api=a36_storage_api();
    if(!realpath(root,s->root))return -1;
    s->guest_ns=open("/proc/self/ns/mnt",O_RDONLY|O_CLOEXEC);if(s->guest_ns<0)return -1;
    if(a36_storage_capture(s))return -1;
    if(a36_storage_path(s->endpoint,sizeof(s->endpoint),s->root,"/run/andr36oid-storage.sock"))return -1;
    s->socket=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_NONBLOCK|SOCK_CLOEXEC,0);if(s->socket<0)return -1;
    struct sockaddr_un address={.sun_family=AF_UNIX};strcpy(address.sun_path,s->endpoint);
    if(unlink(s->endpoint)&&errno!=ENOENT)return -1;
    if(bind(s->socket,(struct sockaddr*)&address,sizeof(address))||chmod(s->endpoint,0660)||chown(s->endpoint,0,1023)||listen(s->socket,4))return -1;
    int recovery=a36_storage_recover_config(s);if(recovery){errno=recovery;return -1;}
    char choice[PATH_MAX];
    if(!a36_storage_path(choice,sizeof(choice),s->root,"/etc/emulationstation/.andr36oid-storage-choice")) {
        FILE *f=fopen(choice,"re");if(f){char value[16]={0};bool sd2=fgets(value,sizeof(value),f)&&!strcmp(value,"sd2\n");fclose(f);
            if(sd2){char fs[32];int error=a36_storage_mount(s,1,fs);if(error){errno=error;return -1;}}
        }
    }
    return 0;
}
static void a36_storage_close(struct a36_storage *s) {
    if(s->socket>=0)close(s->socket);
    if(s->host_ns>=0)close(s->host_ns);
    if(s->guest_ns>=0)close(s->guest_ns);
    /* Child closes only FDs. Parent removes pathname after the child is gone. */
    s->socket=s->host_ns=s->guest_ns=-1;
    free(s->entry);s->entry=NULL;s->entry_count=0;
}
static void a36_storage_finish(struct a36_storage *s) {
    if(s->socket>=0) {
        for(unsigned i=0;i<2;i++)if(s->bound[i]) {
            int ignored=a36_storage_detach_device(s,&s->owned[i]);(void)ignored;
        }
        int ignored=unlink(s->endpoint);(void)ignored;
    }
    a36_storage_close(s);
}
static int a36_storage_client(const char *verb) {
    unsigned op=!strcmp(verb,"mount-usb")?1:!strcmp(verb,"unmount-usb")?2:!strcmp(verb,"mount-sd2")?3:!strcmp(verb,"config-sd2")?4:!strcmp(verb,"config-main")?5:!strcmp(verb,"mount-main")?6:0;
    if(!op)return 2;
    int fd=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_NONBLOCK|SOCK_CLOEXEC,0);if(fd<0)return 32;
    struct sockaddr_un address={.sun_family=AF_UNIX};strcpy(address.sun_path,"/run/andr36oid-storage.sock");
#ifdef A36_STORAGE_FIXTURE
    const char *endpoint=getenv("A36_STORAGE_ENDPOINT");if(endpoint&&strlen(endpoint)<sizeof(address.sun_path))strcpy(address.sun_path,endpoint);
#endif
    struct {uint32_t magic,operation;} request={A36_STORAGE_MAGIC,op};struct a36_storage_reply reply;
    int result=32;
    if(connect(fd,(struct sockaddr*)&address,sizeof(address)))goto done;
    if(send(fd,&request,sizeof(request),MSG_NOSIGNAL)!=sizeof(request))goto done;
    struct pollfd ready={.fd=fd,.events=POLLIN};
    if(poll(&ready,1,2500)<=0){errno=ETIMEDOUT;goto done;}
    if(recv(fd,&reply,sizeof(reply),0)!=sizeof(reply)){errno=EPROTO;goto done;}
    if(reply.error){errno=reply.error;goto done;}
    if(reply.fs[0])puts(reply.fs);
    result=0;
done:
    if(result) {
        if(errno==EOPNOTSUPP)fputs("A drive plugged in after start needs the session restarted.\n",stderr);
        else perror("guest storage");
    }
    close(fd);return result;
}
#endif
