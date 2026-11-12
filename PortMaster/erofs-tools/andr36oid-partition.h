/* Explicit block transport. Never weaken regular-file request/image checks. */
#include <linux/fs.h>
#include <sys/sysmacros.h>
struct partition_root { int diskfd,partfd;uint64_t first,last,capacity,diskbytes;char diskid[33],partid[33],uuid[33],family[65]; };
static uint32_t pcrc(const unsigned char *p,size_t len) {
    uint32_t c=~0U;while(len--){c^=*p++;for(unsigned i=0;i<8;++i)c=(c>>1)^(0xedb88320U&-(c&1));}return ~c;
}
static void ptext(const char *path,char *out,size_t size) {
    struct stat st;int fd=regular(path,&st);ssize_t n=read(fd,out,size-1);if(n<0)die("partition sysfs read");close(fd);
    require(n>0 && (size_t)n<size-1,"partition sysfs length");out[n]=0;while(n && isspace((unsigned char)out[n-1]))out[--n]=0;
}
static int popen_block(const char *path,const char *sys,uint64_t *capacity) {
    int fd=open(path,O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);if(fd<0)die("partition block open");struct stat st;if(fstat(fd,&st))die("partition block stat");
#ifdef A36_PARTITION_FIXTURE
    if(S_ISREG(st.st_mode)){*capacity=(uint64_t)st.st_size;return fd;}
#endif
    require(S_ISBLK(st.st_mode),"partition requires block device");if(ioctl(fd,BLKGETSIZE64,capacity))die("partition capacity");
    const char *name=strrchr(path,'/');require(name && name[1],"partition node basename");char p[PATH_MAX],data[64];
    require(snprintf(p,sizeof(p),"%s/%s/dev",sys,name+1)<(int)sizeof(p),"partition sysfs path");ptext(p,data,sizeof(data));
    unsigned a,b;char tail;require(sscanf(data,"%u:%u%c",&a,&b,&tail)==2 && a==major(st.st_rdev) && b==minor(st.st_rdev),"partition node/sysfs mismatch");return fd;
}
static void plabel(const unsigned char *entry,const char *name) {
    size_t n=strlen(name);require(n<36,"GPT label");for(size_t i=0;i<36;++i)require(entry[56+i*2]==(i<n?(unsigned char)name[i]:0) && !entry[57+i*2],"GPT partition label");
}
static struct partition_root pdiscover(const char *disk,const char *part,const char *sys) {
    struct partition_root p={0};char expected[PATH_MAX],path[PATH_MAX],data[64];
    require(snprintf(expected,sizeof(expected),"%sp14",disk)<(int)sizeof(expected) && !strcmp(expected,part),"partition must be p14 of boot disk");
    p.diskfd=popen_block(disk,sys,&p.diskbytes);p.partfd=popen_block(part,sys,&p.capacity);
    const char *name=strrchr(part,'/');require(name!=NULL,"partition basename");
    require(snprintf(path,sizeof(path),"%s/%s/partition",sys,name+1)<(int)sizeof(path),"partition number path");ptext(path,data,sizeof(data));require(number(data)==14,"partition number");
    unsigned char h[512];readat(p.diskfd,h,512,512);require(!memcmp(h,"EFI PART",8) && le32(h+12)>=92 && le32(h+12)<=512,"GPT header");
    uint32_t crc=le32(h+16);memset(h+16,0,4);require(pcrc(h,le32(h+12))==crc,"GPT header checksum");
    require(p.diskbytes%512==0 && le64(h+24)==1 && le64(h+32)==p.diskbytes/512-1 && le64(h+40)>=34 && le64(h+48)==p.diskbytes/512-34,"GPT card bounds");
    require(le32(h+80)==128 && le32(h+84)==128 && le64(h+72)>=2 && le64(h+72)<=le64(h+40)-32,"GPT entry geometry");
    unsigned char entries[16384];readat(p.diskfd,entries,sizeof(entries),le64(h+72)*512);require(pcrc(entries,sizeof(entries))==le32(h+88),"GPT entries checksum");
    plabel(entries,"BOOT");plabel(entries+128,"system");const unsigned char *e=entries+13*128;plabel(e,"DARKOS");
    static const unsigned char linux_type[16]={0xaf,0x3d,0xc6,0x0f,0x83,0x84,0x72,0x47,0x8e,0x79,0x3d,0x69,0xd8,0x47,0x7d,0xe4};
    require(!memcmp(e,linux_type,16),"DARKOS GPT type");p.first=le64(e+32);p.last=le64(e+40);
    require(p.first>=le64(h+40) && p.first%2048==0 && p.last==le64(h+48) && p.first<=p.last && (p.last-p.first+1)*512==p.capacity,"DARKOS geometry");
    require(snprintf(path,sizeof(path),"%s/%s/start",sys,name+1)<(int)sizeof(path),"partition start path");ptext(path,data,sizeof(data));require(number(data)==p.first,"GPT/kernel partition start");
    require(snprintf(path,sizeof(path),"%s/%s/size",sys,name+1)<(int)sizeof(path),"partition size path");ptext(path,data,sizeof(data));require(number(data)==p.capacity/512,"GPT/kernel partition size");
    for(unsigned i=0;i<128;++i)if(i!=13){
        const unsigned char *other=entries+i*128;unsigned nonzero=0;for(unsigned k=0;k<16;++k)nonzero|=other[k];
        if(nonzero)require(le64(other+32)<=le64(other+40) && le64(other+40)<p.first,"DARKOS overlaps another partition");
    }
    tohex(h+56,16,p.diskid);tohex(e+16,16,p.partid);require(strspn(p.diskid,"0")!=32 && strspn(p.partid,"0")!=32,"GPT unique identity");
    char family[128];int n=snprintf(family,sizeof(family),"a36-partition-v1:%s:%s",p.diskid,p.partid);sha(family,(size_t)n,p.family);
    return p;
}
static void psuper(struct partition_root *p,uint64_t length) {
    require(length>=4096 && length<=p->capacity,"partition image length");unsigned char b[128];readat(p->partfd,b,sizeof(b),1024);
    require(le32(b)==0xe0f5e1e2 && b[12]==12 && !(le32(b+80)&~1U),"partition EROFS features");
    require(le32(b+36) && (uint64_t)le32(b+36)*4096<=length,"partition EROFS bounds");tohex(b+48,16,p->uuid);
}
static char *precord(struct partition_root *p,struct release *r,const char *manifest_hash) {
    char *s=NULL;int n=asprintf(&s,"version=2\norigin=release\ntransport=partition\nsource_id=%s\ngeneration=%s\ndisk_uuid=%s\npart_uuid=%s\nfirst=%" PRIu64 "\ncapacity=%" PRIu64 "\ndarkosen=%s\nrevision=%" PRIu64 "\nimage_sha256=%s\nmanifest_sha256=%s\noutput_size=%" PRIu64 "\noutput_uuid=%s\n",p->family,r->generation,p->diskid,p->partid,p->first,p->capacity,r->darkosen,r->revision,r->hash,manifest_hash,r->size,p->uuid);
    require(n>0 && n<4096 && s,"partition completion record");return s;
}
static int pcached(const char *file,const char *expected) {
    struct stat st;if(lstat(file,&st)){require(errno==ENOENT,"partition cache stat");return 0;}
    int fd=regular(file,&st);require(st.st_size>0 && st.st_size<4096,"partition cache size");char data[4096]={0};readat(fd,data,(size_t)st.st_size,0);close(fd);return (size_t)st.st_size==strlen(expected) && !memcmp(data,expected,(size_t)st.st_size);
}
static void ppublish(const char *dir,const char *file,const char *record) {
    char tmp[PATH_MAX];require(snprintf(tmp,sizeof(tmp),"%s.tmp.XXXXXX",file)<(int)sizeof(tmp),"partition cache temp");
    int fd=mkostemp(tmp,O_CLOEXEC);if(fd<0)die("partition cache create");
    rwrite(fd,record,strlen(record));if(fsync(fd))die("partition cache fsync");close(fd);
    if(rename(tmp,file))die("partition cache rename");
    fd=open(dir,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(fd<0 || fsync(fd))die("partition cache directory fsync");close(fd);
}
static void partition_info(const char *manifest,const char *disk,const char *part,const char *sys,const char *cache,const char *build,const char *progress,int cached_only) {
    struct release r=rload_kind(manifest,"",1);rminimum(r.minimum,build);struct partition_root p=pdiscover(disk,part,sys);
    char guard[PATH_MAX];struct stat held;
    require(snprintf(guard,sizeof(guard),"%s/%s.updating",cache,p.family)<(int)sizeof(guard),"partition update guard path");
    if(!lstat(guard,&held))require(0,"partition update pending; use verified release file");
    require(errno==ENOENT,"partition update guard stat");psuper(&p,r.size);
    struct stat st;int fd=regular(manifest,&st);require(st.st_size>0 && st.st_size<=REL_JSON,"partition manifest length");char data[REL_JSON],mh[65];readat(fd,data,(size_t)st.st_size,0);close(fd);sha(data,(size_t)st.st_size,mh);
    char file[PATH_MAX];require(snprintf(file,sizeof(file),"%s/%s.meta",cache,p.family)<(int)sizeof(file),"partition cache path");char *rec=precord(&p,&r,mh);
    int hit=pcached(file,rec);if(!hit){
        require(!cached_only,"partition not yet validated");struct sha256_state hash;sha_init(&hash);unsigned char b[262144];uint64_t done=0;
        while(done<r.size){size_t n=r.size-done>sizeof(b)?sizeof(b):(size_t)(r.size-done);readat(p.partfd,b,n,done);sha_update(&hash,b,n);done+=n;rprogress(progress,done);}
        char out[65];sha_finish(&hash,out);require(!strcmp(out,r.hash),"partition image checksum");ppublish(cache,file,rec);
    }
    printf("%sformat=erofs\nsize=%" PRIu64 "\nsource_path=%s\nsource_name=%s\nsource_size=0\ncache_hit=%d\n",rec,r.size,part,r.name,hit);
    free(rec);close(p.diskfd);close(p.partfd);
}
static int partition_command(int argc,char **argv) {
    if(!strcmp(argv[1],"partition-describe") && argc==3){struct release r=rload_kind(argv[2],"",1);printf("size=%" PRIu64 "\ngeneration=%s\nimage_sha256=%s\n",r.size,r.generation,r.hash);return 1;}
    if(!strcmp(argv[1],"partition-pin") && argc==4){
        (void)rload_kind(argv[2],"",1);struct stat st;int fd=regular(argv[2],&st);require(st.st_size>0 && st.st_size<=REL_JSON,"partition manifest size");
        char data[REL_JSON+1];readat(fd,data,(size_t)st.st_size,0);data[st.st_size]=0;close(fd);
        char *dir=strdup(argv[3]);require(dir!=NULL,"partition manifest directory");char *slash=strrchr(dir,'/');require(slash!=NULL,"partition manifest path");*slash=0;
        ppublish(dir,argv[3],data);free(dir);(void)rload_kind(argv[3],"",1);return 1;
    }
    if((!strcmp(argv[1],"partition-info") || !strcmp(argv[1],"partition-cached")) && argc==9){
        partition_info(argv[2],argv[3],argv[4],argv[5],argv[6],argv[7],argv[8],!strcmp(argv[1],"partition-cached"));return 1;
    }
    return 0;
}
