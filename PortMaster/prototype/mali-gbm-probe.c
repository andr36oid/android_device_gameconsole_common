/* SPDX-License-Identifier: MIT
 * mali-gbm-probe: prove the Linux libmali GBM/EGL/GLES stack can render on the
 * freed /dev/dri/card0 against this Android kernel's Bifrost kbase driver.
 *
 * This is the PortMaster approach-A G2 gate. It is run AFTER the framework-down
 * display handoff (Android framework stopped, composer stopped, card0 free). It:
 *   - opens card0 and takes DRM master,
 *   - creates a GBM device on that fd,
 *   - brings up EGL (the eglInitialize here is the kbase ABI handshake: UK 11.29
 *     kernel vs the blob's expected version), a GLESv2 context and a GBM window,
 *   - renders animated solid colours and scans each frame out with a raw DRM
 *     modeset, so a working Mali shows cycling full-screen colour on the panel,
 *   - restores and releases cleanly.
 *
 * Self-contained on purpose: no libdrm, no EGL/GBM headers (their platform
 * guessing fights an Android tree). Raw DRM uapi + local EGL/GBM/GLES decls that
 * match the stable C ABIs the blob exports. Link straight against libmali.
 */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#ifndef __user
#define __user
#endif
#include "drm.h"
#include "drm_mode.h"

/* ---- minimal GBM ABI (Mesa-compatible, as libmali exports) ---------------- */
struct gbm_device;
struct gbm_surface;
struct gbm_bo;
union gbm_bo_handle { void *ptr; int32_t s32; uint32_t u32; int64_t s64; uint64_t u64; };
#define GBM_FORMAT_XRGB8888 0x34325258u
#define GBM_BO_USE_SCANOUT   (1u << 0)
#define GBM_BO_USE_RENDERING (1u << 2)
extern struct gbm_device *gbm_create_device(int fd);
extern void gbm_device_destroy(struct gbm_device *gbm);
extern struct gbm_surface *gbm_surface_create(struct gbm_device *gbm, uint32_t w,
        uint32_t h, uint32_t format, uint32_t flags);
extern void gbm_surface_destroy(struct gbm_surface *s);
extern struct gbm_bo *gbm_surface_lock_front_buffer(struct gbm_surface *s);
extern void gbm_surface_release_buffer(struct gbm_surface *s, struct gbm_bo *bo);
extern uint32_t gbm_bo_get_stride(struct gbm_bo *bo);
extern union gbm_bo_handle gbm_bo_get_handle(struct gbm_bo *bo);

/* ---- minimal EGL ABI ------------------------------------------------------ */
typedef void *EGLDisplay; typedef void *EGLConfig; typedef void *EGLContext;
typedef void *EGLSurface; typedef void *EGLClientBuffer;
typedef unsigned int EGLenum; typedef unsigned int EGLBoolean; typedef int EGLint;
typedef void *EGLNativeDisplayType; typedef void *EGLNativeWindowType;
#define EGL_NO_DISPLAY ((EGLDisplay)0)
#define EGL_NO_CONTEXT ((EGLContext)0)
#define EGL_NO_SURFACE ((EGLSurface)0)
#define EGL_FALSE 0
#define EGL_TRUE 1
#define EGL_NONE 0x3038
#define EGL_SUCCESS 0x3000
#define EGL_VENDOR 0x3053
#define EGL_VERSION 0x3054
#define EGL_SURFACE_TYPE 0x3033
#define EGL_WINDOW_BIT 0x0004
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_OPENGL_ES2_BIT 0x0004
#define EGL_RED_SIZE 0x3024
#define EGL_GREEN_SIZE 0x3023
#define EGL_BLUE_SIZE 0x3022
#define EGL_ALPHA_SIZE 0x3021
#define EGL_NATIVE_VISUAL_ID 0x302E
#define EGL_OPENGL_ES_API 0x30A0
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#define EGL_PLATFORM_GBM_KHR 0x31D7
extern EGLDisplay eglGetDisplay(EGLNativeDisplayType);
extern void *eglGetProcAddress(const char *);
typedef EGLDisplay (*pfn_get_platform_display)(EGLenum, void *, const EGLint *);
extern EGLBoolean eglInitialize(EGLDisplay, EGLint *major, EGLint *minor);
extern EGLBoolean eglTerminate(EGLDisplay);
extern const char *eglQueryString(EGLDisplay, EGLint name);
extern EGLBoolean eglBindAPI(EGLenum);
extern EGLBoolean eglChooseConfig(EGLDisplay, const EGLint *attrib, EGLConfig *,
        EGLint size, EGLint *num);
