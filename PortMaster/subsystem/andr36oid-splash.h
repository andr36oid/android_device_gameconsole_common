/* SPDX-License-Identifier: MIT
 * The shipped raw contains three identical 640x480 BGRA frames. Render its first
 * frame into the actual fbdev geometry; never change the display mode or pan. */
#include <linux/fb.h>
#include "andr36oid-loading.h"
static uint32_t splash_channel(unsigned value,struct fb_bitfield field) {
    return field.length ? ((value*((1U<<field.length)-1)+127)/255)<<field.offset : 0;
}
static int splash_render(int fd,struct loading_frame *frame,const struct fb_var_screeninfo *v,
                         const struct fb_fix_screeninfo *f,unsigned rotation) {
    const unsigned char *source=frame->pixels;unsigned sw=frame->width,sh=frame->height;
    unsigned bytes=v->bits_per_pixel/8;
    if((bytes!=2 && bytes!=4) || !v->xres || !v->yres || v->xres>8192 || v->yres>8192 ||
       v->yres_virtual<v->yres || v->yres_virtual>32768 || f->line_length>131072 ||
       ((uint64_t)v->xoffset+v->xres)*bytes>f->line_length ||
       (uint64_t)f->line_length*v->yres_virtual>128U*1024*1024 ||
       (f->smem_len && (uint64_t)f->line_length*v->yres_virtual>f->smem_len)) {errno=EINVAL;return 1;}
    struct fb_bitfield fields[]={v->red,v->green,v->blue,v->transp};
    for(unsigned i=0;i<4;i++)if(fields[i].length>8 || fields[i].offset+fields[i].length>v->bits_per_pixel || fields[i].msb_right) {errno=EINVAL;return 1;}
    unsigned turns=rotation/90, lw=turns%2?v->yres:v->xres,lh=turns%2?v->xres:v->yres;
    if(frame->native&&rotation==0&&sw==v->xres&&sh==v->yres&&bytes==4&&v->xoffset==0&&v->yoffset%v->yres==0&&
       v->yres_virtual%v->yres==0&&f->line_length==sw*4&&v->red.length==8&&v->red.offset==16&&
       v->green.length==8&&v->green.offset==8&&v->blue.length==8&&v->blue.offset==0&&
       (v->transp.length==0||(v->transp.length==8&&v->transp.offset==24))) {
        if(v->transp.length==8)for(size_t i=0;i<(size_t)sw*sh;i++)frame->pixels[4*i+3]=255;
        if(lseek(fd,0,SEEK_SET)<0)return 1;
        for(unsigned y=0;y<v->yres_virtual;y+=sh) {
            size_t sent=0,length=(size_t)sw*sh*4;
            while(sent<length) {ssize_t n=write(fd,source+sent,length-sent);
                if(n<0&&errno==EINTR)continue;
                if(n<=0)return 1;
                sent+=(size_t)n;
            }
        }
        return 0;
    }
    unsigned dw=lw,dh=(unsigned)((uint64_t)dw*sh/sw);
    if(dh>lh) {dh=lh;dw=(unsigned)((uint64_t)dh*sw/sh);}
    if(!dw || !dh) {errno=EINVAL;return 1;}
    unsigned left=(lw-dw)/2,top=(lh-dh)/2;
    unsigned char *row=calloc(1,f->line_length);if(!row)return 1;
    if(lseek(fd,0,SEEK_SET)<0) {free(row);return 1;}
    for(unsigned y=0;y<v->yres_virtual;y++) {
        memset(row,0,f->line_length);
        unsigned py=(y+v->yres-v->yoffset%v->yres)%v->yres;
        for(unsigned px=0;px<v->xres;px++) {
            unsigned x=px,yy=py;
            if(turns==1) {x=py;yy=v->xres-1-px;}
            else if(turns==2) {x=v->xres-1-px;yy=v->yres-1-py;}
            else if(turns==3) {x=v->yres-1-py;yy=px;}
            const unsigned char *pixel=source;
            if(x>=left && x-left<dw && yy>=top && yy-top<dh) {
                unsigned sx=((uint64_t)(2*(x-left)+1)*sw)/(2*dw);
                unsigned sy=((uint64_t)(2*(yy-top)+1)*sh)/(2*dh);
                pixel=source+((size_t)sy*sw+sx)*4;
            }
            uint32_t packed=splash_channel(pixel[2],v->red)|splash_channel(pixel[1],v->green)|
                splash_channel(pixel[0],v->blue)|splash_channel(255,v->transp);
            memcpy(row+(v->xoffset+px)*bytes,&packed,bytes);
        }
        size_t sent=0;
        while(sent<f->line_length) {
            ssize_t n=write(fd,row+sent,f->line_length-sent);
            if(n<0 && errno==EINTR)continue;
            if(n<=0) {free(row);return 1;}
            sent+=(size_t)n;
        }
    }
    free(row);return 0;
}
static int splash_paint(const char *source_path,const char *fb,const char *rotation_text) {
    int result=1,fd=-1;struct loading_frame frame={0};struct timespec begin,decoded,finished;
    clock_gettime(CLOCK_MONOTONIC,&begin);
    fd=open(fb,O_RDWR|O_CLOEXEC);if(fd<0)goto done;
    struct fb_var_screeninfo v;struct fb_fix_screeninfo f;
    if(ioctl(fd,FBIOGET_VSCREENINFO,&v)||ioctl(fd,FBIOGET_FSCREENINFO,&f))goto done;
    unsigned rotation=v.rotate<4?v.rotate*90:0;
    if(rotation_text&&*rotation_text) {
        char *end;unsigned long n=strtoul(rotation_text,&end,10);
        if(*end||(n!=0&&n!=90&&n!=180&&n!=270)) {errno=EINVAL;goto done;}
        rotation=(unsigned)n;
    }
    unsigned width=rotation%180?v.yres:v.xres,height=rotation%180?v.xres:v.yres;
    if(!width||!height||width>8192||height>8192||loading_load(source_path,width,height,&frame))goto done;
    clock_gettime(CLOCK_MONOTONIC,&decoded);
    result=splash_render(fd,&frame,&v,&f,rotation);
    clock_gettime(CLOCK_MONOTONIC,&finished);
    if(!result)fprintf(stderr,"fbdev splash painted %ux%u virtual=%u stride=%u bpp=%u rotation=%u sheet=%02u load_us=%lld render_us=%lld scanout=unverified\n",
        v.xres,v.yres,v.yres_virtual,f.line_length,v.bits_per_pixel,rotation,frame.sheet,
        (long long)(decoded.tv_sec-begin.tv_sec)*1000000+(decoded.tv_nsec-begin.tv_nsec)/1000,
        (long long)(finished.tv_sec-decoded.tv_sec)*1000000+(finished.tv_nsec-decoded.tv_nsec)/1000);
done:
    if(result)perror("paint subsystem splash");
    if(fd>=0)close(fd);
    free(frame.pixels);return result;
}
