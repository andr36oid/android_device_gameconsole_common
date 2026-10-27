#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <inttypes.h>
#include <limits.h>
#include <linux/loop.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>

/* Wire output is bounded key=value text. Never evaluate it as shell code. */
static void die(const char *what) { perror(what); exit(1); }
static void require(int ok, const char *what) { if (!ok) { errno = EINVAL; die(what); } }
static uint32_t le32(const unsigned char *p) {
    return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24;
}
static uint64_t le64(const unsigned char *p) { return le32(p) | (uint64_t)le32(p+4)<<32; }
static uint64_t number(const char *s) {
    char *end; errno = 0;
    require(*s && strspn(s, "0123456789") == strlen(s), "decimal");
    uint64_t n = strtoull(s, &end, 10); require(!errno && !*end, "decimal overflow"); return n;
}
static void readat(int fd, void *buf, size_t len, uint64_t off) {
    require(off <= INT64_MAX, "offset overflow");
    if (pread(fd, buf, len, (off_t)off) != (ssize_t)len) die("short image read");
}
static int hex(const char *s, size_t n) {
    return strlen(s) == n && strspn(s, "0123456789abcdef") == n;
}
static void tohex(const unsigned char *p, size_t n, char *out) {
    const char *h = "0123456789abcdef";
    for (size_t i=0; i<n; ++i) { out[i*2]=h[p[i]>>4]; out[i*2+1]=h[p[i]&15]; }
    out[n*2]=0;
}

