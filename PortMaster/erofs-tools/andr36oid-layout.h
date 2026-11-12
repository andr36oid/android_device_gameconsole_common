/* Read-only official GPT/slot audit used before firstresize can format anything.
 * A raw-written image may still have its backup header at the image's old end;
 * unlike the prepared DARKOS reader, this accepts that legitimate first boot. */
static int layout_name(const unsigned char *e,const char *name) {
    size_t n=strlen(name);if(n>=36)return 0;
    for(size_t i=0;i<=n;++i)if(e[56+i*2]!=(i<n?(unsigned char)name[i]:0) || e[57+i*2])return 0;
    return 1;
}
static void layout_audit(const char *disk,const char *sys,int created) {
    uint64_t capacity;int fd=popen_block(disk,sys,&capacity);unsigned char h[512],entries[16384];readat(fd,h,512,512);
    require(!memcmp(h,"EFI PART",8) && le32(h+12)>=92 && le32(h+12)<=512,"layout GPT header");
    uint32_t crc=le32(h+16);memset(h+16,0,4);require(pcrc(h,le32(h+12))==crc,"layout GPT header checksum");
    uint64_t last=le64(h+48);require(capacity%512==0 && le64(h+24)==1 && le64(h+40)>=34 && last>=le64(h+40) && last<=capacity/512-34 && le64(h+32)<=capacity/512-1 && le64(h+32)>last,"layout GPT bounds");
    require(le32(h+80)==128 && le32(h+84)==128 && le64(h+72)>=2 && le64(h+72)<=le64(h+40)-32,"layout GPT entry geometry");
    readat(fd,entries,sizeof(entries),le64(h+72)*512);require(pcrc(entries,sizeof(entries))==le32(h+88),"layout GPT table checksum");close(fd);
    static const char *names[]={"BOOT","system","idbloader","uboot","trust","userdata","EASYROMS","vendor","metadata","misc","cache","AUTOAPK","AUTOAPK","DARKOS"};
    static const uint64_t fixed[][2]={{32768,557055},{1081344,5701008},{64,16383},{16384,24575},{24576,32767},{0,0},{0,0},{557056,884735},{884736,917503},{917504,925695},{925696,1081343}};
    static const unsigned char linux_type[16]={0xaf,0x3d,0xc6,0x0f,0x83,0x84,0x72,0x47,0x8e,0x79,0x3d,0x69,0xd8,0x47,0x7d,0xe4};
    static const unsigned char basic_type[16]={0xa2,0xa0,0xd0,0xeb,0xe5,0xb9,0x33,0x44,0x87,0xc0,0x68,0xb6,0xb7,0x26,0x99,0xc7};
    unsigned present[128]={0};uint64_t firsts[128]={0},lasts[128]={0};const char *base=strrchr(disk,'/');require(base!=NULL,"layout disk basename");base++;
    for(unsigned i=0;i<128;++i){
        const unsigned char *e=entries+i*128;for(unsigned k=0;k<16;++k)present[i]|=e[k];
        if(!present[i])continue;
        if(i>=14 || !layout_name(e,names[i])){
            char got[37];unsigned k;for(k=0;k<36 && (e[56+k*2] || e[57+k*2]);++k)got[k]=(e[57+k*2] || e[56+k*2]<32 || e[56+k*2]>126)?'?':e[56+k*2];got[k]=0;
            fprintf(stderr,"GPT p%u expected %s, got %s\n",i+1,i<14?names[i]:"no partition",got);require(0,"partition table changed/renumbered");
        }
        const unsigned char *type=(i==0 || i==6 || i==11 || i==12)?basic_type:linux_type;
        require(!memcmp(e,type,16),"partition GPT type changed");
        uint64_t first=le64(e+32),end=le64(e+40);firsts[i]=first;lasts[i]=end;
        require(first>=le64(h+40) && first<=end && end<=last,"partition range invalid");
        if(i<11 && fixed[i][0])require(first==fixed[i][0] && end==fixed[i][1],"frozen partition geometry changed");
        if(i==5 || i==11)require(first==5701632 || first==5701009,"userdata/AUTOAPK start changed");
        if(i==6 || i==12 || i==13)require(first>5701632,"writable/guest partition overlaps system");
        for(unsigned j=0;j<i;++j)if(present[j])require(end<firsts[j] || first>lasts[j],"overlapping partitions");
        char path[PATH_MAX],value[512],node[96];require(snprintf(node,sizeof(node),"%sp%u",base,i+1)<(int)sizeof(node),"layout node");
        require(snprintf(path,sizeof(path),"%s/%s/start",sys,node)<(int)sizeof(path),"layout sysfs path");ptext(path,value,sizeof(value));require(number(value)==first,"GPT/kernel start mismatch");
        require(snprintf(path,sizeof(path),"%s/%s/size",sys,node)<(int)sizeof(path),"layout sysfs path");ptext(path,value,sizeof(value));require(number(value)==end-first+1,"GPT/kernel size mismatch");
        require(snprintf(path,sizeof(path),"%s/%s/uevent",sys,node)<(int)sizeof(path),"layout sysfs path");ptext(path,value,sizeof(value));
        char *label=strstr(value,"PARTNAME=");if(label && label!=value && label[-1]!='\n')label=NULL;
        if(label){label+=9;char *newline=strchr(label,'\n');if(newline)*newline=0;require(!strcmp(label,names[i]),"kernel PARTNAME mismatch");}
        else require(created,"kernel partition has no PARTNAME");
    }
    for(unsigned i=0;i<11;++i)if(i<5 || i>=7)require(present[i],"missing frozen partition");
    require(!present[6] || present[5],"EASYROMS exists without userdata");
    // A stale kernel node must not impersonate a GPT hole such as p6.
    for(unsigned i=0;i<14;++i)if(!present[i]){
        char path[PATH_MAX];require(snprintf(path,sizeof(path),"%s/%sp%u/partition",sys,base,i+1)<(int)sizeof(path),"layout absent node");
        struct stat st;require(lstat(path,&st)<0 && errno==ENOENT,"kernel has a partition absent from GPT");
    }
    puts("layout=official\nlabels=verified\ntypes=verified");
}
static int layout_command(int argc,char **argv) {
    if(!strcmp(argv[1],"layout-audit") && (argc==4 || (argc==5 && !strcmp(argv[4],"created")))){layout_audit(argv[2],argv[3],argc==5);return 1;}
    return 0;
}