extern EGLBoolean eglGetConfigAttrib(EGLDisplay, EGLConfig, EGLint attr, EGLint *val);
extern EGLContext eglCreateContext(EGLDisplay, EGLConfig, EGLContext share, const EGLint *attrib);
extern EGLSurface eglCreateWindowSurface(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *attrib);
extern EGLBoolean eglMakeCurrent(EGLDisplay, EGLSurface draw, EGLSurface read, EGLContext);
extern EGLBoolean eglSwapBuffers(EGLDisplay, EGLSurface);
extern EGLBoolean eglDestroySurface(EGLDisplay, EGLSurface);
extern EGLBoolean eglDestroyContext(EGLDisplay, EGLContext);
extern EGLint eglGetError(void);

/* ---- minimal GLESv2 ABI --------------------------------------------------- */
typedef unsigned int GLenum; typedef unsigned int GLbitfield; typedef float GLclampf;
typedef int GLint; typedef int GLsizei; typedef unsigned char GLubyte;
#define GL_COLOR_BUFFER_BIT 0x00004000
#define GL_VENDOR 0x1F00
#define GL_RENDERER 0x1F01
#define GL_VERSION 0x1F02
extern void glClearColor(GLclampf, GLclampf, GLclampf, GLclampf);
extern void glClear(GLbitfield);
extern void glViewport(GLint, GLint, GLsizei, GLsizei);
extern void glFinish(void);
extern const GLubyte *glGetString(GLenum);

/* ---- raw DRM helpers (same style as kms-probe) ---------------------------- */
#define MAX_IDS 64
#define MAX_MODES 128

static int call(int fd, unsigned long req, void *arg)
{
    int r;
    do { r = ioctl(fd, req, arg); } while (r < 0 && errno == EINTR);
    return r;
}
static int fail(const char *s) { fprintf(stderr, "FAIL %s: errno=%d (%s)\n", s, errno, strerror(errno)); return -1; }

