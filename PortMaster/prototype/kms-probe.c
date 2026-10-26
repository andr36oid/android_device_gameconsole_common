/* SPDX-License-Identifier: MIT
 * One-output DRM handoff probe. No libdrm, EGL, GPU, tty or rootfs needed.
 * Build against this kernel's two DRM UAPI headers; see report build notes.
 */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
/* Raw in-tree UAPI retains sparse annotations removed by headers_install. */
#ifndef __user
#define __user
#endif
#include "drm.h"
#include "drm_mode.h"

#define MAX_IDS 64
#define MAX_MODES 128
static volatile sig_atomic_t interrupted;

static void on_signal(int sig) { interrupted = sig; }

static int call(int fd, unsigned long request, void *arg)
{
    int ret;
    do { ret = ioctl(fd, request, arg); } while (ret < 0 && errno == EINTR);
    return ret;
}

static int error(const char *step)
{
    fprintf(stderr, "FAIL %s: errno=%d (%s)\n", step, errno, strerror(errno));
    return -1;
}

static int connector(int fd, uint32_t id, struct drm_mode_get_connector *c,
                     struct drm_mode_modeinfo modes[MAX_MODES], uint32_t enc[MAX_IDS])
{
    memset(c, 0, sizeof(*c));
    c->connector_id = id;
    c->count_modes = MAX_MODES;
    c->modes_ptr = (uintptr_t)modes;
    c->count_encoders = MAX_IDS;
    c->encoders_ptr = (uintptr_t)enc;
    if (call(fd, DRM_IOCTL_MODE_GETCONNECTOR, c) < 0)
        return error("GETCONNECTOR");
    if (c->count_modes > MAX_MODES || c->count_encoders > MAX_IDS) {
        fprintf(stderr, "FAIL connector lists exceed probe bounds\n");
        return -1;
    }
    return 0;
}

static int choose_crtc(int fd, const struct drm_mode_card_res *r, const uint32_t *crtcs,
                       const struct drm_mode_get_connector *c, const uint32_t *enc)
{
    struct drm_mode_get_encoder e;
    unsigned i, j;
    memset(&e, 0, sizeof(e));
    if (c->encoder_id) {
        e.encoder_id = c->encoder_id;
        if (call(fd, DRM_IOCTL_MODE_GETENCODER, &e) < 0)
            return error("GETENCODER current");
        for (i = 0; i < r->count_crtcs; i++)
            if (crtcs[i] == e.crtc_id && (e.possible_crtcs & (1U << i)))
                return (int)i;
    }
    for (i = 0; i < c->count_encoders; i++) {
        memset(&e, 0, sizeof(e));
        e.encoder_id = enc[i];
        if (call(fd, DRM_IOCTL_MODE_GETENCODER, &e) < 0)
            return error("GETENCODER candidate");
        for (j = 0; j < r->count_crtcs; j++) {
            struct drm_mode_crtc old;
            if (!(e.possible_crtcs & (1U << j))) continue;
            memset(&old, 0, sizeof(old));
            old.crtc_id = crtcs[j];
            if (call(fd, DRM_IOCTL_MODE_GETCRTC, &old) < 0)
                return error("GETCRTC candidate");
            if (!old.mode_valid) return (int)j;
        }
    }
    fprintf(stderr, "FAIL no current or unused compatible CRTC\n");
    return -1;
}

static int disable_crtc(int fd, uint32_t id)
{
    /* DRM legacy disable: no mode, FB or connectors, only the CRTC ID.
     * Do not reuse saved here: its old FB may have been removed by HWC.
     */
    struct drm_mode_crtc off;
    memset(&off, 0, sizeof(off));
    off.crtc_id = id;
    return call(fd, DRM_IOCTL_MODE_SETCRTC, &off);
}

static void pattern(uint8_t *map, const struct drm_mode_create_dumb *b)
{
    static const uint32_t bars[] = {
        0x00ffffff, 0x00ffff00, 0x0000ffff, 0x0000ff00,
        0x00ff00ff, 0x00ff0000, 0x000000ff, 0x00000000
    };
    uint32_t x, y;
    memset(map, 0, (size_t)b->size);
    for (y = 0; y < b->height; y++) {
        uint32_t *row = (uint32_t *)(map + (size_t)y * b->pitch);
        for (x = 0; x < b->width; x++) {
            uint32_t color = bars[(uint64_t)x * 8 / b->width];
            if (y >= b->height * 3 / 4)
                color = ((x / 16 + y / 16) & 1) ? 0x00ffffff : 0;
            if (x < 4 || y < 4 || x >= b->width - 4 || y >= b->height - 4)
                color = 0x00ffffff;
            row[x] = color;
        }
    }
}

static void usage(const char *name)
{
    fprintf(stderr, "Usage: %s [--device PATH] [--seconds 1..600] [--check-master]\n", name);
}