/* SHA-256, also used to validate the selection ID inside a completion record. */
static const uint32_t k[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2 };
static uint32_t rr(uint32_t x, unsigned n) { return x>>n | x<<(32-n); }
static void sha(const void *input, size_t len, char out[65]) {
    uint32_t h[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    size_t total=(len+9+63)&~(size_t)63;
    unsigned char *p=calloc(1,total); if (!p) die("calloc");
    memcpy(p,input,len); p[len]=0x80;
    for (unsigned j=0;j<8;++j) p[total-1-j]=(uint64_t)len*8>>(j*8);
    for (size_t pos=0;pos<total;pos+=64) {
        uint32_t w[64];
        for (unsigned j=0;j<16;++j) { unsigned char *b=p+pos+j*4; w[j]=(uint32_t)b[0]<<24|(uint32_t)b[1]<<16|(uint32_t)b[2]<<8|b[3]; }
        for (unsigned j=16;j<64;++j) w[j]=w[j-16]+(rr(w[j-15],7)^rr(w[j-15],18)^(w[j-15]>>3))+w[j-7]+(rr(w[j-2],17)^rr(w[j-2],19)^(w[j-2]>>10));
        uint32_t a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],v=h[7];
        for (unsigned j=0;j<64;++j) {
            uint32_t t=v+(rr(e,6)^rr(e,11)^rr(e,25))+((e&f)^(~e&g))+k[j]+w[j];
            uint32_t u=(rr(a,2)^rr(a,13)^rr(a,22))+((a&b)^(a&c)^(b&c));
            v=g;g=f;f=e;e=d+t;d=c;c=b;b=a;a=t+u;
        }
        h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=d;h[4]+=e;h[5]+=f;h[6]+=g;h[7]+=v;
    }
    free(p);
    for (unsigned j=0;j<8;++j) snprintf(out+j*8,9,"%08" PRIx32,h[j]);
}
static int basename_ok(const char *s) {
    size_t n=strlen(s);
    if (!n || n>NAME_MAX || strchr(s,'/') || n<4 || strcmp(s+n-4,".img")) return 0;
    for (size_t i=0;i<n;++i) {
        unsigned char c=(unsigned char)s[i];
        if (c<32 || c==127) return 0;
        if (c>=128) {
            unsigned more; uint32_t cp,minimum;
            if (c>=0xc2 && c<=0xdf) { more=1;cp=c&31;minimum=0x80; }
            else if (c>=0xe0 && c<=0xef) { more=2;cp=c&15;minimum=0x800; }
            else if (c>=0xf0 && c<=0xf4) { more=3;cp=c&7;minimum=0x10000; }
            else return 0;
            if (i+more>=n) return 0;
            while (more--) { c=(unsigned char)s[++i]; if ((c&0xc0)!=0x80) return 0; cp=cp<<6|(c&63); }
            if (cp<minimum || cp>0x10ffff || (cp>=0x80 && cp<=0x9f) || (cp>=0xd800 && cp<=0xdfff)) return 0;
        }
    }
    return 1;
}
static void selection(const char *uuid, const char *name, char out[65]) {
    size_t n=strlen(uuid), m=strlen(name); require(n && n<128 && basename_ok(name), "selection input");
    unsigned char buf[512]; require(n+1+m<=sizeof(buf),"selection length");
    for (size_t i=0;i<n;++i) buf[i]=tolower((unsigned char)uuid[i]);
    buf[n]=0; memcpy(buf+n+1,name,m); sha(buf,n+1+m,out);
}
struct image { struct stat st; int erofs; uint64_t offset,sectors; char uuid[33], generation[65]; };
static int regular(const char *path, struct stat *st) {
    int fd=open(path,O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC); if (fd<0) die(path);
    if (fstat(fd,st)) die("fstat");
    require(S_ISREG(st->st_mode),"regular file required"); return fd;
}
static struct image probe(const char *path) {
    struct image x={0}; int fd=regular(path,&x.st); unsigned char b[4096];
    require(x.st.st_size>=4096,"short image"); readat(fd,b,128,1024);
    if (le32(b)==0xe0f5e1e2) {
        require(b[12]==12 && !(le32(b+80)&~1U),"unsupported EROFS features");
        require((uint64_t)le32(b+36)*4096<=(uint64_t)x.st.st_size && le32(b+36),"EROFS bounds");
        x.erofs=1; tohex(b+48,16,x.uuid);
    } else {
        readat(fd,b,512,0); require(b[510]==0x55 && b[511]==0xaa,"MBR signature");
        unsigned i;
        for (i=0;i<4;++i) if (b[446+16*i+4]==0x83) break;
        require(i<4,"MBR Linux partition");
        x.offset=(uint64_t)le32(b+446+16*i+8)*512;
        x.sectors=le32(b+446+16*i+12);
        uint64_t bytes=x.sectors*512;
        require(x.offset>=512 && bytes>=69632 && x.offset<=(uint64_t)x.st.st_size && bytes<=(uint64_t)x.st.st_size-x.offset,"partition bounds");
        readat(fd,b,4096,x.offset+65536);
        require(!memcmp(b+64,"_BHRfS_M",8) && le64(b+48)==65536,"btrfs superblock");
        require(le64(b+112)<=bytes && le64(b+112)>=69632 && le64(b+136)==1,"single-device btrfs bounds");
        tohex(b+32,16,x.uuid);
        char stamp[256]; int n=snprintf(stamp,sizeof(stamp),"%" PRIu64 ":%" PRIu64 ":%" PRIu64 ":%" PRIu64 ":%" PRIu64 ":%s:%" PRIu64,
            (uint64_t)x.st.st_size,(uint64_t)x.st.st_mtim.tv_sec,(uint64_t)x.st.st_mtim.tv_nsec,x.offset,x.sectors,x.uuid,le64(b+72));
        sha(stamp,(size_t)n,x.generation);
    }
    close(fd); return x;
}
static void print_probe(struct image x) {
    printf("format=%s\nsize=%" PRIu64 "\noffset=%" PRIu64 "\nsectors=%" PRIu64 "\nfs_uuid=%s\ngeneration=%s\ndevice=%" PRIu64 "\ninode=%" PRIu64 "\n",
        x.erofs?"erofs":"btrfs",(uint64_t)x.st.st_size,x.offset,x.sectors,x.uuid,x.generation,(uint64_t)x.st.st_dev,(uint64_t)x.st.st_ino);
}
static void record(const char *path, const char *uuid, const char *orphan_job) {
    struct image x={0};
    if (orphan_job) {
        struct stat absent;
        require(lstat(path,&absent)<0 && errno==ENOENT,"orphan requires absent output");
    } else { x=probe(path); require(x.erofs,"completion requires EROFS"); }
    char meta[PATH_MAX]; require(snprintf(meta,sizeof(meta),"%s.meta",path)<(int)sizeof(meta),"meta path");
    struct stat st; int fd=regular(meta,&st); require(st.st_size>0 && st.st_size<4096,"record size");
    FILE *f=fdopen(fd,"r"); if (!f) die("fdopen");
    const char *keys[]={"version","job","source_name_hex","source_id","generation","offset","sectors","source_size","card_uuid","tool","options","output_size","output_uuid"};
    char values[13][768]={{0}},line[1024]; unsigned mask=0;
    while (fgets(line,sizeof(line),f)) {
        size_t n=strlen(line); require(n && line[n-1]=='\n',"record line"); line[--n]=0;
        char *eq=strchr(line,'='); require(eq!=NULL,"record field"); *eq++=0;
        unsigned i; for (i=0;i<13;++i) if (!strcmp(line,keys[i])) break;
        require(i<13 && !(mask&(1U<<i)) && strlen(eq)<sizeof(values[0]),"record key");
        strcpy(values[i],eq); mask|=1U<<i;
    }
    require(!ferror(f) && mask==8191,"complete record"); fclose(f);
    require(!strcmp(values[0],"1") && hex(values[1],16) && hex(values[3],64) && hex(values[4],64),"record version/identity");
    size_t n=strlen(values[2]); require(n && !(n&1) && n<=NAME_MAX*2 && hex(values[2],n),"record basename hex");
    char name[NAME_MAX+1];
    for (size_t i=0;i<n/2;++i) { unsigned c; require(sscanf(values[2]+2*i,"%2x",&c)==1 && c,"record basename"); name[i]=(char)c; } name[n/2]=0;
    require(basename_ok(name),"record basename");
    char id[65]; selection(uuid,name,id);
    require(!strcmp(values[3],id) && !strcmp(values[8],uuid),"record card/selection");
    uint64_t offset=number(values[5]),sectors=number(values[6]),size=number(values[7]);
    require(offset>=512 && offset%512==0 && sectors>=136 && sectors<=UINT32_MAX && offset<=size && sectors*512<=size-offset,"record geometry");
    require(!strcmp(values[9],"erofs-utils-1.8.10") && !strcmp(values[10],"b4096,lz4,no-xattr-filter,preserve-mtime,workers1"),"record tools");
    if (orphan_job) {
        require(!strcmp(values[1],orphan_job) && number(values[11])>=4096 && hex(values[12],32),"orphan job/output identity");
        printf("meta_device=%" PRIu64 "\nmeta_inode=%" PRIu64 "\n",(uint64_t)st.st_dev,(uint64_t)st.st_ino);
    } else require(number(values[11])==(uint64_t)x.st.st_size && !strcmp(values[12],x.uuid),"record output identity");
    for (unsigned i=0;i<13;++i) printf("%s=%s\n",keys[i],values[i]);
    printf("source_name=%s\n",name);
}
static uint64_t add(uint64_t a,uint64_t b) { require(a<=UINT64_MAX-b,"estimate overflow"); return a+b; }
static uint64_t round_up(uint64_t n,uint64_t unit) { return add(n,unit-1)/unit*unit; }
static uint64_t estimate_bytes,cluster;
static int overlay_walk(const char *path,const struct stat *st,int type,struct FTW *ftw) {
    (void)st; (void)ftw;
    require(type!=FTW_NS && type!=FTW_DNR,"unreadable legacy upper");
    ssize_t n=llistxattr(path,NULL,0); if (n<0) die("legacy listxattr");
    char *list=malloc((size_t)n+1); if (!list) die("malloc");
    if (llistxattr(path,list,(size_t)n)!=n) die("legacy listxattr");
    for (char *p=list;p<list+n;p+=strlen(p)+1) {
        if (!strncmp(p,"trusted.overlay.",16) || !strncmp(p,"user.overlay.",13))
            require(!strcmp(p,"trusted.overlay.opaque") || !strcmp(p,"user.overlay.opaque"),"legacy overlay references require migration");
    }
    free(list); return 0;
}
static int walk(const char *path,const struct stat *st,int type,struct FTW *ftw) {
    (void)ftw; require(type!=FTW_NS && type!=FTW_DNR,"unreadable source tree");
    estimate_bytes=add(estimate_bytes,round_up(8192,cluster));
    if (S_ISREG(st->st_mode)) estimate_bytes=add(estimate_bytes,round_up((uint64_t)st->st_size,cluster));
    ssize_t n=llistxattr(path,NULL,0); if (n<0) die("listxattr");
    if (n) {
        char *attrs=malloc((size_t)n); if (!attrs) die("malloc");
        if (llistxattr(path,attrs,(size_t)n)!=n) die("listxattr changed");
        uint64_t bytes=(uint64_t)n;
        for (char *p=attrs;p<attrs+n;p+=strlen(p)+1) { ssize_t v=lgetxattr(path,p,NULL,0); if (v<0) die("getxattr"); bytes=add(bytes,(uint64_t)v); }
        estimate_bytes=add(estimate_bytes,round_up(bytes,cluster)); free(attrs);
    }
    return 0;
}
static uint64_t space(const char *path,int print) {
    struct statvfs v; struct statfs f;
    if (statvfs(path,&v) || statfs(path,&f)) die("destination space");
    require(!(v.f_flag&ST_RDONLY),"read-only destination");
    cluster=v.f_frsize?v.f_frsize:v.f_bsize; require(cluster && v.f_bavail<=UINT64_MAX/cluster,"space overflow");
    uint64_t avail=v.f_bavail*cluster;
    uint64_t limit=(unsigned long)f.f_type==0x4d44?UINT32_MAX:INT64_MAX;
    if (print) printf("available=%" PRIu64 "\ncluster=%" PRIu64 "\nfile_limit=%" PRIu64 "\n",avail,cluster,limit);
    return limit;
}
static void identity(const char *path,const char *dev,const char *ino,int flush) {
    struct stat st; int fd=regular(path,&st);
    require((uint64_t)st.st_dev==number(dev) && (uint64_t)st.st_ino==number(ino),"staging identity changed");
    if (flush && fsync(fd)) die("fsync staging");
    close(fd);
}
static void metadata(const char *root) {
    if (chroot(root) || chdir("/")) die("chroot metadata");
    struct stat st;
    require(!stat("/usr/bin/emulationstation",&st) && S_ISDIR(st.st_mode),"EmulationStation directory");
    const char *required[]={"/sbin/init","/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"};
    for (unsigned i=0;i<2;++i) require(!stat(required[i],&st) && S_ISREG(st.st_mode) && (st.st_mode&0111),"essential executable");
    const char *samples[]={"/etc/passwd","/etc/group","/bin/sh","/sbin/init","/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1","/usr/bin/emulationstation/emulationstation",
        "/usr/bin/sh","/lib/systemd/systemd","/usr/bin/ping","/bin/ping"};
    for (unsigned i=0;i<sizeof(samples)/sizeof(samples[0]);++i) {
        const char *p=samples[i];
        if (lstat(p,&st)) {
            if (i>=6 && errno==ENOENT) { printf("absent:%s\n",p); continue; }
            die("sample metadata");
        }
        printf("%s:%o:%u:%u:%" PRIu64 ":%" PRIu64 ":%ld:%" PRIu64 "\n",p,st.st_mode,st.st_uid,st.st_gid,(uint64_t)st.st_size,(uint64_t)st.st_mtim.tv_sec,st.st_mtim.tv_nsec,(uint64_t)st.st_nlink);
        if (S_ISLNK(st.st_mode)) { char b[PATH_MAX]; ssize_t n=readlink(p,b,sizeof(b)); require(n>0 && n<(ssize_t)sizeof(b),"symlink"); char h[65]; sha(b,(size_t)n,h); puts(h); }
        ssize_t n=llistxattr(p,NULL,0); if (n<0) die("sample xattrs");
        char *list=malloc((size_t)n+1); if (!list) die("malloc");
        if (llistxattr(p,list,(size_t)n)!=n) die("sample xattrs");
        /* Sort names so filesystem enumeration order does not affect comparison. */
        char *names[256]; unsigned count=0;
        for (char *a=list;a<list+n;a+=strlen(a)+1) { require(count<256,"xattr count"); names[count++]=a; }
        for (unsigned a=0;a<count;++a) for (unsigned b=a+1;b<count;++b) if (strcmp(names[a],names[b])>0) { char *t=names[a];names[a]=names[b];names[b]=t; }
        for (unsigned a=0;a<count;++a) {
            ssize_t size=lgetxattr(p,names[a],NULL,0); if (size<0) die("sample xattr size");
            void *value=malloc((size_t)size+1); if (!value) die("malloc");
            if (lgetxattr(p,names[a],value,(size_t)size)!=size) die("sample xattr value");
            char h[65]; sha(value,(size_t)size,h); printf("%s=%s\n",names[a],h); free(value);
        }
        free(list);
    }
}
static int name_order(const void *a,const void *b) { return strcmp(*(char * const *)a,*(char * const *)b); }
static void list_candidates(const char *path) {
    char canonical[PATH_MAX];
    require(realpath(path,canonical)!=NULL && !strcmp(path,canonical),"canonical image directory");
    DIR *dir=opendir(path); if (!dir) die("list images");
    char **names=NULL; size_t count=0; struct dirent *entry;
    for (;;) {
        errno=0; entry=readdir(dir);
        if (!entry) { if (errno) die("read image directory"); break; }
        if (!basename_ok(entry->d_name)) continue;
        struct stat st;
        if (fstatat(dirfd(dir),entry->d_name,&st,AT_SYMLINK_NOFOLLOW) || !S_ISREG(st.st_mode)) continue;
        char **next=realloc(names,(count+1)*sizeof(*names)); if (!next) die("image list allocation"); names=next;
        names[count]=strdup(entry->d_name); if (!names[count++]) die("image name allocation");
    }
    closedir(dir); qsort(names,count,sizeof(*names),name_order);
    for (size_t i=0;i<count;++i) { puts(names[i]);free(names[i]); } free(names);
}
int main(int argc,char **argv) {
    require(argc>=2,"command"); const char *cmd=argv[1];
    if (!strcmp(cmd,"id") && argc==4) { char out[65]; selection(argv[2],argv[3],out); puts(out); }
    else if (!strcmp(cmd,"list") && argc==3) list_candidates(argv[2]);
    else if (!strcmp(cmd,"overlay-safe") && argc==3) {
        if (nftw(argv[2],overlay_walk,32,FTW_PHYS|FTW_MOUNT)) die("legacy upper walk");
    }
    else if (!strcmp(cmd,"probe") && argc==3) print_probe(probe(argv[2]));
    else if (!strcmp(cmd,"candidate") && argc==4) {
        require(basename_ok(argv[3]),"candidate basename");
        char canonical[PATH_MAX],path[PATH_MAX]; require(realpath(argv[2],canonical)!=NULL && !strcmp(canonical,argv[2]),"canonical directory");
        require(snprintf(path,sizeof(path),"%s/%s",canonical,argv[3])<(int)sizeof(path),"candidate path");
        print_probe(probe(path));
    } else if (!strcmp(cmd,"record") && argc==4) record(argv[2],argv[3],NULL);
    else if (!strcmp(cmd,"orphan") && argc==5) record(argv[2],argv[3],argv[4]);
    else if (!strcmp(cmd,"space") && argc==3) space(argv[2],1);
    else if (!strcmp(cmd,"estimate") && argc==4) {
        uint64_t limit=space(argv[3],1); if (cluster<4096) cluster=4096;
        if (nftw(argv[2],walk,32,FTW_PHYS|FTW_MOUNT)) die("source walk");
        uint64_t overhead=add(estimate_bytes,99)/100*15;
        uint64_t output=add(estimate_bytes,overhead);
        printf("required=%" PRIu64 "\noutput_bound=%" PRIu64 "\n",add(output,512ULL*1024*1024),output);
        require(output<=limit,"destination file-size limit");
    } else if (!strcmp(cmd,"metadata") && argc==3) metadata(argv[2]);
    else if (!strcmp(cmd,"reserve") && argc==3) {
        int fd=open(argv[2],O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600); if (fd<0) die("reserve staging");
        struct stat st; if (fstat(fd,&st)) die("reserve stat"); close(fd);
        printf("device=%" PRIu64 "\ninode=%" PRIu64 "\n",(uint64_t)st.st_dev,(uint64_t)st.st_ino);
    } else if (!strcmp(cmd,"fsync") && argc==3) {
        int fd=open(argv[2],O_RDONLY|O_NOFOLLOW|O_CLOEXEC); if (fd<0 || fsync(fd)) die("fsync"); close(fd);
    } else if (!strcmp(cmd,"publish") && argc==6) {
        /* No fallback to mv/link/unlink: fail closed on unsupported filesystems. */
        char *a=strdup(argv[2]),*b=strdup(argv[3]); if (!a || !b) die("strdup");
        char *as=strrchr(a,'/'),*bs=strrchr(b,'/'); require(as && bs,"publication paths"); *as=0;*bs=0;
        require(!strcmp(a,b),"publication directory");
        int dir=open(a,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC); if (dir<0) die("publication directory");
        identity(argv[2],argv[4],argv[5],1);
        if (fsync(dir)) die("fsync directory before rename");
        if (syscall(SYS_renameat2,dir,as+1,dir,bs+1,1)) die("renameat2 NOREPLACE");
        if (fsync(dir)) die("fsync directory after rename");
        close(dir); free(a);free(b);
    } else if (!strcmp(cmd,"remove") && argc==5) {
        identity(argv[2],argv[3],argv[4],0); if (unlink(argv[2])) die("remove staging");
    } else if (!strcmp(cmd,"namespace") && argc>=3) {
        if (unshare(CLONE_NEWNS) || mount(NULL,"/",NULL,MS_REC|MS_PRIVATE,NULL)) die("private mount namespace");
        if (setenv("A36_OPT_PRIVATE","1",1)) die("namespace marker");
        execv(argv[2],argv+2); die("exec namespace worker");
    } else if (!strcmp(cmd,"child") && argc>=3) {
        pid_t parent=getppid(); require(parent>1,"orphan worker child");
        if (prctl(PR_SET_PDEATHSIG,SIGKILL)) die("parent-death signal");
        require(getppid()==parent,"worker exited before child started");
        execv(argv[2],argv+2); die("exec worker child");
    } else if ((!strcmp(cmd,"detach-owned") || !strcmp(cmd,"loop-owned")) && argc==6) {
        int fd=open(argv[2],O_RDONLY|O_CLOEXEC); if (fd<0) die("owned loop");
        struct loop_info64 info;
        if (ioctl(fd,LOOP_GET_STATUS64,&info)) die("loop status");
        require(info.lo_device==number(argv[3]) && info.lo_inode==number(argv[4]) && info.lo_offset==number(argv[5]) && (info.lo_flags&LO_FLAGS_READ_ONLY),"loop ownership");
        if (!strcmp(cmd,"detach-owned") && ioctl(fd,LOOP_CLR_FD,0)) die("detach owned loop");
        close(fd);
    } else { errno=EINVAL; die("unknown command/arguments"); }
    return 0;
}
