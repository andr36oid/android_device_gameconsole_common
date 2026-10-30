/* Test the actual gamma snapshot code using simulated DRM ioctls. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdarg.h>
#define open drm_test_open
#define ioctl drm_test_ioctl
#define main guest_program_main
#include "../../subsystem/andr36oid-guest.c"
#undef main
#undef ioctl
#undef open
static bool fail_read;
static int restored;
int drm_test_open(const char *path,int flags,...) {
    return syscall(SYS_openat,AT_FDCWD,!strcmp(path,"/dev/dri/card0")?"/dev/null":path,flags,0);
}
int drm_test_ioctl(int fd,unsigned long request,...) {
    (void)fd;va_list ap;va_start(ap,request);void *arg=va_arg(ap,void *);va_end(ap);
    switch(request) {
    case DRM_IOCTL_SET_MASTER:return 0;
    case DRM_IOCTL_MODE_GETRESOURCES: {
        struct drm_mode_card_res *r=arg;if(r->crtc_id_ptr)((uint32_t *)(uintptr_t)r->crtc_id_ptr)[0]=42;r->count_crtcs=1;return 0;
    }
    case DRM_IOCTL_MODE_GETCRTC: {
        struct drm_mode_crtc *c=arg;assert(c->crtc_id==42);c->gamma_size=256;c->mode_valid=1;return 0;
    }
    case DRM_IOCTL_MODE_GETGAMMA:case DRM_IOCTL_MODE_SETGAMMA: {
        struct drm_mode_crtc_lut *g=arg;assert(g->crtc_id==42 && g->gamma_size==256);
        if(fail_read && request==DRM_IOCTL_MODE_GETGAMMA){errno=EIO;return -1;}
        uint16_t *r=(uint16_t *)(uintptr_t)g->red,*green=(uint16_t *)(uintptr_t)g->green,*b=(uint16_t *)(uintptr_t)g->blue;
        for(int i=0;i<256;i++) {
            if(request==DRM_IOCTL_MODE_GETGAMMA){r[i]=i*200;green[i]=i*180;b[i]=i*160;}
            else assert(r[i]==i*200 && green[i]==i*180 && b[i]==i*160);
        }
        if(request==DRM_IOCTL_MODE_SETGAMMA)restored++;
        return 0;
    }
    default:assert(0);return -1;
    }
}
int main(int argc,char **argv) {
    assert(argc==2);
    assert(!gamma_state(argv[1],false));assert(!gamma_state(argv[1],true));assert(restored==1);
    fail_read=true;assert(gamma_state(argv[1],false));
    assert(!gamma_state(argv[1],true));assert(restored==2);
    // A truncated second CRTC must not partially apply the first CRTC.
    FILE *f=fopen(argv[1],"r+b");assert(f);uint32_t count=2;
    assert(!fseek(f,4,SEEK_SET));assert(fwrite(&count,sizeof(count),1,f)==1);fclose(f);
    assert(gamma_state(argv[1],true));assert(restored==2);
    fail_read=false;assert(!gamma_state(argv[1],false));
    f=fopen(argv[1],"ab");assert(f);fputc('x',f);fclose(f);
    assert(gamma_state(argv[1],true));assert(restored==2);
    f=fopen(argv[1],"wb");assert(f);fputs("bad",f);fclose(f);
    assert(gamma_state(argv[1],true));assert(restored==2);
    unlink(argv[1]);puts("PASS: gamma state round trip, failed capture preserves prior state, malformed state rejected");
}
