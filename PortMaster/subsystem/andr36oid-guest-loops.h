/* Ordinary mount tools run themselves. This helper only owns the lifetime of
 * NEW guest-backed loops; entry-bound Android/APEX/rootfs loops are protected. */
#include <linux/loop.h>
#include <limits.h>
#define GL_MAX 256
#define GL_EXPOSE 64
struct gl_state {
    char root[PATH_MAX], journal[PATH_MAX], boot[80];
    bool protected[GL_MAX], owned[GL_MAX];
    struct loop_info64 info[GL_MAX];
    int lease[GL_MAX];
    bool saw_es;
    long long idle, next_scan;
};
static bool gl_prefix(const char *p, const char *base) {
    size_t n = strlen(base);
    return !strncmp(p, base, n) && p[n] == '/';
}
static void gl_boot(char out[80]) {
    FILE *f = fopen("/proc/sys/kernel/random/boot_id", "re");
    if (!f)
        fatal("loop boot identity");
    if (!fgets(out, 80, f))
        fatal("loop boot identity");
    fclose(f);
    out[strcspn(out, "\n")] = 0;
}
static void gl_init(struct gl_state *s, const char *root, const char *journal) {
    memset(s, 0, sizeof(*s));
    if (strlen(root) >= sizeof(s->root) || (journal && strlen(journal) >= sizeof(s->journal)))
        fatal("loop state path");
    strcpy(s->root, root);
    if (journal)
        strcpy(s->journal, journal);
    for (int n = 0; n < GL_MAX; n++)
        s->lease[n] = -1;
    gl_boot(s->boot);
}
static int gl_open(struct gl_state *s, int n) {
    char p[PATH_MAX];
    int fd;
    snprintf(p, sizeof(p), "/dev/block/loop%d", n);
    fd = open(p, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd >= 0)
        return fd;
    snprintf(p, sizeof(p), "/dev/loop%d", n);
    fd = open(p, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd >= 0)
        return fd;
    if (snprintf(p, sizeof(p), "%s/dev/loop%d", s->root, n) >= (int)sizeof(p)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    return open(p, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
}
static bool gl_backing(int n, char *out, size_t size) {
    char p[80];
    snprintf(p, sizeof(p), "/sys/block/loop%d/loop/backing_file", n);
    FILE *f = fopen(p, "re");
    if (!f)
        return false;
    bool ok = fgets(out, size, f) != NULL;
    fclose(f);
    if (ok)
        out[strcspn(out, "\n")] = 0;
    return ok && out[0];
}
static bool gl_same(const struct loop_info64 *a, const struct loop_info64 *b) {
    return a->lo_device == b->lo_device && a->lo_inode == b->lo_inode &&
           a->lo_offset == b->lo_offset;
}
static bool gl_guest_source(struct gl_state *s, int n, const struct loop_info64 *i) {
    char path[PATH_MAX], name[LO_NAME_SIZE + 1];
    if (gl_backing(n, path, sizeof(path)) && gl_prefix(path, s->root))
        return true;
    memcpy(name, i->lo_file_name, LO_NAME_SIZE);
    name[LO_NAME_SIZE] = 0;
    // For arbitrary short guest paths, prove ownership against the backing
    // inode rather than assuming all loops on the shared card belong to us.
    struct stat st;
    if (name[0] == '/' && snprintf(path, sizeof(path), "%s%s", s->root, name) < (int)sizeof(path) &&
        !stat(path, &st) && (uint64_t)st.st_dev == i->lo_device &&
        (uint64_t)st.st_ino == i->lo_inode)
        return true;
    /* util-linux records a canonical backing filename (truncated only at the
     * END). These are guest paths, not Android /data/apex or /mnt/media_rw paths.
     * This also closes the helper-death-before-first-scan window for normal tools. */
    const char *roots[] = {"/roms", "/roms2", "/tmp", "/run", "/home",
                           "/root", "/opt",   "/usr", "/var", "/media"};
    for (unsigned k = 0; k < sizeof(roots) / sizeof(*roots); k++)
        if (gl_prefix(name, roots[k]))
            return true;
    return false;
}
static int gl_save(struct gl_state *s) {
    if (!s->journal[0])
        return 0;
    char tmp[PATH_MAX], dir[PATH_MAX];
    if (snprintf(tmp, sizeof(tmp), "%s.tmp", s->journal) >= (int)sizeof(tmp))
        return -1;
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0)
        return -1;
    FILE *f = fdopen(fd, "w");
    if (!f) {
        close(fd);
        return -1;
    }
    fprintf(f, "A36LOOPS1 %s\n", s->boot);
    for (int n = 0; n < GL_MAX; n++) {
        if (s->protected[n])
            fprintf(f, "B %d\n", n);
        if (s->owned[n])
            fprintf(f, "L %d %llu %llu %llu\n", n, (unsigned long long)s->info[n].lo_device,
                    (unsigned long long)s->info[n].lo_inode,
                    (unsigned long long)s->info[n].lo_offset);
    }
    int bad = ferror(f) || fflush(f) || fsync(fd);
    if (fclose(f))
        bad = 1;
    if (bad || rename(tmp, s->journal))
        return -1;
    strcpy(dir, s->journal);
    char *slash = strrchr(dir, '/');
    if (!slash)
        return -1;
    *slash = 0;
    fd = open(dir, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0)
        return -1;
    int rc = fsync(fd);
    close(fd);
    return rc;
}
static int gl_recover(const char *root, const char *journal);
static int gl_start(struct gl_state *s, const char *root, const char *journal) {
    gl_init(s, root, journal);
    char backing[PATH_MAX], p[PATH_MAX];
    if (journal && journal[0] && !access(journal, F_OK)) {
        if (gl_recover(root, journal))
            return -1;
        gl_init(s, root, journal);
    }
    for (int n = 0; n < GL_MAX; n++)
        s->protected[n] = gl_backing(n, backing, sizeof(backing));
    if (snprintf(p, sizeof(p), "%s/dev", root) >= (int)sizeof(p))
        return -1;
    if (mkdir(p, 0755) && errno != EEXIST)
        return -1;
    if (gl_save(s))
        return -1; // The baseline is durable BEFORE any payload runs.
    for (int n = 0; n < GL_EXPOSE; n++) {
        if (snprintf(p, sizeof(p), "%s/dev/loop%d", root, n) >= (int)sizeof(p))
            return -1;
        if (unlink(p) && errno != ENOENT)
            return -1;
        if (!s->protected[n] && mknod(p, S_IFBLK | 0660, makedev(7, n)))
            return -1;
    }
    if (snprintf(p, sizeof(p), "%s/dev/loop-control", root) >= (int)sizeof(p))
        return -1;
    if (mknod(p, S_IFCHR | 0660, makedev(10, 237)) && errno != EEXIST)
        return -1;
    return 0;
}
static int gl_observe(struct gl_state *s) {
    bool changed = false;
    char backing[PATH_MAX];
    for (int n = 0; n < GL_EXPOSE; n++) {
        if (s->protected[n])
            continue;
        if (!gl_backing(n, backing, sizeof(backing))) {
            if (s->owned[n]) {
                if (s->lease[n] >= 0)
                    close(s->lease[n]);
                s->lease[n] = -1;
                s->owned[n] = false;
                changed = true;
            }
            continue;
        }
        int fd = gl_open(s, n);
        if (fd < 0)
            continue;
        struct loop_info64 info;
        if (ioctl(fd, LOOP_GET_STATUS64, &info)) {
            close(fd);
            continue;
        }
        if (s->owned[n] && !gl_same(&s->info[n], &info)) {
            if (s->lease[n] >= 0)
                close(s->lease[n]);
            s->lease[n] = -1;
            s->owned[n] = false;
            changed = true;
        }
        if (!s->owned[n] && !gl_guest_source(s, n, &info)) {
            close(fd);
            continue;
        }
        if (!s->owned[n]) {
            s->info[n] = info;
            s->owned[n] = true;
            if (gl_save(s)) {
                close(fd);
                return -1;
            }
            changed = false;
        }
        if (s->lease[n] < 0 && !(info.lo_flags & LO_FLAGS_AUTOCLEAR)) {
            info.lo_flags |= LO_FLAGS_AUTOCLEAR;
            if (ioctl(fd, LOOP_SET_STATUS64, &info)) {
                close(fd);
                return -1;
            }
            s->lease[n] = fd; // Keep losetup's image alive until the port mounts it.
        } else
            close(fd);
    }
    return changed ? gl_save(s) : 0;
}
static void gl_unescape(char *p) {
    char *w = p;
    for (char *r = p; *r; r++) {
        if (r[0] == '\\' && r[1] >= '0' && r[1] <= '7' && r[2] >= '0' && r[2] <= '7' &&
            r[3] >= '0' && r[3] <= '7') {
            *w++ = (char)((r[1] - '0') * 64 + (r[2] - '0') * 8 + r[3] - '0');
            r += 3;
        } else
            *w++ = *r;
    }
    *w = 0;
}
static int gl_unmount(struct gl_state *s, int n) {
    FILE *f = fopen("/proc/self/mountinfo", "re");
    if (!f)
        return -1;
    char line[16384], paths[128][PATH_MAX];
    unsigned count = 0;
    while (fgets(line, sizeof(line), f)) {
        unsigned major_num, minor_num;
        char mp[PATH_MAX];
        if (sscanf(line, "%*u %*u %u:%u %*s %4095s", &major_num, &minor_num, mp) != 3)
            continue;
        char *sep = strstr(line, " - ");
        bool source = false;
        if (sep) {
            char type[80], dev[PATH_MAX], want[32];
            if (sscanf(sep + 3, "%79s %4095s", type, dev) == 2) {
                snprintf(want, sizeof(want), "loop%d", n);
                char *base = strrchr(dev, '/');
                source = base && !strcmp(base + 1, want);
            }
        }
        gl_unescape(mp);
        if (((major_num == 7 && minor_num == (unsigned)n) || source) && gl_prefix(mp, s->root) &&
            count < 128)
            strcpy(paths[count++], mp);
    }
    fclose(f);
    int failed = 0;
    while (count) {
        if (umount2(paths[--count], MNT_DETACH) && errno != EINVAL && errno != ENOENT)
            failed = 1;
    }
    return failed ? -1 : 0;
}
static int gl_release(struct gl_state *s, bool private_ns) {
    int failed = 0;
    bool any = false;
    for (int n = 0; n < GL_MAX; n++)
        if (s->owned[n])
            any = true;
    if (!any)
        return 0; // No idle-time journal writes or fsyncs.
    for (int n = 0; n < GL_MAX; n++) {
        if (!s->owned[n] || s->protected[n])
            continue;
        int fd = gl_open(s, n);
        struct loop_info64 info;
        if (fd < 0) {
            failed = 1;
            continue;
        }
        if (ioctl(fd, LOOP_GET_STATUS64, &info)) {
            int e = errno;
            close(fd);
            if (e != ENXIO)
                failed = 1;
            else {
                s->owned[n] = false;
                if (s->lease[n] >= 0)
                    close(s->lease[n]);
                s->lease[n] = -1;
            }
            continue;
        }
        if (!gl_same(&info, &s->info[n])) {
            close(fd);
            if (s->lease[n] >= 0)
                close(s->lease[n]);
            s->lease[n] = -1;
            s->owned[n] = false;
            continue;
        } // Recycled: NEVER detach it.
        if (private_ns && gl_unmount(s, n)) {
            close(fd);
            failed = 1;
            continue;
        }
        if (s->lease[n] >= 0) {
            close(s->lease[n]);
            s->lease[n] = -1;
        }
        if (ioctl(fd, LOOP_CLR_FD, 0) && errno != ENXIO)
            failed = 1;
        close(fd); // AUTOCLEAR completes when the final old mmap/FD closes.
    }
    if (gl_save(s))
        failed = 1;
    return failed ? -1 : 0;
}
static bool gl_descendant(pid_t p, pid_t init) {
    for (int d = 0; p > 1 && d < 64; d++) {
        if (p == init)
            return true;
        p = parent_of(p);
    }
    return false;
}
static bool gl_port_busy(struct gl_state *s, pid_t init) {
    DIR *d = opendir("/proc");
    if (!d)
        return true;
    struct dirent *e;
    pid_t es = 0;
    while ((e = readdir(d))) {
        pid_t p = atoi(e->d_name);
        if (p > 1 && is_emulationstation(p) && gl_descendant(p, init)) {
            es = p;
            break;
        }
    }
    if (!es) {
        closedir(d);
        return !s->saw_es;
    }
    s->saw_es = true;
    rewinddir(d);
    bool busy = false;
    while ((e = readdir(d))) {
        pid_t p = atoi(e->d_name);
        if (p > 1 && parent_of(p) == es && !process_named(p, "gptokeyb") &&
            !process_named(p, "gptokeyb2")) {
            busy = true;
            break;
        }
    }
    closedir(d);
    return busy;
}
static int gl_tick(struct gl_state *s, pid_t init) {
    long long now = now_ms();
    if (now < s->next_scan)
        return 0;
    s->next_scan = now + 500;
    if (gl_observe(s))
        return -1;
    if (gl_port_busy(s, init))
        s->idle = 0;
    else {
        if (!s->idle)
            s->idle = now_ms();
        if (now_ms() - s->idle > 300 && gl_release(s, true))
            return -1;
    }
    return 0;
}
static int gl_finish(struct gl_state *s, bool private_ns) {
    int rc = gl_observe(s);
    if (gl_release(s, private_ns))
        rc = -1;
    for (int n = 0; n < GL_MAX; n++) {
        if (s->lease[n] >= 0)
            close(s->lease[n]);
        s->lease[n] = -1;
    }
    // Keep the journal until retirement is proven, including delayed VFS puts.
    for (int k = 0; k < 30; k++) {
        bool left = false;
        for (int n = 0; n < GL_MAX; n++)
            if (s->owned[n]) {
                int fd = gl_open(s, n);
                struct loop_info64 i;
                if (fd >= 0) {
                    if (!ioctl(fd, LOOP_GET_STATUS64, &i) && gl_same(&i, &s->info[n]))
                        left = true;
                    close(fd);
                }
            }
        if (!left) {
            if (!rc && s->journal[0])
                unlink(s->journal);
            return rc;
        }
        usleep(100000);
    }
    return -1;
}
static int gl_recover(const char *root, const char *journal) {
    struct gl_state s;
    gl_init(&s, root, journal);
    FILE *f = fopen(journal, "re");
    if (!f)
        return errno == ENOENT ? 0 : 1;
    char line[256], boot[80];
    if (!fgets(line, sizeof(line), f) || sscanf(line, "A36LOOPS1 %79s", boot) != 1) {
        fclose(f);
        return 1;
    }
    if (strcmp(boot, s.boot)) {
        fclose(f);
        return unlink(journal) && errno != ENOENT ? 1 : 0;
    }
    while (fgets(line, sizeof(line), f)) {
        int n;
        unsigned long long dev, ino, off;
        if (sscanf(line, "B %d", &n) == 1) {
            if (n < 0 || n >= GL_MAX) {
                fclose(f);
                return 1;
            }
            s.protected[n] = true;
        } else if (sscanf(line, "L %d %llu %llu %llu", &n, &dev, &ino, &off) == 4) {
            if (n < 0 || n >= GL_MAX) {
                fclose(f);
                return 1;
            }
            s.owned[n] = true;
            s.info[n].lo_device = dev;
            s.info[n].lo_inode = ino;
            s.info[n].lo_offset = off;
        } else {
            fclose(f);
            return 1;
        }
    }
    fclose(f);
    return gl_finish(&s, false) ? 1 : 0;
}
