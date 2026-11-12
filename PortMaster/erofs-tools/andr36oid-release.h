/* Release request parser and streaming verifier. Included by erofs-support.c.
 * Requests are data, never commands. All paths below are basenames relative to
 * the caller's canonical card directory. The broker pins this file in /data. */
#define REL_PARTS 64
#define REL_JSON 65536
struct rtoken { int type, parent, start, end; };
struct rjson { char text[REL_JSON+1]; struct rtoken t[4096]; int count, at, length; };
struct rpart { char name[NAME_MAX+1], hash[65]; uint64_t size; };
struct release {
    char job[17], darkosen[33], minimum[128], name[NAME_MAX+1], hash[65], compressed_hash[65], generation[65];
    uint64_t revision, size, compressed; unsigned count;
    struct rpart parts[REL_PARTS];
};
static void rskip(struct rjson *j) { while (j->at<j->length && strchr(" \t\r\n",j->text[j->at])) j->at++; }
static int rvalue(struct rjson *j,int parent,int depth) {
    require(depth<12 && j->count<4096,"request nesting/tokens"); rskip(j);require(j->at<j->length,"truncated request value");
    int n=j->count++; struct rtoken *t=&j->t[n]; t->parent=parent;t->start=j->at;
    char c=j->text[j->at++];
    if (c=='{' || c=='[') {
        t->type=c; rskip(j); char close=c=='{'?'}':']';
        if (j->text[j->at]!=close) for (;;) {
            if (c=='{') { require(j->text[j->at]=='"',"request object key"); rvalue(j,n,depth+1);rskip(j);require(j->text[j->at++]==':',"request colon"); }
            rvalue(j,n,depth+1); rskip(j);
            if (j->text[j->at]!=',') break;
            j->at++;rskip(j);
        }
        require(j->text[j->at++]==close,"request closing delimiter");
    } else if (c=='"') {
        t->type='s'; t->start=j->at;
        /* Operational fields are ASCII; escaped paths/NUL/control data are
         * rejected rather than interpreted differently by C, shell and Java. */
        while (j->at<j->length && j->text[j->at]!='"') {
            unsigned char b=j->text[j->at++];require(b>=32 && b<127 && b!='\\',"request string encoding");
        }
        t->end=j->at; require(j->text[j->at++]=='"',"request string end");return n;
    } else {
        t->type='n'; require(c>='0' && c<='9',"request unsigned integer");
        while (j->text[j->at]>='0' && j->text[j->at]<='9') j->at++;
        require(j->at-t->start==1 || c!='0',"request leading zero");
    }
    require(j->at<=j->length,"truncated request");t->end=j->at;return n;
}
static void rstring(struct rjson *j,int n,char *out,size_t size,int type) {
    require(n>=0 && j->t[n].type==type,"request field type");
    int len=j->t[n].end-j->t[n].start;require(len>=0 && (size_t)len<size,"request field length");
    memcpy(out,j->text+j->t[n].start,(size_t)len);out[len]=0;
}
static int rfield(struct rjson *j,int obj,const char *name) {
    int found=-1;
    for (int i=obj+1;i<j->count;++i) if (j->t[i].parent==obj) {
        char key[64];rstring(j,i,key,sizeof(key),'s');int value=i+1;
        require(value<j->count && j->t[value].parent==obj,"request key/value");
        if (!strcmp(key,name)) {require(found<0,"duplicate request field");found=value;}
        i=value;while (i+1<j->count && j->t[i+1].parent!=obj) i++;
    }
    require(found>=0,"missing request field");return found;
}
static void rkeys(struct rjson *j,int obj,const char *allowed) {
    require(j->t[obj].type=='{',"request object");
    for (int i=obj+1;i<j->count;++i) if (j->t[i].parent==obj) {
        char key[64],needle[68];rstring(j,i,key,sizeof(key),'s');snprintf(needle,sizeof(needle),"|%s|",key);
        require(strstr(allowed,needle)!=NULL,"unknown request field"); (void)rfield(j,obj,key);
        i++;while (i+1<j->count && j->t[i+1].parent!=obj) i++;
    }
}
static uint64_t rnumber(struct rjson *j,int obj,const char *name) {
    char value[32];rstring(j,rfield(j,obj,name),value,sizeof(value),'n');return number(value);
}
static void rget(struct rjson *j,int obj,const char *name,char *out,size_t size) {rstring(j,rfield(j,obj,name),out,size,'s');}
static int rname(const char *name) {
    size_t n=strlen(name);return n && n<=NAME_MAX && name[0]!='.' && strspn(name,"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-+")==n;
}
static void rgeneration(struct release *r) {
    char stamp[256];int n=snprintf(stamp,sizeof(stamp),"a36-release-v1:%s:%" PRIu64 ":%s",r->darkosen,r->revision,r->hash);
    require(n>0 && n<(int)sizeof(stamp),"release generation");sha(stamp,(size_t)n,r->generation);
}
static struct release rload_kind(const char *path,const char *job,int partition) {
    struct rjson *j=calloc(1,sizeof(*j));if(!j)die("request allocation");
    struct stat st;int fd=regular(path,&st);require(st.st_size>0 && st.st_size<=REL_JSON,"request size");
    readat(fd,j->text,(size_t)st.st_size,0);close(fd);j->length=(int)st.st_size;
    require(!memchr(j->text,0,(size_t)j->length),"request NUL");
    rvalue(j,-1,0);rskip(j);require(j->at==j->length,"trailing request data");
    rkeys(j,0,partition?"|format||darkosen||revision||minimum_andr36oid||image|":"|format||job||darkosen||revision||minimum_andr36oid||image||download|");
    require(rnumber(j,0,"format")==1,"request format");struct release r={0};
    if(!partition){rget(j,0,"job",r.job,sizeof(r.job));require(hex(r.job,16) && !strcmp(r.job,job),"request job");}
    rget(j,0,"darkosen",r.darkosen,sizeof(r.darkosen));require(strlen(r.darkosen)==8 && strspn(r.darkosen,"0123456789")==8,"release version");
    r.revision=rnumber(j,0,"revision");require(r.revision>0 && r.revision<=UINT32_MAX,"release revision");
    rget(j,0,"minimum_andr36oid",r.minimum,sizeof(r.minimum));
    int image=rfield(j,0,"image");rkeys(j,image,"|name||size||sha256|");
    rget(j,image,"name",r.name,sizeof(r.name));require(rname(r.name) && basename_ok(r.name) && strlen(r.name)<=78,"release image name");
    r.size=rnumber(j,image,"size");require(r.size>=4096 && r.size<=INT64_MAX,"release image size");
    rget(j,image,"sha256",r.hash,sizeof(r.hash));require(hex(r.hash,64),"release image hash");
    if(partition){rgeneration(&r);free(j);return r;}
    int dl=rfield(j,0,"download");rkeys(j,dl,"|compression||size||sha256||parts|");
    char compression[8];rget(j,dl,"compression",compression,sizeof(compression));require(!strcmp(compression,"xz"),"release compression");
    r.compressed=rnumber(j,dl,"size");require(r.compressed>0 && r.compressed<=INT64_MAX,"release compressed size");
    rget(j,dl,"sha256",r.compressed_hash,sizeof(r.compressed_hash));require(hex(r.compressed_hash,64),"release compressed hash");
    int parts=rfield(j,dl,"parts");require(j->t[parts].type=='[',"release parts array");uint64_t total=0;
    for(int i=parts+1;i<j->count;++i)if(j->t[i].parent==parts){
        require(r.count<REL_PARTS,"release part count");struct rpart *p=&r.parts[r.count++];
        rkeys(j,i,"|name||size||sha256|");rget(j,i,"name",p->name,sizeof(p->name));require(rname(p->name) && strcmp(p->name,r.name),"release part name");
        p->size=rnumber(j,i,"size");require(p->size>0 && p->size<=INT64_MAX,"release part size");
        rget(j,i,"sha256",p->hash,sizeof(p->hash));require(hex(p->hash,64),"release part hash");
        total=add(total,p->size);for(unsigned k=0;k+1<r.count;++k)require(strcmp(p->name,r.parts[k].name),"duplicate release part");
    }
    require(r.count && total==r.compressed,"release compressed total");rgeneration(&r);free(j);return r;
}
static struct release rload(const char *path,const char *job) {return rload_kind(path,job,0);}
static void rpath(char path[PATH_MAX],const char *dir,const char *name) {
    require(rname(name) && snprintf(path,PATH_MAX,"%s/%s",dir,name)<PATH_MAX,"release path");
}
static void rwrite(int fd,const void *data,size_t len) {
    const unsigned char *p=data;while(len){ssize_t n=write(fd,p,len);if(n<0 && errno==EINTR)continue;if(n<=0)die("release write");p+=n;len-=(size_t)n;}
}
static void rprogress(const char *path,uint64_t done) {
    if(!strcmp(path,"-"))return;
    int fd=open(path,O_WRONLY|O_TRUNC|O_NOFOLLOW|O_CLOEXEC);if(fd<0)die("release progress");
    char b[32];int n=snprintf(b,sizeof(b),"%" PRIu64 "\n",done);rwrite(fd,b,(size_t)n);close(fd);
}
static void rhashfd(int fd,uint64_t size,const char *hash,struct sha256_state *all,int output,const char *progress,uint64_t *done) {
    struct sha256_state state;sha_init(&state);unsigned char b[262144];uint64_t left=size;
    while(left){size_t want=left>sizeof(b)?sizeof(b):(size_t)left;ssize_t n=read(fd,b,want);if(n<0 && errno==EINTR)continue;require(n>0,"release short part");
        sha_update(&state,b,(size_t)n);if(all)sha_update(all,b,(size_t)n);if(output)rwrite(STDOUT_FILENO,b,(size_t)n);
        left-=(uint64_t)n;*done+=(uint64_t)n;rprogress(progress,*done);
    }
    require(read(fd,b,1)==0,"release part grew");char out[65];sha_finish(&state,out);require(!strcmp(hash,out),"release checksum");
}
static void rminimum(const char *minimum,const char *current) {
    if(!*minimum)return;
    /* Accepted minimum is an incremental eng.NAME.YYYYMMDD.HHMMSS, its full
     * release|incremental identity, or YYYYMMDD[.HHMMSS]. Compare timestamps. */
    char a[15]={0},b[15]={0};const char *values[]={minimum,current};char *out[]={a,b};
    for(unsigned i=0;i<2;++i){
        const char *found=NULL;size_t n=strlen(values[i]);
        for(size_t k=0;k+8<=n;++k)if(strspn(values[i]+k,"0123456789")>=8 && (k==0 || values[i][k-1]=='.')){found=values[i]+k;break;}
        require(found!=NULL,"release minimum build syntax");memcpy(out[i],found,8);
        if(found[8]=='.') {require(strlen(found+9)==6 && strspn(found+9,"0123456789")==6,"release minimum build syntax");memcpy(out[i]+8,found+9,6);}
        else {require(!found[8],"release minimum build syntax");memcpy(out[i]+8,"000000",6);}
    }
    require(strcmp(b,a)>=0,"release requires newer Android");
}
static void rinfo(struct release *r,const char *dir,const char *uuid,const char *build) {
    char canonical[PATH_MAX];require(realpath(dir,canonical) && !strcmp(dir,canonical),"canonical release directory");rminimum(r->minimum,build);
    char id[65];selection(uuid,r->name,id);uint64_t limit=space(dir,1),head=add(r->size,99)/100;
    if(head<67108864)head=67108864;
    head=round_up(head,cluster);
    uint64_t required=add(round_up(r->size,cluster),head);
    printf("name=%s\nsize=%" PRIu64 "\ncompressed=%" PRIu64 "\nsource_id=%s\ngeneration=%s\nrequired=%" PRIu64 "\nheadroom=%" PRIu64 "\n",r->name,r->size,r->compressed,id,r->generation,required,head);
    fflush(stdout);require(r->size<=limit,"release file-size limit");
    struct statvfs v;if(statvfs(dir,&v))die("release space");require(v.f_bavail*cluster>=required,"release free space");
}
static void rparts(struct release *r,const char *dir,int stream,const char *progress) {
    int fds[REL_PARTS];uint64_t done=0;struct sha256_state all;sha_init(&all);
    for(unsigned i=0;i<r->count;++i){char path[PATH_MAX];rpath(path,dir,r->parts[i].name);struct stat st;fds[i]=regular(path,&st);
        require((uint64_t)st.st_size==r->parts[i].size,"release part size changed");
        /* Verify ALL parts before emitting even one compressed byte. */
        rhashfd(fds[i],r->parts[i].size,r->parts[i].hash,&all,0,progress,&done);
    }
    char out[65];sha_finish(&all,out);require(!strcmp(out,r->compressed_hash),"release aggregate checksum");
    if(stream){sha_init(&all);done=0;for(unsigned i=0;i<r->count;++i){if(lseek(fds[i],0,SEEK_SET)<0)die("release rewind");rhashfd(fds[i],r->parts[i].size,r->parts[i].hash,&all,1,"-",&done);}sha_finish(&all,out);require(!strcmp(out,r->compressed_hash),"release stream changed");}
    for(unsigned i=0;i<r->count;++i)close(fds[i]);
}
static void rreceive(struct release *r,const char *path,const char *dev,const char *ino,const char *progress) {
    identity(path,dev,ino,0);int fd=open(path,O_WRONLY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)die("release partial");
    struct stat st;if(fstat(fd,&st))die("release partial stat");require((uint64_t)st.st_dev==number(dev) && (uint64_t)st.st_ino==number(ino),"release partial changed");if(ftruncate(fd,0))die("release partial truncate");
    uint64_t done=0;unsigned char b[262144];struct sha256_state hash;sha_init(&hash);
    for(;;){ssize_t n=read(STDIN_FILENO,b,sizeof(b));if(n<0 && errno==EINTR)continue;if(n<0)die("release input");if(!n)break;
        require((uint64_t)n<=r->size-done,"release output exceeds manifest");rwrite(fd,b,(size_t)n);sha_update(&hash,b,(size_t)n);done+=(uint64_t)n;rprogress(progress,done);
    }
    char out[65];sha_finish(&hash,out);require(done==r->size && !strcmp(out,r->hash),"release output checksum/size");if(fsync(fd))die("release fsync");close(fd);
    require(probe(path).erofs,"release output format");
}
static void rmakerecord(struct release *r,const char *uuid,const char *path) {
    struct image x=probe(path);require(x.erofs && (uint64_t)x.st.st_size==r->size,"release record output");
    char id[65],namehex[NAME_MAX*2+1];selection(uuid,r->name,id);tohex((unsigned char *)r->name,strlen(r->name),namehex);
    printf("version=2\norigin=release\ntransport=file\njob=%s\nsource_name_hex=%s\nsource_id=%s\ngeneration=%s\ncard_uuid=%s\ndarkosen=%s\nrevision=%" PRIu64 "\nimage_sha256=%s\noutput_size=%" PRIu64 "\noutput_uuid=%s\nminimum_andr36oid=%s\n",r->job,namehex,id,r->generation,uuid,r->darkosen,r->revision,r->hash,r->size,x.uuid,r->minimum);
}
static int release_record_read(const char *path,const char *meta,const char *uuid,const char *orphan,
                               const char *expected_name,struct release *out,int emit) {
    struct stat st;int fd=regular(meta,&st);char text[4096]={0};require(st.st_size>0 && st.st_size<(off_t)sizeof(text),"release record size");readat(fd,text,(size_t)st.st_size,0);close(fd);
    if(strncmp(text,"version=2\n",10))return 0;
    require(!orphan,"release records require release recovery");
    const char *keys[]={"version","origin","transport","job","source_name_hex","source_id","generation","card_uuid","darkosen","revision","image_sha256","output_size","output_uuid","minimum_andr36oid"};
    char values[14][768]={{0}};unsigned mask=0;char *p=text;
    while(*p){char *end=strchr(p,'\n');require(end!=NULL,"release record line");*end=0;char *eq=strchr(p,'=');require(eq!=NULL,"release record field");*eq++=0;
        unsigned i;for(i=0;i<14;++i)if(!strcmp(p,keys[i]))break;require(i<14 && !(mask&(1U<<i)) && strlen(eq)<sizeof(values[0]),"release record key");strcpy(values[i],eq);mask|=1U<<i;p=end+1;}
    require(mask==16383 && !strcmp(values[1],"release") && !strcmp(values[2],"file") && hex(values[3],16),"release record identity");
    size_t n=strlen(values[4]);require(n && n<=NAME_MAX*2 && !(n&1) && hex(values[4],n),"release record filename");char name[NAME_MAX+1];
    for(size_t i=0;i<n/2;++i){unsigned c;require(sscanf(values[4]+i*2,"%2x",&c)==1 && c,"release filename NUL");name[i]=(char)c;}name[n/2]=0;
    const char *expected=expected_name?expected_name:(strrchr(path,'/')?strrchr(path,'/')+1:path);
    require(rname(name) && basename_ok(name) && !strcmp(expected,name),"release record filename mismatch");
    char id[65];selection(uuid,name,id);require(!strcmp(id,values[5]) && !strcmp(uuid,values[7]),"release record card");
    struct release r={0};require(strlen(values[8])==8 && strspn(values[8],"0123456789")==8 && hex(values[10],64),"release record version/hash");
    strcpy(r.darkosen,values[8]);r.revision=number(values[9]);require(r.revision && r.revision<=UINT32_MAX,"release record revision");strcpy(r.hash,values[10]);rgeneration(&r);require(!strcmp(r.generation,values[6]),"release record generation");
    struct image x=probe(path);require(x.erofs && number(values[11])==(uint64_t)x.st.st_size && !strcmp(values[12],x.uuid),"release record output identity");
    require(strlen(values[13])<sizeof(r.minimum),"release minimum length");
    strcpy(r.name,name);strcpy(r.job,values[3]);strcpy(r.minimum,values[13]);r.size=(uint64_t)x.st.st_size;
    if(out)*out=r;
    if(emit){
        for(unsigned i=0;i<14;++i)printf("%s=%s\n",keys[i],values[i]);
        printf("source_name=%s\nsource_size=0\noffset=0\nsectors=0\n",name);
    }
    return 1;
}
static int release_record_if(const char *path,const char *uuid,const char *orphan) {
    char meta[PATH_MAX];require(snprintf(meta,sizeof(meta),"%s.meta",path)<(int)sizeof(meta),"release record path");
    return release_record_read(path,meta,uuid,orphan,NULL,NULL,1);
}
static void rdeath(pid_t parent) {
    require(parent>1 && !prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent,"release parent death");
}
static void runpack(struct release *r,const char *dir,const char *path,const char *dev,const char *ino,const char *verified,const char *written,const char *unxz) {
    int input[2],output[2];if(pipe2(input,O_CLOEXEC) || pipe2(output,O_CLOEXEC))die("release pipes");
    pid_t parent=getpid(),producer=fork();if(producer<0)die("release producer");
    if(!producer){rdeath(parent);if(dup2(input[1],STDOUT_FILENO)<0)die("release stdout");close(input[0]);close(input[1]);close(output[0]);close(output[1]);rparts(r,dir,1,verified);_exit(0);}
    pid_t decoder=fork();if(decoder<0)die("release decoder");
    if(!decoder){rdeath(parent);if(dup2(input[0],STDIN_FILENO)<0 || dup2(output[1],STDOUT_FILENO)<0)die("release decoder pipes");close(input[0]);close(input[1]);close(output[0]);close(output[1]);execl(unxz,unxz,"-c","-T1","--memlimit-decompress=128MiB",(char *)NULL);die("release unxz");}
    close(input[0]);close(input[1]);close(output[1]);if(dup2(output[0],STDIN_FILENO)<0)die("release receive pipe");close(output[0]);
    rreceive(r,path,dev,ino,written);
    int a,b;require(waitpid(producer,&a,0)==producer && waitpid(decoder,&b,0)==decoder && WIFEXITED(a) && !WEXITSTATUS(a) && WIFEXITED(b) && !WEXITSTATUS(b),"release stream/decompress failed");
}
static int release_command(int argc,char **argv) {
    const char *cmd=argv[1];
    if(!strcmp(cmd,"release-pin") && argc==5){
        (void)rload(argv[2],argv[3]);struct stat st;int in=regular(argv[2],&st);
        require(st.st_size>0 && st.st_size<=REL_JSON,"request snapshot size");char data[REL_JSON];readat(in,data,(size_t)st.st_size,0);close(in);
        int out=open(argv[4],O_WRONLY|O_CREAT|O_NOFOLLOW|O_CLOEXEC,0600);if(out<0)die("request snapshot");
        struct stat dst;if(fstat(out,&dst))die("request snapshot stat");require(S_ISREG(dst.st_mode) && dst.st_uid==geteuid(),"private request snapshot");
        if(ftruncate(out,0))die("request snapshot truncate");
        rwrite(out,data,(size_t)st.st_size);if(fsync(out))die("request snapshot fsync");close(out);
        (void)rload(argv[4],argv[3]);
    }
    else if(!strcmp(cmd,"release-check") && argc==4){struct release r=rload(argv[2],argv[3]);printf("name=%s\ngeneration=%s\n",r.name,r.generation);}
    else if(!strcmp(cmd,"release-info") && argc==7){struct release r=rload(argv[2],argv[3]);rinfo(&r,argv[4],argv[5],argv[6]);}
    else if((!strcmp(cmd,"release-verify") || !strcmp(cmd,"release-stream")) && argc==6){struct release r=rload(argv[2],argv[3]);rparts(&r,argv[4],!strcmp(cmd,"release-stream"),argv[5]);}
    else if(!strcmp(cmd,"release-receive") && argc==8){struct release r=rload(argv[2],argv[3]);rreceive(&r,argv[4],argv[5],argv[6],argv[7]);}
    else if(!strcmp(cmd,"release-unpack") && argc==11){struct release r=rload(argv[2],argv[3]);runpack(&r,argv[4],argv[5],argv[6],argv[7],argv[8],argv[9],argv[10]);}
    else if(!strcmp(cmd,"release-record") && argc==6){struct release r=rload(argv[2],argv[3]);rmakerecord(&r,argv[4],argv[5]);}
    else if(!strcmp(cmd,"release-clean") && argc==5){struct release r=rload(argv[2],argv[3]);
        for(unsigned i=0;i<r.count;++i){char path[PATH_MAX];rpath(path,argv[4],r.parts[i].name);struct stat st;
            if(lstat(path,&st) && errno==ENOENT)continue;
            int fd=regular(path,&st);require((uint64_t)st.st_size==r.parts[i].size,"cleanup part size changed");uint64_t done=0;rhashfd(fd,r.parts[i].size,r.parts[i].hash,NULL,0,"-",&done);
            struct stat now;if(lstat(path,&now))die("cleanup part stat");require(now.st_dev==st.st_dev && now.st_ino==st.st_ino,"cleanup part changed");close(fd);if(unlink(path))die("cleanup part unlink");
        }
        int fd=open(argv[4],O_RDONLY|O_DIRECTORY|O_CLOEXEC);if(fd<0 || fsync(fd))die("cleanup directory sync");close(fd);
    }else return 0;
    return 1;
}