int main(int argc, char **argv)
{
    const char *dev = "/dev/dri/card0";
    unsigned seconds = 10;
    if (argc > 1) seconds = (unsigned)strtoul(argv[1], NULL, 10);
    if (seconds < 1 || seconds > 600) seconds = 10;
    setvbuf(stdout, NULL, _IOLBF, 0);

    int fd = open(dev, O_RDWR | O_CLOEXEC);
    if (fd < 0) return fail("open card0"), 3;
    if (call(fd, DRM_IOCTL_SET_MASTER, NULL) < 0) { fail("SET_MASTER"); return 3; }
    printf("MASTER acquired %s\n", dev);

    /* find the one connected connector, its preferred mode and a CRTC. */
    uint32_t crtcs[MAX_IDS], conns[MAX_IDS], encs[MAX_IDS];
    struct drm_mode_card_res res; memset(&res, 0, sizeof(res));
    res.crtc_id_ptr = (uintptr_t)crtcs; res.count_crtcs = MAX_IDS;
    res.connector_id_ptr = (uintptr_t)conns; res.count_connectors = MAX_IDS;
    if (call(fd, DRM_IOCTL_MODE_GETRESOURCES, &res) < 0) { fail("GETRESOURCES"); return 4; }
    if (!res.count_crtcs || res.count_crtcs > 32) { fprintf(stderr, "FAIL crtc count\n"); return 4; }

    struct drm_mode_modeinfo mode; memset(&mode, 0, sizeof(mode));
    uint32_t conn_id = 0, crtc_id = 0; int have = 0;
    for (unsigned i = 0; i < res.count_connectors; i++) {
        struct drm_mode_get_connector c; struct drm_mode_modeinfo modes[MAX_MODES];
        memset(&c, 0, sizeof(c));
        c.connector_id = conns[i];
        c.count_modes = MAX_MODES; c.modes_ptr = (uintptr_t)modes;
        c.count_encoders = MAX_IDS; c.encoders_ptr = (uintptr_t)encs;
        if (call(fd, DRM_IOCTL_MODE_GETCONNECTOR, &c) < 0) { fail("GETCONNECTOR"); return 4; }
        if (c.connection != 1 || !c.count_modes) continue;   /* connected + has modes */
        unsigned mi = 0;
        for (unsigned m = 0; m < c.count_modes; m++)
            if (modes[m].type & (1 << 3) /*PREFERRED*/) { mi = m; break; }
        mode = modes[mi]; conn_id = c.connector_id;
        struct drm_mode_get_encoder e; memset(&e, 0, sizeof(e)); e.encoder_id = c.encoder_id;
        if (c.encoder_id && call(fd, DRM_IOCTL_MODE_GETENCODER, &e) == 0 && e.crtc_id)
            crtc_id = e.crtc_id;
        else
            crtc_id = crtcs[0];
        have = 1; break;
    }
    if (!have) { fprintf(stderr, "FAIL no connected connector\n"); return 4; }
    printf("MODE conn=%u crtc=%u %ux%u %.*s\n", conn_id, crtc_id, mode.hdisplay,
           mode.vdisplay, DRM_DISPLAY_MODE_LEN, mode.name);

    /* GBM device on the DRM master fd. */
    struct gbm_device *gbm = gbm_create_device(fd);
    if (!gbm) { fprintf(stderr, "FAIL gbm_create_device (libmali did not accept card0 fd)\n"); return 5; }
    puts("GBM device created");
    struct gbm_surface *surf = gbm_surface_create(gbm, mode.hdisplay, mode.vdisplay,
            GBM_FORMAT_XRGB8888, GBM_BO_USE_SCANOUT | GBM_BO_USE_RENDERING);
    if (!surf) { fprintf(stderr, "FAIL gbm_surface_create\n"); return 5; }
    puts("GBM surface created");

    /* EGL on the GBM device. eglInitialize = the kbase ABI handshake. */
    EGLDisplay dpy = EGL_NO_DISPLAY;
    pfn_get_platform_display get_pdpy = (pfn_get_platform_display)eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (get_pdpy) dpy = get_pdpy(EGL_PLATFORM_GBM_KHR, gbm, NULL);
    if (dpy == EGL_NO_DISPLAY) dpy = eglGetDisplay((EGLNativeDisplayType)gbm);
    if (dpy == EGL_NO_DISPLAY) { fprintf(stderr, "FAIL eglGetDisplay\n"); return 6; }
    EGLint emaj = 0, emin = 0;
    if (!eglInitialize(dpy, &emaj, &emin)) {
        fprintf(stderr, "FAIL eglInitialize egl_error=0x%x (kbase ABI handshake failed)\n", eglGetError());
        return 6;
    }
    printf("EGL ok %d.%d vendor=%s version=%s\n", emaj, emin,
           eglQueryString(dpy, EGL_VENDOR), eglQueryString(dpy, EGL_VERSION));

    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint cfga[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 0, EGL_NONE
    };
    EGLConfig cfgs[32]; EGLint ncfg = 0;
    if (!eglChooseConfig(dpy, cfga, cfgs, 32, &ncfg) || ncfg == 0) {
        fprintf(stderr, "FAIL eglChooseConfig (%d configs)\n", ncfg); return 6;
    }
    /* prefer a config whose native visual matches XRGB8888. */
    EGLConfig cfg = cfgs[0];
    for (EGLint i = 0; i < ncfg; i++) {
        EGLint vid = 0;
        if (eglGetConfigAttrib(dpy, cfgs[i], EGL_NATIVE_VISUAL_ID, &vid) && (uint32_t)vid == GBM_FORMAT_XRGB8888) {
            cfg = cfgs[i]; break;
        }
    }
    const EGLint ctxa[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxa);
    if (ctx == EGL_NO_CONTEXT) { fprintf(stderr, "FAIL eglCreateContext 0x%x\n", eglGetError()); return 6; }
    EGLSurface es = eglCreateWindowSurface(dpy, cfg, (EGLNativeWindowType)surf, NULL);
    if (es == EGL_NO_SURFACE) { fprintf(stderr, "FAIL eglCreateWindowSurface 0x%x\n", eglGetError()); return 6; }
    if (!eglMakeCurrent(dpy, es, es, ctx)) { fprintf(stderr, "FAIL eglMakeCurrent 0x%x\n", eglGetError()); return 6; }
    printf("GL vendor=%s renderer=%s version=%s\n", glGetString(GL_VENDOR),
           glGetString(GL_RENDERER), glGetString(GL_VERSION));

    glViewport(0, 0, mode.hdisplay, mode.vdisplay);

    /* render loop: cycle colour, scan each locked front buffer out via raw DRM. */
    uint32_t prev_fb = 0; struct gbm_bo *prev_bo = NULL;
    unsigned frames = seconds * 10;
    int rc = 0;
    for (unsigned f = 0; f <= frames; f++) {
        float t = (float)f / 10.0f;
        float r = 0.5f + 0.5f * (float)((t * 0.7));
        glClearColor((f / 10) % 2 ? 0.9f : 0.1f,
                     (f / 7) % 2 ? 0.8f : 0.1f,
                     (f / 5) % 2 ? 0.9f : 0.2f, 1.0f);
        (void)r;
        glClear(GL_COLOR_BUFFER_BIT);
        if (!eglSwapBuffers(dpy, es)) { fprintf(stderr, "FAIL eglSwapBuffers 0x%x\n", eglGetError()); rc = 7; break; }
        struct gbm_bo *bo = gbm_surface_lock_front_buffer(surf);
        if (!bo) { fprintf(stderr, "FAIL lock_front_buffer\n"); rc = 7; break; }
        uint32_t stride = gbm_bo_get_stride(bo);
        uint32_t handle = gbm_bo_get_handle(bo).u32;
        struct drm_mode_fb_cmd fb; memset(&fb, 0, sizeof(fb));
        fb.width = mode.hdisplay; fb.height = mode.vdisplay;
        fb.bpp = 32; fb.depth = 24; fb.pitch = stride; fb.handle = handle;
        if (call(fd, DRM_IOCTL_MODE_ADDFB, &fb) < 0) { fail("ADDFB"); rc = 7; gbm_surface_release_buffer(surf, bo); break; }
        struct drm_mode_crtc set; memset(&set, 0, sizeof(set));
        set.crtc_id = crtc_id; set.fb_id = fb.fb_id;
        set.set_connectors_ptr = (uintptr_t)&conn_id; set.count_connectors = 1;
        set.mode = mode; set.mode_valid = 1;
        if (call(fd, DRM_IOCTL_MODE_SETCRTC, &set) < 0) { fail("SETCRTC"); rc = 7; }
        if (f == 0) printf("SCANOUT first frame fb=%u stride=%u\n", fb.fb_id, stride);
        if (prev_fb) call(fd, DRM_IOCTL_MODE_RMFB, &prev_fb);
        if (prev_bo) gbm_surface_release_buffer(surf, prev_bo);
        prev_fb = fb.fb_id; prev_bo = bo;
        if (rc) break;
        struct timespec ts = { 0, 100 * 1000 * 1000 }; nanosleep(&ts, NULL);  /* ~10 fps */
    }
    glFinish();
    printf("RENDER done rc=%d frames=%u\n", rc, frames);

    /* teardown: disable the CRTC, then release everything, drop master. */
    struct drm_mode_crtc off; memset(&off, 0, sizeof(off)); off.crtc_id = crtc_id;
    call(fd, DRM_IOCTL_MODE_SETCRTC, &off);
    if (prev_fb) call(fd, DRM_IOCTL_MODE_RMFB, &prev_fb);
    if (prev_bo) gbm_surface_release_buffer(surf, prev_bo);
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(dpy, es); eglDestroyContext(dpy, ctx); eglTerminate(dpy);
    gbm_surface_destroy(surf); gbm_device_destroy(gbm);
    call(fd, DRM_IOCTL_DROP_MASTER, NULL);
    close(fd);
    printf("EXIT status=%d\n", rc);
    return rc;
}