int main(int argc, char **argv)
{
    const char *device = "/dev/dri/card0";
    unsigned seconds = 10;
    int check_only = 0, fd = -1, master = 0, changed = 0, rc = 4, idx, i;
    uint32_t crtcs[MAX_IDS], conns[MAX_IDS], enc[MAX_IDS], old_conns[MAX_IDS];
    unsigned nold = 0, connected = 0, mode_idx = 0;
    struct drm_mode_card_res res;
    struct drm_mode_get_connector selected;
    struct drm_mode_modeinfo selected_mode;
    struct drm_mode_crtc saved, set;
    struct drm_mode_create_dumb dumb;
    struct drm_mode_map_dumb mapping;
    struct drm_mode_fb_cmd fb;
    uint8_t *map = MAP_FAILED;
    struct sigaction sa;

    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--help")) { usage(argv[0]); return 0; }
        if (!strcmp(argv[i], "--check-master")) { check_only = 1; continue; }
        if (!strcmp(argv[i], "--device") && i + 1 < argc) { device = argv[++i]; continue; }
        if (!strcmp(argv[i], "--seconds") && i + 1 < argc) {
            char *end;
            long value;
            errno = 0;
            value = strtol(argv[++i], &end, 10);
            if (!errno && *argv[i] && !*end && value >= 1 && value <= 600) {
                seconds = (unsigned)value;
                continue;
            }
        }
        usage(argv[0]); return 2;
    }
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGINT, &sa, NULL) || sigaction(SIGTERM, &sa, NULL) ||
        sigaction(SIGHUP, &sa, NULL)) { error("sigaction"); return 2; }
    setvbuf(stdout, NULL, _IOLBF, 0);
    memset(&dumb, 0, sizeof(dumb));
    memset(&fb, 0, sizeof(fb));
    memset(&selected, 0, sizeof(selected));
    memset(&selected_mode, 0, sizeof(selected_mode));
    memset(&saved, 0, sizeof(saved));

    fd = open(device, O_RDWR | O_CLOEXEC);
    if (fd < 0) { error("open DRM device"); return 3; }
    if (call(fd, DRM_IOCTL_SET_MASTER, NULL) < 0) {
        error("SET_MASTER (owner busy, permission denied, or unsupported)");
        rc = 3; goto out;
    }
    master = 1;
    printf("MASTER acquired device=%s\n", device);
    if (check_only) { rc = 0; goto out; }

    memset(&res, 0, sizeof(res));
    res.crtc_id_ptr = (uintptr_t)crtcs;
    res.count_crtcs = MAX_IDS;
    res.connector_id_ptr = (uintptr_t)conns;
    res.count_connectors = MAX_IDS;
    if (call(fd, DRM_IOCTL_MODE_GETRESOURCES, &res) < 0) {
        error("GETRESOURCES"); goto out;
    }
    /* possible_crtcs is a 32-bit mask. Refuse complex/hotplug layouts. */
    if (!res.count_crtcs || res.count_crtcs > 32 || res.count_connectors > MAX_IDS) {
        fprintf(stderr, "FAIL unsupported resource counts\n"); goto out;
    }
    for (i = 0; i < (int)res.count_connectors; i++) {
        struct drm_mode_get_connector c;
        struct drm_mode_modeinfo modes[MAX_MODES];
        uint32_t candidate_enc[MAX_IDS];
        if (connector(fd, conns[i], &c, modes, candidate_enc)) goto out;
        printf("CONNECTOR id=%u type=%u status=%u modes=%u\n",
               c.connector_id, c.connector_type, c.connection, c.count_modes);
        if (c.connection != 1) continue; /* DRM_MODE_CONNECTED */
        connected++;
        if (!c.count_modes) {
            fprintf(stderr, "FAIL connected connector has no modes\n"); goto out;
        }
        selected = c;
        memcpy(enc, candidate_enc, c.count_encoders * sizeof(enc[0]));
        mode_idx = 0;
        for (unsigned m = 0; m < c.count_modes; m++)
            if (modes[m].type & DRM_MODE_TYPE_PREFERRED) { mode_idx = m; break; }
        selected_mode = modes[mode_idx];
    }
    if (connected != 1) {
        fprintf(stderr, "FAIL expected exactly one connected panel, found %u\n", connected);
        goto out;
    }
    idx = choose_crtc(fd, &res, crtcs, &selected, enc);
    if (idx < 0) goto out;
    saved.crtc_id = crtcs[idx];
    if (call(fd, DRM_IOCTL_MODE_GETCRTC, &saved) < 0) { error("GETCRTC save"); goto out; }
    /* Snapshot all connectors actually attached to this CRTC for restoration. */
    for (i = 0; i < (int)res.count_connectors; i++) {
        struct drm_mode_get_connector c;
        struct drm_mode_modeinfo modes[MAX_MODES];
        uint32_t candidate_enc[MAX_IDS];
        struct drm_mode_get_encoder e;
        if (connector(fd, conns[i], &c, modes, candidate_enc)) goto out;
        if (!c.encoder_id) continue;
        memset(&e, 0, sizeof(e)); e.encoder_id = c.encoder_id;
        if (call(fd, DRM_IOCTL_MODE_GETENCODER, &e) < 0) { error("GETENCODER save"); goto out; }
        if (e.crtc_id == saved.crtc_id) old_conns[nold++] = c.connector_id;
    }
    dumb.width = selected_mode.hdisplay;
    dumb.height = selected_mode.vdisplay;
    dumb.bpp = 32;
    if (dumb.width < 16 || dumb.height < 16) {
        fprintf(stderr, "FAIL invalid panel dimensions\n"); goto out;
    }
    printf("MODE connector=%u crtc=%u name=%.*s size=%ux%u old_fb=%u valid=%u\n",
           selected.connector_id, saved.crtc_id, DRM_DISPLAY_MODE_LEN, selected_mode.name,
           dumb.width, dumb.height, saved.fb_id, saved.mode_valid);
    rc = 5;
    if (call(fd, DRM_IOCTL_MODE_CREATE_DUMB, &dumb) < 0) { error("CREATE_DUMB"); goto out; }
    if (dumb.size > SIZE_MAX || dumb.size > 128U * 1024U * 1024U ||
        dumb.pitch < (uint64_t)dumb.width * 4 ||
        dumb.size < (uint64_t)dumb.pitch * dumb.height) {
        fprintf(stderr, "FAIL invalid/oversized dumb buffer\n"); goto out;
    }
    fb.width = dumb.width; fb.height = dumb.height;
    fb.pitch = dumb.pitch; fb.bpp = 32; fb.depth = 24; fb.handle = dumb.handle;
    if (call(fd, DRM_IOCTL_MODE_ADDFB, &fb) < 0) { error("ADDFB XRGB8888"); goto out; }
    memset(&mapping, 0, sizeof(mapping)); mapping.handle = dumb.handle;
    if (call(fd, DRM_IOCTL_MODE_MAP_DUMB, &mapping) < 0) { error("MAP_DUMB"); goto out; }
    map = mmap(NULL, (size_t)dumb.size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, (off_t)mapping.offset);
    if (map == MAP_FAILED) { error("mmap"); goto out; }
    pattern(map, &dumb);
    if (interrupted) { rc = 128 + interrupted; goto out; }
    memset(&set, 0, sizeof(set));
    set.crtc_id = saved.crtc_id; set.fb_id = fb.fb_id;
    set.set_connectors_ptr = (uintptr_t)&selected.connector_id;
    set.count_connectors = 1; set.mode = selected_mode; set.mode_valid = 1;
    rc = 6;
    if (call(fd, DRM_IOCTL_MODE_SETCRTC, &set) < 0) { error("SETCRTC pattern"); goto out; }
    changed = 1;
    printf("PATTERN visible-requested seconds=%u fb=%u pitch=%u bytes=%" PRIu64 "\n",
           seconds, fb.fb_id, dumb.pitch, (uint64_t)dumb.size);
    {
        struct timespec remain = { (time_t)seconds, 0 };
        while (!interrupted && nanosleep(&remain, &remain) < 0) {
            if (errno != EINTR) { error("nanosleep"); goto out; }
        }
    }
    rc = interrupted ? 128 + interrupted : 0;
