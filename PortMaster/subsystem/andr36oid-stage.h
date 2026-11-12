#ifndef ANDR36OID_STAGE_H
#define ANDR36OID_STAGE_H
/* File/node hot spots only. Mounts and their ownership journal remain serialized
 * in the broker, with the original record after each successful mount. */
static int stage_path(char out[PATH_MAX],const char *root,const char *tail) {
    if(snprintf(out,PATH_MAX,"%s/%s",root,tail)>=PATH_MAX){errno=ENAMETOOLONG;return -1;}return 0;
}
static int stage_mkdirs(const char *path) {
    char copy[PATH_MAX];
    if(strlen(path)>=sizeof(copy)){errno=ENAMETOOLONG;return -1;}
    strcpy(copy,path);
    for(char *p=copy+1;;p++)if(*p=='/'||!*p) {
        char saved=*p;*p=0;
        if(mkdir(copy,0777)&&errno!=EEXIST)return -1;
        struct stat st;if(stat(copy,&st)||!S_ISDIR(st.st_mode)){errno=ENOTDIR;return -1;}
        *p=saved;if(!saved)break;
    }
    return 0;
}
static int stage_dev_one(const char *source,const char *root,const char *name) {
    char src[PATH_MAX],dst[PATH_MAX],relative[PATH_MAX];struct stat st;
    if(stage_path(src,source,name)||snprintf(relative,sizeof(relative),"dev/%s",name)>=(int)sizeof(relative)||stage_path(dst,root,relative))return -1;
    if(stat(src,&st))return 0; // the shell's -e skipped every failed stat too
    if(S_ISDIR(st.st_mode)) {
        if(stage_mkdirs(dst))return -1;
        struct dirent **entries=NULL;int count=scandir(src,&entries,NULL,alphasort),rc=0;
        if(count<0)return -1;
        for(int i=0;i<count;i++) {
            struct dirent *entry=entries[i];
            if(entry->d_name[0]=='.')continue; // shell * excludes dot entries
            char child[PATH_MAX],path[PATH_MAX];struct stat node;
            if(stage_path(child,name,entry->d_name)||stage_path(path,source,child)){rc=-1;break;}
            // Match add_dev: directories expose their character children only.
            if(!stat(path,&node)&&S_ISCHR(node.st_mode)&&stage_dev_one(source,root,child)){rc=-1;break;}
        }
        for(int i=0;i<count;i++)free(entries[i]);
        free(entries);return rc;
    }
    mode_t mode=S_ISBLK(st.st_mode)?0660:0666;
    if(mknod(dst,(S_ISBLK(st.st_mode)?S_IFBLK:S_IFCHR)|mode,st.st_rdev)||chmod(dst,mode))return -1;
    return 0;
}
static int stage_dev(const char *root,bool handoff) {
    const char *source="/dev";
#ifdef A36_STAGE_FIXTURE
    source=getenv("A36_STAGE_SOURCE_DEV");if(!source)return 2;
#endif
    static const char *base[]={"null","zero","full","random","urandom","tty","ptmx"};
    static const char *hardware[]={"input","snd","dri","mali0","mali","graphics","uinput","tty0","tty1","ion","rga","mpp_service"};
    for(unsigned i=0;i<sizeof(base)/sizeof(*base);i++)if(stage_dev_one(source,root,base[i]))return 1;
    if(handoff) {
        for(unsigned i=0;i<sizeof(hardware)/sizeof(*hardware);i++)if(stage_dev_one(source,root,hardware[i]))return 1;
        char fb[PATH_MAX],alias[PATH_MAX];struct stat st;
        if(stage_path(fb,root,"dev/graphics/fb0")||stage_path(alias,root,"dev/fb0"))return 1;
        if(!stat(fb,&st)){int ignored=symlink("graphics/fb0",alias);(void)ignored;}
    }
    return 0;
}
struct stage_group_row {unsigned gid;size_t member,end;bool has_member;};
static int stage_group_row(const char *line,size_t size,struct stage_group_row *row) {
    size_t colon[4],count=0;
    for(size_t i=0;i<size&&count<4;i++)if(line[i]==':')colon[count++]=i;
    if(count<2)return 0;
    size_t start=colon[1]+1,end=count>2?colon[2]:size,n=end-start;
    if(!n)return 0;
    // Noncanonical numeric syntax delegates to awk, rather than approximating
    // its numeric-string coercion for hand-edited/malformed group files.
    if(n>10||(n>1&&line[start]=='0'))return -1;
    uint64_t gid=0;
    for(size_t i=start;i<end;i++){if(line[i]<'0'||line[i]>'9')return -1;gid=gid*10+(unsigned)(line[i]-'0');}
    if(gid>UINT32_MAX)return -1;
    row->gid=(unsigned)gid;row->has_member=count>2;
    row->member=count>2?colon[2]+1:size;row->end=count>3?colon[3]:size;return 1;
}
static bool stage_has_ark(const char *data,size_t size) {
    size_t start=0;
    for(size_t i=0;i<=size;i++)if(i==size||data[i]==',') {
        if(i-start==3&&!memcmp(data+start,"ark",3))return true;
        start=i+1;
    }
    return false;
}
static int stage_groups(const char *root) {
    char path[PATH_MAX];struct stat st;
    if(stage_path(path,root,"etc/group"))return 1;
    if(stat(path,&st)||!S_ISREG(st.st_mode))return 0;
    char temporary[PATH_MAX];
    if(snprintf(temporary,sizeof(temporary),"%s.a36",path)>=(int)sizeof(temporary))return 2;
    // Preserve the old path's handling of leftovers, symlinks and hard links.
    if(!lstat(temporary,&st))return 2;
    if(stat(path,&st))return 1;
    if(st.st_size<0||st.st_size>1048576)return 2;
    FILE *file=fopen(path,"r");if(!file)return 1;
    size_t size=(size_t)st.st_size;char *data=malloc(size+1);if(!data){fclose(file);return 1;}
    if(fread(data,1,size,file)!=size){free(data);fclose(file);return 1;}
    fclose(file);data[size]=0;
    if(memchr(data,0,size)){free(data);return 2;}
    const unsigned gids[]={1023,1015,1004,1005,3003,3004,9997};int rc=0;
    for(unsigned g=0;g<sizeof(gids)/sizeof(*gids);g++) {
        bool exists=false;
        for(size_t pos=0;pos<size;) {
            const char *nl=memchr(data+pos,'\n',size-pos);size_t end=nl?(size_t)(nl-data):size;
            struct stage_group_row row;int parsed=stage_group_row(data+pos,end-pos,&row);
            if(parsed<0){rc=2;goto done;}if(parsed&&row.gid==gids[g])exists=true;
            pos=end+(nl!=NULL);
        }
        char *next=NULL;size_t length=0;FILE *out=open_memstream(&next,&length);
        if(!out){rc=1;goto done;}
        if(!exists) {
            fwrite(data,1,size,out);fprintf(out,"a36hw%u:x:%u:ark\n",gids[g],gids[g]);
        } else for(size_t pos=0;pos<size;) {
            const char *nl=memchr(data+pos,'\n',size-pos);size_t end=nl?(size_t)(nl-data):size;
            const char *line=data+pos;struct stage_group_row row;
            int parsed=stage_group_row(line,end-pos,&row);
            if(parsed&&row.gid==gids[g]&&!stage_has_ark(line+row.member,row.end-row.member)) {
                fwrite(line,1,row.member,out);
                if(row.has_member)fwrite(line+row.member,1,row.end-row.member,out);
                if(row.has_member&&row.end>row.member)fputc(',',out);
                if(!row.has_member)fputc(':',out);
                fputs("ark",out);fwrite(line+row.end,1,end-pos-row.end,out);
            } else fwrite(line,1,end-pos,out);
            fputc('\n',out);pos=end+(nl!=NULL);
        }
        bool bad=ferror(out);if(fclose(out))bad=true;
        if(bad){free(next);rc=1;goto done;}
        free(data);data=next;size=length;
        if(size>1114112){rc=2;goto done;}
    }
    // Write through the original inode: preserve mode, ownership and hard links,
    // just as the old awk-temp + cat > group path did. No mount journal here.
    file=fopen(path,"w");if(!file){rc=1;goto done;}
    bool bad=fwrite(data,1,size,file)!=size;if(fclose(file))bad=true;
    if(bad)rc=1;
done:
    free(data);return rc;
}
static int stage_network_masks(const char *root) {
    const char *dirs[]={"run/systemd/system","etc/systemd/system"};
    const char *units[]={"NetworkManager","NetworkManager-wait-online","systemd-networkd","systemd-networkd-wait-online","systemd-resolved","networking","dhcpcd","dhcpcd@","wpa_supplicant","wpa_supplicant@","iwd","connman"};
    int rc=0;
    // ln's directory-target behavior differs between implementations. Preserve
    // the original command for these non-unit-file cases, before any mutation.
    for(unsigned u=0;u<sizeof(units)/sizeof(*units);u++)for(unsigned d=0;d<2;d++) {
        char path[PATH_MAX];struct stat st;
        if(snprintf(path,sizeof(path),"%s/%s/%s.service",root,dirs[d],units[u])>=(int)sizeof(path))return 2;
        if(!stat(path,&st)&&S_ISDIR(st.st_mode))return 2;
    }
    for(unsigned u=0;u<sizeof(units)/sizeof(*units);u++)for(unsigned d=0;d<2;d++) {
        char path[PATH_MAX];struct stat st;
        if(snprintf(path,sizeof(path),"%s/%s/%s.service",root,dirs[d],units[u])>=(int)sizeof(path)){rc=1;continue;}
        if(!stat(path,&st)&&S_ISDIR(st.st_mode)) {
            size_t n=strlen(path);if(n+5>=sizeof(path)){rc=1;continue;}strcpy(path+n,"/null");
        }
        if(unlink(path)&&errno!=ENOENT){rc=1;continue;}
        if(symlink("/dev/null",path))rc=1;
    }
    return rc;
}
#endif
