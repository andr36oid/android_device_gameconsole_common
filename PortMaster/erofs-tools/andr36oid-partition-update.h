/* Release-file -> explicit DARKOS transport. Storage/operation/BOOT locks and
 * selection/lifecycle journals belong to the broker; these commands never
 * discover or write an arbitrary disk, and never remove the verified fallback
 * until the broker has durably selected the verified partition.
 */
static char *up_manifest(const char *path,struct release *release,char hash[65]) {
    *release=rload_kind(path,"",1);
    struct stat st;int fd=regular(path,&st);
    require(st.st_size>0 && st.st_size<=REL_JSON,"update manifest length");
    char *data=calloc((size_t)st.st_size+1,1);require(data!=NULL,"update manifest allocation");
    readat(fd,data,(size_t)st.st_size,0);close(fd);sha(data,(size_t)st.st_size,hash);
    return data;
}
static void up_close(struct partition_root *p) { close(p->diskfd);close(p->partfd); }
static void up_path(char out[PATH_MAX],const char *cache,const char *family,const char *suffix) {
    require(snprintf(out,PATH_MAX,"%s/%s%s",cache,family,suffix)<PATH_MAX,"update cache path");
}
static char *up_guard(struct partition_root *p,struct release *r,const char *hash) {
    char *s=NULL;require(asprintf(&s,"version=1\nfamily=%s\ngeneration=%s\nmanifest_sha256=%s\n",p->family,r->generation,hash)>0 && s,"update guard record");return s;
}
static void up_held(const char *cache,struct partition_root *p,struct release *r,const char *hash) {
    char file[PATH_MAX];up_path(file,cache,p->family,".updating");char *guard=up_guard(p,r,hash);
    require(pcached(file,guard),"partition update guard mismatch");free(guard);
}
static struct release up_release(const char *image,const char *uuid) {
    char meta[PATH_MAX];require(snprintf(meta,sizeof(meta),"%s.meta",image)<(int)sizeof(meta),"update source record path");
    struct release r={0};require(release_record_read(image,meta,uuid,NULL,NULL,&r,0),"update requires release file");return r;
}
static void up_same(struct release *expected,struct release *actual) {
    require(!strcmp(expected->name,actual->name) && expected->size==actual->size &&
            !strcmp(expected->generation,actual->generation) && !strcmp(expected->hash,actual->hash) &&
            !strcmp(expected->minimum,actual->minimum),"update source differs from pinned manifest");
}
static void up_hash(int fd,uint64_t length,const char *expected,const char *progress) {
    struct sha256_state hash;sha_init(&hash);unsigned char data[262144];uint64_t done=0;
    while(done<length){size_t n=length-done>sizeof(data)?sizeof(data):(size_t)(length-done);
        readat(fd,data,n,done);sha_update(&hash,data,n);done+=n;rprogress(progress,done);}
    char actual[65];sha_finish(&hash,actual);require(!strcmp(actual,expected),"partition update image checksum");
}
static void up_json_string(const char *text) {
    for(const unsigned char *p=(const unsigned char *)text;*p;++p)
        require(*p>=32 && *p<127 && *p!='"' && *p!='\\',"update manifest string");
}
static void up_make_manifest(struct release *r,const char *manifest) {
    up_json_string(r->name);up_json_string(r->minimum);
    char *json=NULL;require(asprintf(&json,"{\"format\":1,\"darkosen\":\"%s\",\"revision\":%" PRIu64 ",\"minimum_andr36oid\":\"%s\",\"image\":{\"name\":\"%s\",\"size\":%" PRIu64 ",\"sha256\":\"%s\"}}\n",r->darkosen,r->revision,r->minimum,r->name,r->size,r->hash)>0 && json,"update manifest serialization");
    char *dir=strdup(manifest);require(dir!=NULL,"update manifest directory");char *slash=strrchr(dir,'/');require(slash!=NULL,"update manifest path");*slash=0;
    ppublish(dir,manifest,json);(void)rload_kind(manifest,"",1);free(dir);free(json);
}
static void up_plan(const char *image,const char *uuid,const char *disk,const char *part,
                    const char *sys,const char *build,const char *manifest) {
    struct release r=up_release(image,uuid);rminimum(r.minimum,build);
    struct partition_root p=pdiscover(disk,part,sys);up_make_manifest(&r,manifest);
    printf("source_id=%s\n",p.family);
    printf("generation=%s\nsize=%" PRIu64 "\ncapacity=%" PRIu64 "\nfits=%d\ndisk_uuid=%s\npart_uuid=%s\nfirst=%" PRIu64 "\n",r.generation,r.size,p.capacity,r.size<=p.capacity,p.diskid,p.partid,p.first);
    up_close(&p);
}
static void up_hold(const char *manifest,const char *disk,const char *part,const char *sys,const char *cache) {
    struct release r;char hash[65];char *json=up_manifest(manifest,&r,hash);
    struct partition_root p=pdiscover(disk,part,sys);require(r.size<=p.capacity,"update image exceeds DARKOS capacity");
    char file[PATH_MAX];up_path(file,cache,p.family,".updating");char *guard=up_guard(&p,&r,hash);
    ppublish(cache,file,guard);printf("source_id=%s\ngeneration=%s\n",p.family,r.generation);
    free(json);free(guard);up_close(&p);
}
static int up_output(const char *part,struct partition_root *p,const char *disk,const char *sys) {
    int out=open(part,O_RDWR|O_NOFOLLOW|O_CLOEXEC);if(out<0)die("DARKOS write open");
    struct stat old,now;if(fstat(p->partfd,&old) || fstat(out,&now))die("DARKOS write stat");
    require(old.st_dev==now.st_dev && old.st_ino==now.st_ino && old.st_rdev==now.st_rdev &&
            (S_ISBLK(now.st_mode)
#ifdef A36_PARTITION_FIXTURE
             || S_ISREG(now.st_mode)
#endif
             ),"DARKOS write identity changed");
    struct partition_root fresh=pdiscover(disk,part,sys);
    require(!strcmp(fresh.diskid,p->diskid) && !strcmp(fresh.partid,p->partid) &&
            fresh.first==p->first && fresh.capacity==p->capacity,"DARKOS card changed before write");
    up_close(&fresh);return out;
}
static void up_copy(const char *manifest,const char *image,const char *uuid,const char *disk,
                    const char *part,const char *sys,const char *cache,const char *progress) {
    struct release r,source=up_release(image,uuid);char hash[65];char *json=up_manifest(manifest,&r,hash);up_same(&r,&source);
    struct partition_root p=pdiscover(disk,part,sys);require(r.size<=p.capacity,"update image exceeds DARKOS capacity");
    up_held(cache,&p,&r,hash);
    struct stat st;int in=regular(image,&st);require((uint64_t)st.st_size==r.size,"update source size changed");
    /* Finish a full source SHA before the first target byte can change. */
    up_hash(in,r.size,r.hash,progress);
    int out=up_output(part,&p,disk,sys);struct sha256_state digest;sha_init(&digest);
    unsigned char data[262144];uint64_t done=0;
    while(done<r.size){size_t n=r.size-done>sizeof(data)?sizeof(data):(size_t)(r.size-done);
        readat(in,data,n,done);sha_update(&digest,data,n);size_t off=0;
        while(off<n){ssize_t wrote=pwrite(out,data+off,n-off,(off_t)(done+off));if(wrote<0 && errno==EINTR)continue;if(wrote<=0)die("DARKOS write");off+=(size_t)wrote;}
        done+=n;rprogress(progress,done);
    }
    char actual[65];sha_finish(&digest,actual);require(!strcmp(actual,r.hash),"update source changed during copy");
    if(fsync(out))die("DARKOS flush");
#ifdef A36_PARTITION_FIXTURE
    if(S_ISBLK(st.st_mode) && ioctl(out,BLKFLSBUF))die("DARKOS invalidate read cache");
#else
    if(ioctl(out,BLKFLSBUF))die("DARKOS invalidate read cache");
#endif
    /* Re-read the flushed target, not the copy buffer or source file. */
    up_hash(p.partfd,r.size,r.hash,progress);psuper(&p,r.size);
    char file[PATH_MAX];up_path(file,cache,p.family,".meta");char *record=precord(&p,&r,hash);ppublish(cache,file,record);
    printf("%ssource_path=%s\nsize=%" PRIu64 "\nverified=1\n",record,part,r.size);
    free(record);free(json);close(in);close(out);up_close(&p);
}
static char *up_verified(const char *manifest,const char *disk,const char *part,const char *sys,
                         const char *cache,struct partition_root *p,struct release *r,char hash[65]) {
    char *json=up_manifest(manifest,r,hash);*p=pdiscover(disk,part,sys);psuper(p,r->size);up_held(cache,p,r,hash);
    char file[PATH_MAX];up_path(file,cache,p->family,".meta");char *record=precord(p,r,hash);
    require(pcached(file,record),"partition copy has no verified cache");free(record);return json;
}
static void up_publish(const char *manifest,const char *disk,const char *part,const char *sys,const char *cache,const char *boot) {
    struct partition_root p;struct release r;char hash[65];char *json=up_verified(manifest,disk,part,sys,cache,&p,&r,hash);
#ifndef A36_PARTITION_FIXTURE
    /* BOOT must be the p1 of this same boot disk, not another FAT mount. */
    char bootdev[PATH_MAX];require(snprintf(bootdev,sizeof(bootdev),"%sp1",disk)<(int)sizeof(bootdev),"update BOOT path");
    struct stat node,mounted;require(!stat(bootdev,&node) && S_ISBLK(node.st_mode) && !stat(boot,&mounted) && mounted.st_dev==node.st_rdev,"update BOOT mount identity");
    struct statfs fs;require(!statfs(boot,&fs) && fs.f_type==MSDOS_SUPER_MAGIC,"update BOOT filesystem");
#endif
    char path[PATH_MAX];require(snprintf(path,sizeof(path),"%s/andr36oid-darkosen.json",boot)<(int)sizeof(path),"update BOOT manifest path");
    ppublish(boot,path,json);free(json);up_close(&p);
}
static void up_activate(const char *manifest,const char *disk,const char *part,const char *sys,const char *cache) {
    struct partition_root p;struct release r;char hash[65];char *json=up_verified(manifest,disk,part,sys,cache,&p,&r,hash);
    char file[PATH_MAX];up_path(file,cache,p.family,".updating");if(unlink(file))die("update guard remove");
    int fd=open(cache,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(fd<0 || fsync(fd))die("update guard directory flush");close(fd);
    printf("source_id=%s\ngeneration=%s\n",p.family,r.generation);free(json);up_close(&p);
}
static void up_target(const char *disk,const char *part,const char *sys) {
    struct partition_root p=pdiscover(disk,part,sys);
    printf("source_id=%s\ndisk_uuid=%s\npart_uuid=%s\nfirst=%" PRIu64 "\ncapacity=%" PRIu64 "\n",p.family,p.diskid,p.partid,p.first,p.capacity);up_close(&p);
}
static void up_recheck(const char *manifest,const char *disk,const char *part,const char *sys,
                        const char *cache,const char *build,const char *progress) {
    struct release r;char hash[65];char *json=up_manifest(manifest,&r,hash);rminimum(r.minimum,build);
    struct partition_root p=pdiscover(disk,part,sys);psuper(&p,r.size);
    char file[PATH_MAX];up_path(file,cache,p.family,".updating");struct stat st;
    if(!lstat(file,&st))up_held(cache,&p,&r,hash);else require(errno==ENOENT,"update recheck guard stat");
    up_hash(p.partfd,r.size,r.hash,progress);
    char *record=precord(&p,&r,hash);up_path(file,cache,p.family,".meta");ppublish(cache,file,record);
    printf("%ssource_path=%s\nsize=%" PRIu64 "\nverified=1\n",record,part,r.size);
    free(record);free(json);up_close(&p);
}
static void up_remove_image(const char *manifest,const char *image,const char *uuid,const char *meta_hash) {
    require(hex(meta_hash,64),"update cleanup record hash");struct release r;char mh[65];char *json=up_manifest(manifest,&r,mh);
    require(!strcmp(strrchr(image,'/')?strrchr(image,'/')+1:image,r.name),"update cleanup source name");
    char meta[PATH_MAX];require(snprintf(meta,sizeof(meta),"%s.meta",image)<(int)sizeof(meta),"update cleanup metadata path");
    struct stat image_st,meta_st,now;int in=-1,record=-1;
    int absent=lstat(image,&image_st);if(absent)require(errno==ENOENT,"update cleanup source stat");
    if(!absent){struct release source=up_release(image,uuid);up_same(&r,&source);in=regular(image,&image_st);up_hash(in,r.size,r.hash,"-");}
    if(lstat(meta,&meta_st)){require(errno==ENOENT && absent,"update cleanup metadata missing before source");goto sync_directory;}
    record=regular(meta,&meta_st);up_hash(record,(uint64_t)meta_st.st_size,meta_hash,"-");
    if(!absent)require(!lstat(image,&now) && now.st_dev==image_st.st_dev && now.st_ino==image_st.st_ino,"update cleanup source changed");
    require(!lstat(meta,&now) && now.st_dev==meta_st.st_dev && now.st_ino==meta_st.st_ino,"update cleanup record changed");
    if((!absent && unlink(image)) || unlink(meta))die("update cleanup unlink");
sync_directory: ;
    char *dir=strdup(image);require(dir!=NULL,"update cleanup directory");char *slash=strrchr(dir,'/');require(slash!=NULL,"update cleanup path");*slash=0;
    int fd=open(dir,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(fd<0 || fsync(fd))die("update cleanup directory flush");close(fd);
    if(in>=0)close(in);
    if(record>=0)close(record);
    free(dir);free(json);
}
static void up_backup_info(const char *image,const char *meta,const char *uuid,const char *name,const char *generation,const char *meta_hash) {
    require(hex(generation,64) && hex(meta_hash,64),"release backup expected identity");struct release r;
    require(release_record_read(image,meta,uuid,NULL,name,&r,0) && !strcmp(r.generation,generation),"release backup generation");
    struct stat image_st,meta_st;int in=regular(image,&image_st),record=regular(meta,&meta_st);
    up_hash(in,r.size,r.hash,"-");up_hash(record,(uint64_t)meta_st.st_size,meta_hash,"-");
    printf("device=%" PRIu64 "\ninode=%" PRIu64 "\nmeta_device=%" PRIu64 "\nmeta_inode=%" PRIu64 "\ngeneration=%s\n",(uint64_t)image_st.st_dev,(uint64_t)image_st.st_ino,(uint64_t)meta_st.st_dev,(uint64_t)meta_st.st_ino,r.generation);
    close(in);close(record);
}
static void up_partial_info(const char *request,const char *job,const char *image) {
    struct release r=rload(request,job);char expected[128];
    require(snprintf(expected,sizeof(expected),".a36-release-%s.partial",job)<(int)sizeof(expected),"partial expected name");
    require(!strcmp(strrchr(image,'/')?strrchr(image,'/')+1:image,expected),"partial transaction filename");
    struct stat st;int fd=regular(image,&st);require((uint64_t)st.st_size==r.size,"partial completed size");up_hash(fd,r.size,r.hash,"-");
    printf("device=%" PRIu64 "\ninode=%" PRIu64 "\n",(uint64_t)st.st_dev,(uint64_t)st.st_ino);close(fd);
}
static void release_wake_hold(const char *job,const char *power,const char *period) {
    require(hex(job,16),"release wake job");uint64_t ms=number(period);require(ms>=1 && ms<=15000,"release wake interval");
    rdeath(getppid());char path[PATH_MAX],value[128];
    require(snprintf(path,sizeof(path),"%s/wake_lock",power)<(int)sizeof(path),"release wake path");
    int length=snprintf(value,sizeof(value),"a36-release-%s 60000000000\n",job);require(length>0 && length<(int)sizeof(value),"release wake value");
    for(;;){
        int fd=open(path,O_WRONLY|O_TRUNC|O_NOFOLLOW|O_CLOEXEC);if(fd<0)die("release wake open");
        struct stat st;require(!fstat(fd,&st) && S_ISREG(st.st_mode),"release wake attribute");rwrite(fd,value,(size_t)length);close(fd);
        struct timespec delay={.tv_sec=(time_t)(ms/1000),.tv_nsec=(long)(ms%1000)*1000000};
        while(nanosleep(&delay,&delay) && errno==EINTR){}
    }
}
static int partition_update_command(int argc,char **argv) {
    const char *cmd=argv[1];
    if(!strcmp(cmd,"partition-plan") && argc==9)up_plan(argv[2],argv[3],argv[4],argv[5],argv[6],argv[7],argv[8]);
    else if(!strcmp(cmd,"partition-hold") && argc==7)up_hold(argv[2],argv[3],argv[4],argv[5],argv[6]);
    else if(!strcmp(cmd,"partition-copy") && argc==10)up_copy(argv[2],argv[3],argv[4],argv[5],argv[6],argv[7],argv[8],argv[9]);
    else if(!strcmp(cmd,"partition-publish") && argc==8)up_publish(argv[2],argv[3],argv[4],argv[5],argv[6],argv[7]);
    else if(!strcmp(cmd,"partition-activate") && argc==7)up_activate(argv[2],argv[3],argv[4],argv[5],argv[6]);
    else if(!strcmp(cmd,"partition-target") && argc==5)up_target(argv[2],argv[3],argv[4]);
    else if(!strcmp(cmd,"partition-recheck") && argc==9)up_recheck(argv[2],argv[3],argv[4],argv[5],argv[6],argv[7],argv[8]);
    else if(!strcmp(cmd,"release-remove-image") && argc==6)up_remove_image(argv[2],argv[3],argv[4],argv[5]);
    else if(!strcmp(cmd,"release-backup-info") && argc==8)up_backup_info(argv[2],argv[3],argv[4],argv[5],argv[6],argv[7]);
    else if(!strcmp(cmd,"release-partial-info") && argc==5)up_partial_info(argv[2],argv[3],argv[4]);
    else if(!strcmp(cmd,"wake-hold") && argc==5)release_wake_hold(argv[2],argv[3],argv[4]);
    else if(!strcmp(cmd,"release-manifest") && argc==6){struct release r=up_release(argv[2],argv[3]);rminimum(r.minimum,argv[4]);up_make_manifest(&r,argv[5]);}
    else return 0;
    return 1;
}
