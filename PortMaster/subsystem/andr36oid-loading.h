/* SPDX-License-Identifier: MIT
 * Exact RGB palettes + individually seekable LZ4 sheets. The unchanged legacy
 * logo is the first frame, independent of optional pack metadata and payloads.
 */
#include <lz4.h>
#include <sys/file.h>
#include <sys/random.h>
#define LOADING_PREFIX (640U*480U*4U)
struct loading_frame {unsigned char *pixels;unsigned width,height,sheet;bool native;};
static uint32_t loading_u32(const unsigned char *p) {return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24;}
static uint64_t loading_u64(const unsigned char *p) {return loading_u32(p)|(uint64_t)loading_u32(p+4)<<32;}
static int loading_read(int fd,void *buffer,size_t length,uint64_t offset) {
    size_t n=0;
    while(n<length) {ssize_t got=pread(fd,(char *)buffer+n,length-n,(off_t)(offset+n));
        if(got<0&&errno==EINTR)continue;
        if(got<=0)return -1;
        n+=(size_t)got;
    }
    return 0;
}
static bool loading_range(uint64_t off,uint64_t len,uint64_t total,uint64_t minimum) {return off>=minimum&&off<=total&&len<=total-off;}
static uint64_t loading_diff(uint64_t a,uint64_t b) {return a>b?a-b:b-a;}
static int loading_state(uint32_t *last,uint64_t *used) {
    const char *path=getenv("A36_LOADING_STATE");if(!path||!*path)path="/dev/.andr36oid-loading-last";
    int fd=open(path,O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0600);if(fd<0)return -1;
    struct stat st;
    if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_nlink!=1||flock(fd,LOCK_EX|LOCK_NB)) {close(fd);return -1;}
    unsigned char value[16];*last=0;*used=0;
    if(pread(fd,value,16,0)==16&&!memcmp(value,"A36R",4)) {*last=loading_u32(value+4);*used=loading_u64(value+8);}
    return fd;
}
static int loading_pack(int fd,unsigned target_w,unsigned target_h,struct loading_frame *out) {
    unsigned char head[32];struct stat st;int result=-1,state=-1;
    unsigned char *meta=NULL,*compressed=NULL,*decoded=NULL,*palette=NULL,*pixels=NULL;
    if(loading_read(fd,head,sizeof(head),LOADING_PREFIX)||memcmp(head,"A36LOAD1",8)||loading_u32(head+8)!=1)return -1;
    unsigned groups=loading_u32(head+12),frames=loading_u32(head+16);
    uint64_t total=loading_u64(head+24),metadata=32U+32U*(uint64_t)(groups+frames),minimum=LOADING_PREFIX+metadata;
    if(!groups||groups>16||!frames||frames>1024||loading_u32(head+20)||fstat(fd,&st)||
       st.st_size<0||(uint64_t)st.st_size!=total||minimum>total||total>512U*1024*1024)return -1;
    meta=malloc((size_t)metadata);if(!meta||loading_read(fd,meta,(size_t)metadata,LOADING_PREFIX))goto done;
    unsigned group=UINT_MAX;uint64_t best_aspect=UINT64_MAX,best_area=UINT64_MAX;
    for(unsigned i=0;i<groups;i++) {
        const unsigned char *g=meta+32+32*i;unsigned w=loading_u32(g),h=loading_u32(g+4);
        if(!w||!h||w>8192||h>8192||(uint64_t)w*h>16U*1024*1024)continue;
        uint64_t a=(uint64_t)w*target_h,b=(uint64_t)target_w*h;
        uint64_t aspect=loading_diff(a,b)*1048576/(a+b),area=loading_diff((uint64_t)w*h,(uint64_t)target_w*target_h)*1048576/((uint64_t)w*h+(uint64_t)target_w*target_h);
        if(group==UINT_MAX||aspect<best_aspect||(aspect==best_aspect&&area<best_area)) {group=i;best_aspect=aspect;best_area=area;}
    }
    if(group==UINT_MAX)goto done;
    const unsigned char *g=meta+32+32*group;unsigned w=loading_u32(g),h=loading_u32(g+4),colors=loading_u32(g+8),count=loading_u32(g+12),first=loading_u32(g+24),codec=loading_u32(g+28);
    uint64_t palette_off=loading_u64(g+16);size_t n=(size_t)w*h;
    if(!count||count>36||first>frames||count>frames-first||(codec!=1&&codec!=2) ||
       (codec==1&&(!colors||colors>65536||!loading_range(palette_off,(uint64_t)colors*4,total,minimum))))goto done;
    uint32_t last=0;uint64_t used=0;state=loading_state(&last,&used);if(state<0)goto done;
    unsigned choices[36],number=0;bool seen[37]={false};
    for(unsigned i=0;i<count;i++) {
        const unsigned char *f=meta+32+32*groups+32*(first+i);unsigned sheet=loading_u32(f);
        if(!sheet||sheet>36||seen[sheet]||loading_u32(f+4)!=group)goto done;
        seen[sheet]=true;if(!(used&(1ULL<<(sheet-1))))choices[number++]=i;
    }
    if(!number) {
        used=0;
        for(unsigned i=0;i<count;i++) {
            const unsigned char *f=meta+32+32*groups+32*(first+i);
            if(loading_u32(f)!=last)choices[number++]=i;
        }
    }
    if(!number)goto done;
    uint32_t random=0;
    if(getrandom(&random,sizeof(random),GRND_NONBLOCK)!=(ssize_t)sizeof(random)) {struct timespec t;clock_gettime(CLOCK_BOOTTIME,&t);random=(uint32_t)t.tv_nsec^(uint32_t)t.tv_sec^(uint32_t)getpid()*2654435761U;}
    const unsigned char *f=meta+32+32*groups+32*(first+choices[random%number]);unsigned sheet=loading_u32(f);
    uint64_t offset=loading_u64(f+8),length=loading_u64(f+16);size_t raw=n*(codec==1?2:4);
    if(loading_u32(f+24)!=raw||loading_u32(f+28)||!length||length>raw+raw/255+16||!loading_range(offset,length,total,minimum))goto done;
    compressed=malloc((size_t)length);decoded=malloc(raw);pixels=malloc(n*4);
    if(!compressed||!decoded||!pixels||loading_read(fd,compressed,(size_t)length,offset)||
       LZ4_decompress_safe((const char *)compressed,(char *)decoded,(int)length,(int)raw)!=(int)raw)goto done;
    if(codec==1) {
        palette=malloc((size_t)colors*4);if(!palette||loading_read(fd,palette,(size_t)colors*4,palette_off))goto done;
        for(size_t i=0;i<n;i++) {
            unsigned index=(unsigned)decoded[2*i]|(unsigned)decoded[2*i+1]<<8;if(index>=colors)goto done;
            uint32_t value=loading_u32(palette+4*index)&0xffffffU;memcpy(pixels+4*i,&value,4);
        }
    } else memcpy(pixels,decoded,n*4);
    used|=1ULL<<(sheet-1);
    unsigned char value[16]={'A','3','6','R',(unsigned char)sheet,0,0,0};
    for(unsigned i=0;i<8;i++)value[8+i]=(unsigned char)(used>>(8*i));
    if(pwrite(state,value,16,0)!=16||ftruncate(state,16))goto done;
    out->pixels=pixels;pixels=NULL;out->width=w;out->height=h;out->sheet=sheet;out->native=true;result=0;
    fprintf(stderr,"loading selected sheet=%02u source=%ux%u target=%ux%u fit=%s codec=%s\n",sheet,w,h,target_w,target_h,w==target_w&&h==target_h?"exact":"nearest-centred",codec==1?"palette16-lz4":"xrgb32-lz4");
done:
    if(state>=0)close(state);
    free(meta);free(compressed);free(decoded);free(palette);free(pixels);return result;
}
static int loading_load(const char *path,unsigned width,unsigned height,struct loading_frame *out) {
    memset(out,0,sizeof(*out));int fd=open(path,O_RDONLY|O_CLOEXEC);if(fd<0)return -1;
    if(!loading_pack(fd,width,height,out)) {close(fd);return 0;}
    out->pixels=malloc(LOADING_PREFIX);if(!out->pixels) {close(fd);return -1;}
    int rc=loading_read(fd,out->pixels,LOADING_PREFIX,0);close(fd);
    if(rc) {free(out->pixels);out->pixels=NULL;return -1;}
    out->width=640;out->height=480;out->native=false;
    fprintf(stderr,"loading fallback=current-logo\n");return 0;
}
