/* SPDX-License-Identifier: MIT
 * Re-activate the kernel-owned fbdev framebuffer only in a DRM-master-free gap.
 * No KD_TEXT forced restore, new GEM/FB allocation, lingering master, or stale
 * userspace CRTC snapshot. A successor owns its normal modeset immediately.
 */
#include <sys/mman.h>
static int splash_property(int fd,uint32_t object,uint32_t type,const char *name,uint64_t *value) {
    uint32_t ids[128];uint64_t values[128];
    struct drm_mode_obj_get_properties props={.obj_id=object,.obj_type=type,.count_props=128,
        .props_ptr=(uintptr_t)ids,.prop_values_ptr=(uintptr_t)values};
    if(ioctl(fd,DRM_IOCTL_MODE_OBJ_GETPROPERTIES,&props)||props.count_props>128)return -1;
    for(unsigned i=0;i<props.count_props;i++) {
        struct drm_mode_get_property p={.prop_id=ids[i]};
        if(ioctl(fd,DRM_IOCTL_MODE_GETPROPERTY,&p))return -1;
        if(!strncmp(p.name,name,sizeof(p.name))) {*value=values[i];return 0;}
    }
    errno=ENOENT;return -1;
}
static void splash_crtcs(int fd,const char *label) {
    uint32_t ids[32];struct drm_mode_card_res r={.count_crtcs=32,.crtc_id_ptr=(uintptr_t)ids};
    if(ioctl(fd,DRM_IOCTL_MODE_GETRESOURCES,&r)||r.count_crtcs>32)return;
    for(unsigned i=0;i<r.count_crtcs;i++) {
        struct drm_mode_crtc c={.crtc_id=ids[i]};uint64_t active=0;
        if(ioctl(fd,DRM_IOCTL_MODE_GETCRTC,&c))continue;
        int have=!splash_property(fd,ids[i],DRM_MODE_OBJECT_CRTC,"ACTIVE",&active);
        fprintf(stderr,"splash KMS %s crtc=%u fb=%u mode_valid=%u active=%s%llu size=%ux%u\n",
            label,c.crtc_id,c.fb_id,c.mode_valid,have?"":"unknown/",(unsigned long long)active,
            c.mode.hdisplay,c.mode.vdisplay);
    }
}
static int splash_scanout_check(int fd,int fbfd,const struct fb_var_screeninfo *v,const struct fb_fix_screeninfo *f) {
    struct drm_set_client_cap cap={.capability=DRM_CLIENT_CAP_UNIVERSAL_PLANES,.value=1};
    if(ioctl(fd,DRM_IOCTL_SET_CLIENT_CAP,&cap))return 1;
    uint32_t planes[128];struct drm_mode_get_plane_res res={.count_planes=128,.plane_id_ptr=(uintptr_t)planes};
    if(ioctl(fd,DRM_IOCTL_MODE_GETPLANERESOURCES,&res)||res.count_planes>128)return 1;
    for(unsigned i=0;i<res.count_planes;i++) {
        struct drm_mode_get_plane plane={.plane_id=planes[i]};uint64_t active,type;
        if(ioctl(fd,DRM_IOCTL_MODE_GETPLANE,&plane)||!plane.fb_id||!plane.crtc_id)continue;
        if(splash_property(fd,plane.plane_id,DRM_MODE_OBJECT_PLANE,"type",&type)||type!=1 ||
           splash_property(fd,plane.crtc_id,DRM_MODE_OBJECT_CRTC,"ACTIVE",&active)||active!=1)continue;
        struct drm_mode_crtc crtc={.crtc_id=plane.crtc_id};
        if(ioctl(fd,DRM_IOCTL_MODE_GETCRTC,&crtc)||!crtc.mode_valid||crtc.fb_id!=plane.fb_id ||
           crtc.mode.hdisplay!=v->xres||crtc.mode.vdisplay!=v->yres)continue;
        struct drm_mode_fb_cmd fb={.fb_id=plane.fb_id};
        if(ioctl(fd,DRM_IOCTL_MODE_GETFB,&fb)||!fb.handle)continue;
        unsigned bytes=v->bits_per_pixel/8;size_t size=(size_t)fb.pitch*fb.height;
        struct drm_mode_map_dumb mapping={.handle=fb.handle};
        unsigned char *map=MAP_FAILED,*row=NULL;int match=0;
        if((bytes!=2&&bytes!=4)||fb.bpp!=v->bits_per_pixel || !fb.pitch ||
           ((uint64_t)crtc.x+v->xres)*bytes>fb.pitch || (uint64_t)crtc.y+v->yres>fb.height ||
           size>128U*1024*1024 || ioctl(fd,DRM_IOCTL_MODE_MAP_DUMB,&mapping))goto release;
        map=mmap(NULL,size,PROT_READ,MAP_SHARED,fd,(off_t)mapping.offset);if(map==MAP_FAILED)goto release;
        row=malloc((size_t)v->xres*bytes);if(!row)goto release;
        match=1;
        for(unsigned y=0;y<v->yres;y++) {
            off_t off=(off_t)(y+v->yoffset)*f->line_length+(off_t)v->xoffset*bytes;
            size_t length=(size_t)v->xres*bytes;
            if(pread(fbfd,row,length,off)!=(ssize_t)length ||
               memcmp(row,map+(size_t)(crtc.y+y)*fb.pitch+(size_t)crtc.x*bytes,length)) {match=0;break;}
        }
release:
        free(row);if(map!=MAP_FAILED)munmap(map,size);
        struct drm_gem_close close_handle={.handle=fb.handle};ioctl(fd,DRM_IOCTL_GEM_CLOSE,&close_handle);
        if(match) {
            struct drm_crtc_get_sequence a={.crtc_id=plane.crtc_id},b={.crtc_id=plane.crtc_id};
            int seq=!ioctl(fd,DRM_IOCTL_CRTC_GET_SEQUENCE,&a);
            usleep(50000);
            seq=seq&&!ioctl(fd,DRM_IOCTL_CRTC_GET_SEQUENCE,&b);
            /* Recheck the binding after the delay: a successor may already
             * have replaced our framebuffer. That is a handoff, not proof. */
            struct drm_mode_get_plane check={.plane_id=plane.plane_id};uint64_t current_active=0;
            if(ioctl(fd,DRM_IOCTL_MODE_GETPLANE,&check)||check.fb_id!=plane.fb_id ||
               splash_property(fd,plane.crtc_id,DRM_MODE_OBJECT_CRTC,"ACTIVE",&current_active)||current_active!=1)continue;
            fprintf(stderr,"splash scanout verified crtc=%u active=1 plane=%u fb=%u size=%ux%u pitch=%u fbdev_pixels_match=1 vblank=%s%llu->%llu\n",
                plane.crtc_id,plane.plane_id,plane.fb_id,v->xres,v->yres,fb.pitch,seq?"":"unavailable/",
                (unsigned long long)a.sequence,(unsigned long long)b.sequence);
            return 0;
        }
    }
    errno=ENODATA;return 1;
}
struct splash_brightness {char node[PATH_MAX];unsigned value;};
static unsigned splash_read_brightness(const char *snapshot,struct splash_brightness *items,unsigned maximum) {
    if(!snapshot||!*snapshot)return 0;
    FILE *file=fopen(snapshot,"re");if(!file)return 0;
    unsigned n=0;char line[PATH_MAX+80],node[PATH_MAX],extra;unsigned value;
    while(n<maximum&&fgets(line,sizeof(line),file)) {
        if(sscanf(line,"%4095s %u %c",node,&value,&extra)!=2 ||
           strncmp(node,"/sys/devices/",13)||strstr(node,"/../")||value>1048576)continue;
        size_t len=strlen(node);if(len<11||strcmp(node+len-11,"/brightness"))continue;
        strcpy(items[n].node,node);items[n++].value=value;
    }
    fclose(file);return n;
}
static int splash_scanout(const char *source,const char *fbpath,const char *rotation,const char *drmpath,
                          unsigned wait_ms,const char *brightness) {
    struct timespec now;clock_gettime(CLOCK_MONOTONIC,&now);
    long long deadline=(long long)now.tv_sec*1000+now.tv_nsec/1000000+wait_ms;
    struct splash_brightness lights[8];unsigned nlights=splash_read_brightness(brightness,lights,8);
    int fd=open(drmpath,O_RDWR|O_CLOEXEC),fbfd=-1,rc=1,master=0;
    if(fd<0)goto done;
    struct drm_set_client_cap atomic_cap={.capability=DRM_CLIENT_CAP_ATOMIC,.value=1};
    if(ioctl(fd,DRM_IOCTL_SET_CLIENT_CAP,&atomic_cap))goto done;
    for(;;) {
        if(!ioctl(fd,DRM_IOCTL_SET_MASTER,0)) {master=1;break;}
        if(errno!=EBUSY&&errno!=EACCES)goto done;
        clock_gettime(CLOCK_MONOTONIC,&now);
        if((long long)now.tv_sec*1000+now.tv_nsec/1000000>=deadline)goto done;
        usleep(20000);
    }
    guest_phase("splash-master-free");splash_crtcs(fd,"before");
    if(splash_paint(source,fbpath,rotation))goto done;
    fbfd=open(fbpath,O_RDWR|O_CLOEXEC);if(fbfd<0)goto done;
    struct fb_var_screeninfo v;struct fb_fix_screeninfo f;
    if(ioctl(fbfd,FBIOGET_VSCREENINFO,&v)||ioctl(fbfd,FBIOGET_FSCREENINFO,&f))goto done;
    for(unsigned i=0;i<nlights;i++) {
        FILE *out=fopen(lights[i].node,"we");
        if(out) {if(fprintf(out,"%u\n",lights[i].value)<0)perror("splash brightness");fclose(out);}
        fprintf(stderr,"splash brightness requested node=%s value=%u\n",lights[i].node,lights[i].value);
    }
    /* Kernel fbdev's ordinary commit takes an internal master. Drop ours
     * first; its own master lock refuses to steal a live successor's CRTC. */
    if(ioctl(fd,DRM_IOCTL_DROP_MASTER,0))goto done;
    master=0;
    v.activate=FB_ACTIVATE_NOW|FB_ACTIVATE_FORCE; /* NEVER FB_ACTIVATE_KD_TEXT */
    if(ioctl(fbfd,FBIOPUT_VSCREENINFO,&v)||ioctl(fbfd,FBIOBLANK,FB_BLANK_UNBLANK))goto done;
    if(ioctl(fbfd,FBIOGET_VSCREENINFO,&v)||ioctl(fbfd,FBIOGET_FSCREENINFO,&f))goto done;
    splash_crtcs(fd,"after");
    rc=splash_scanout_check(fd,fbfd,&v,&f);
    if(!rc)guest_phase("splash-scanout-active");
done:
    if(rc)perror("splash scanout not verified");
    if(master)ioctl(fd,DRM_IOCTL_DROP_MASTER,0);
    if(fbfd>=0)close(fbfd);
    if(fd>=0)close(fd);
    return rc;
}