out:
    if (changed) {
        int restored;
        if (saved.mode_valid && saved.fb_id && nold) {
            saved.set_connectors_ptr = (uintptr_t)old_conns;
            saved.count_connectors = nold;
            restored = call(fd, DRM_IOCTL_MODE_SETCRTC, &saved);
        } else {
            restored = disable_crtc(fd, saved.crtc_id);
        }
        if (restored < 0) {
            error("RESTORE prior CRTC (old framebuffer may have vanished)");
            if (disable_crtc(fd, saved.crtc_id) < 0) error("fallback disable CRTC");
            else puts("RESTORE fallback CRTC disabled");
            rc = 7;
        } else puts("RESTORE prior CRTC restored or previously inactive CRTC disabled");
    }
    /* Keep master through disable/restore, RMFB and dumb-buffer destruction.
     * DROP_MASTER does not restore a display. Android starts only after this
     * process has also closed its DRM fd. close releases master if DROP fails.
     */
    if (map != MAP_FAILED && munmap(map, (size_t)dumb.size) < 0) {
        error("munmap"); rc = 7;
    }
    if (fb.fb_id && call(fd, DRM_IOCTL_MODE_RMFB, &fb.fb_id) < 0) {
        error("RMFB"); rc = 7;
    }
    if (dumb.handle) {
        struct drm_mode_destroy_dumb destroy;
        memset(&destroy, 0, sizeof(destroy)); destroy.handle = dumb.handle;
        if (call(fd, DRM_IOCTL_MODE_DESTROY_DUMB, &destroy) < 0) {
            error("DESTROY_DUMB"); rc = 7;
        }
    }
    if (master) {
        if (call(fd, DRM_IOCTL_DROP_MASTER, NULL) < 0) { error("DROP_MASTER"); rc = 7; }
        else puts("MASTER dropped");
    }
    if (close(fd) < 0) { error("close"); rc = 7; }
    else puts("DRM closed");
    printf("EXIT status=%d\n", rc);
    return rc;
}
