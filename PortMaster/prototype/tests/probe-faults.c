/* SPDX-License-Identifier: MIT
 * Synthetic DRM responses to exercise unwind paths, never real hardware.
 */
#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
#define __user
#include "drm.h"
#include "drm_mode.h"

static const char *scenario;
static int owned, created, added, mapped, drawn, closed, dropped, restored, disabled, disable_attempted;
static int injected;
static uint32_t pixels[64 * 48];
static int fake_open(const char *path, int flags, ...);
static int fake_close(int fd);
static int fake_ioctl(int fd, unsigned long request, ...);
static void *fake_mmap(void *a, size_t n, int prot, int flags, int fd, off_t off);
static int fake_munmap(void *p, size_t n);
static int fake_sleep(const struct timespec *req, struct timespec *rem);

#define main probe_main
#define open fake_open
#define close fake_close
#define ioctl fake_ioctl
#define mmap fake_mmap
#define munmap fake_munmap
#define nanosleep fake_sleep
#include "../kms-probe.c"
#undef main
#undef open
#undef close
#undef ioctl
#undef mmap
#undef munmap
#undef nanosleep

static int is(const char *s) { return !strcmp(scenario, s); }
static int fail(const char *s)
{
    if (!injected && is(s)) { injected = 1; errno = EINVAL; return 1; }
    return 0;
}
static int fake_open(const char *path, int flags, ...)
{
    (void)path; (void)flags;
    if (fail("open")) return -1;
    return 19;
}
static int fake_close(int fd) {
    assert(fd == 19);
    assert(!created && !added && !mapped); /* cleanup precedes fd close */
    owned = 0; /* final close also releases master when DROP failed */
    closed++;
    if (fail("close")) return -1;
    return 0;
}
static int fake_ioctl(int fd, unsigned long request, ...)
{
    va_list args;
    void *arg;
    assert(fd == 19);
    va_start(args, request); arg = va_arg(args, void *); va_end(args);
    if (request == DRM_IOCTL_SET_MASTER) {
        if (fail("master")) return -1;
        owned = 1; return 0;
    }
    assert(owned);
    if (request == DRM_IOCTL_DROP_MASTER) {
        assert(!created && !added && !mapped);
        assert(!drawn || restored || disable_attempted);
        dropped++;
        if (fail("drop")) return -1;
        owned = 0; return 0;
    }
    if (request == DRM_IOCTL_MODE_GETRESOURCES) {
        struct drm_mode_card_res *r = arg;
        if (fail("resources")) return -1;
        ((uint32_t *)(uintptr_t)r->crtc_id_ptr)[0] = 11;
        ((uint32_t *)(uintptr_t)r->connector_id_ptr)[0] = 21;
        r->count_crtcs = 1; r->count_connectors = 1;
        return 0;
    }
    if (request == DRM_IOCTL_MODE_GETCONNECTOR) {
        struct drm_mode_get_connector *c = arg;
        struct drm_mode_modeinfo *m = (void *)(uintptr_t)c->modes_ptr;
        if (fail("connector")) return -1;
        c->connection = is("disconnected") ? 2 : 1;
        c->encoder_id = 41; c->count_modes = 1; c->count_encoders = 1;
        ((uint32_t *)(uintptr_t)c->encoders_ptr)[0] = 41;
        memset(m, 0, sizeof(*m)); m->hdisplay = 64; m->vdisplay = 48;
        m->type = DRM_MODE_TYPE_PREFERRED; strcpy(m->name, "fake64x48");
        return 0;
    }
    if (request == DRM_IOCTL_MODE_GETENCODER) {
        struct drm_mode_get_encoder *e = arg;
        if (fail("encoder")) return -1;
        e->crtc_id = 11; e->possible_crtcs = 1; return 0;
    }
    if (request == DRM_IOCTL_MODE_GETCRTC) {
        struct drm_mode_crtc *c = arg;
        if (fail("save")) return -1;
        c->mode_valid = is("active") || is("restore") || is("vanished") || is("disable-fail");
        c->fb_id = c->mode_valid ? 90 : 0;
        return 0;
    }
    if (request == DRM_IOCTL_MODE_CREATE_DUMB) {
        struct drm_mode_create_dumb *b = arg;
        if (fail("create")) return -1;
        b->handle = 51; b->pitch = b->width * 4; b->size = b->pitch * b->height;
        created = 1; return 0;
    }
    if (request == DRM_IOCTL_MODE_ADDFB) {
        struct drm_mode_fb_cmd *f = arg;
        if (fail("addfb")) return -1;
        f->fb_id = 31; added = 1; return 0;
    }
    if (request == DRM_IOCTL_MODE_MAP_DUMB) {
        if (fail("map_ioctl")) return -1;
        return 0;
    }
    if (request == DRM_IOCTL_MODE_SETCRTC) {
        struct drm_mode_crtc *c = arg;
        if (c->fb_id == 31) {
            if (fail("modeset")) return -1;
            assert(mapped && added && created);
            assert(pixels[0] == 0x00ffffff); /* actual pattern written */
            drawn = 1;
        } else if (c->fb_id == 90) {
            if (fail("restore")) return -1;
            if (is("vanished") || is("disable-fail")) { errno = ENOENT; return -1; }
            assert(c->count_connectors == 1);
            assert(((uint32_t *)(uintptr_t)c->set_connectors_ptr)[0] == 21);
            restored = 1;
        } else {
            assert(!c->fb_id && !c->mode_valid && !c->count_connectors);
            assert(!c->set_connectors_ptr && !c->x && !c->y);
            assert(c->crtc_id == 11 && owned);
            disable_attempted = 1;
            if (fail("disable-fail")) return -1;
            disabled = 1;
        }
        return 0;
    }
    if (request == DRM_IOCTL_MODE_RMFB) {
        assert(!mapped && added); added = 0; return 0;
    }
    if (request == DRM_IOCTL_MODE_DESTROY_DUMB) {
        assert(!added && !mapped && created); created = 0; return 0;
    }
    assert(!"unexpected ioctl"); return -1;
}
static void *fake_mmap(void *a, size_t n, int prot, int flags, int fd, off_t off)
{
    (void)a; (void)prot; (void)flags; (void)fd; (void)off;
    assert(n == sizeof(pixels));
    if (fail("mmap")) return MAP_FAILED;
    mapped = 1; return pixels;
}
static int fake_munmap(void *p, size_t n)
{
    assert(mapped && p == pixels && n == sizeof(pixels)); mapped = 0; return 0;
}
static int fake_sleep(const struct timespec *req, struct timespec *rem)
{
    (void)req; (void)rem;
    if (is("signal")) { raise(SIGTERM); errno = EINTR; return -1; }
    return 0;
}
int main(int argc, char **argv)
{
    int expected, result;
    char *probe_args[] = { "probe-under-test", "--seconds", "1", NULL };
    assert(argc == 3);
    scenario = argv[1]; expected = atoi(argv[2]);
    if (is("check")) probe_args[1] = "--check-master";
    result = probe_main(is("check") ? 2 : 3, probe_args);
    assert(result == expected);
    assert(!owned && !created && !added && !mapped);
    assert(closed == !is("open"));
    assert(dropped == (!is("open") && !is("master")));
    if (drawn) assert(restored || disabled || (is("disable-fail") && disable_attempted));
    if (is("active")) assert(restored);
    if (is("restore")) assert(disabled && injected);
    if (is("vanished")) assert(disabled);
    if (is("master") || is("check")) assert(!drawn);
    printf("PASS scenario=%s\n", scenario);
    return 0;
}
