#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define F2FS_MAGIC 0xf2f52010U
#define F2FS_COMPRESSION 0x2000U
struct comp_option { uint8_t algorithm, log_cluster_size; };
#define GET_COMPRESS_OPTION _IOR(0xf5, 21, struct comp_option)

static int fail(const char *why) { fprintf(stderr, "userdata compression: %s: %s\n", why, strerror(errno)); return 1; }
static int capable(void) {
    char b[32] = {0};
    const char *path = "/sys/fs/f2fs/features/compression";
#ifdef A36_COMPRESSION_TEST
    path = getenv("A36_COMPRESSION_FEATURE");
    if (!path) return 0;
#endif
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, b, sizeof(b)-1); close(fd);
    return n > 0 && !strncmp(b, "supported", 9);
}
static uint32_t le32(const unsigned char *b) { return (uint32_t)b[0] | (uint32_t)b[1]<<8 | (uint32_t)b[2]<<16 | (uint32_t)b[3]<<24; }
static int feature(int fd) {
    unsigned char b[2184]; int found = 0;
    for (int off = 1024; off <= 5120; off += 4096) {
        if (pread(fd, b, sizeof(b), off) != sizeof(b)) return -1;
        if (le32(b) != F2FS_MAGIC) return -1;
        if ((le32(b+2180) & (F2FS_COMPRESSION | 8U)) != (F2FS_COMPRESSION | 8U)) return 0;
        ++found;
    }
    return found == 2 ? 1 : -1;
}
static int format_blank(const char *device) {
    int fd = open(device, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    // Resolve only the caller's by-name symlink, then validate the actual block
    // object's kernel PARTNAME. Never trust its partition number.
    if (fd < 0 && errno == ELOOP) {
        char *real = realpath(device, NULL); if (!real) return fail("resolve userdata");
        fd = open(real, O_RDONLY | O_CLOEXEC | O_NOFOLLOW); free(real);
    }
    if (fd < 0) return fail("open userdata");
    struct stat st; if (fstat(fd, &st)) { close(fd); return fail("stat userdata"); }
    char uevent[256], text[2048] = {0};
#ifdef A36_COMPRESSION_TEST
    const char *event = getenv("A36_COMPRESSION_UEVENT");
    if (!event) { close(fd); return fail("missing test uevent"); }
    snprintf(uevent, sizeof(uevent), "%s", event);
#else
    if (!S_ISBLK(st.st_mode)) { close(fd); errno = EINVAL; return fail("userdata is not a block device"); }
    snprintf(uevent, sizeof(uevent), "/sys/dev/block/%u:%u/uevent", major(st.st_rdev), minor(st.st_rdev));
#endif
    int u = open(uevent, O_RDONLY | O_CLOEXEC);
    ssize_t n = u < 0 ? -1 : read(u, text, sizeof(text)-1); if (u >= 0) close(u);
    if (n <= 0 || (!strstr(text, "\nPARTNAME=userdata\n") && strncmp(text, "PARTNAME=userdata\n", 18))) {
        close(fd); errno = EINVAL; return fail("PARTNAME is not userdata");
    }
    // An existing filesystem, foreign data, or incomplete nonblank format is
    // never converted/reformatted by this opt-in helper.
    unsigned char block[4096];
    for (off_t off = 0; off < 1048576; off += sizeof(block)) {
        if (pread(fd, block, sizeof(block), off) != sizeof(block)) { close(fd); return fail("read fresh userdata"); }
        for (size_t i=0; i<sizeof(block); ++i) if (block[i]) { close(fd); puts("existing userdata preserved"); return 0; }
    }
    close(fd);
    if (!capable()) { errno = ENOTSUP; return fail("kernel lacks F2FS compression"); }
    const char *mkfs = "/system/bin/make_f2fs";
#ifdef A36_COMPRESSION_TEST
    mkfs = getenv("A36_COMPRESSION_MKFS"); if (!mkfs) return fail("missing test mkfs");
#endif
    pid_t child = fork(); if (child < 0) return fail("fork make_f2fs");
    if (!child) { execl(mkfs, mkfs, "-g", "android", "-O", "extra_attr,compression", device, (char*)NULL); _exit(127); }
    int status; if (waitpid(child, &status, 0) < 0 || !WIFEXITED(status) || WEXITSTATUS(status)) return fail("make_f2fs failed");
    fd = open(device, O_RDONLY | O_CLOEXEC);
    int result = fd < 0 ? -1 : feature(fd); if (fd >= 0) close(fd);
    if (result != 1) { errno = ENOTSUP; return fail("formatted filesystem lacks compression feature"); }
    puts("fresh userdata: F2FS compression=lz4 available"); return 0;
}
static int policy(void) {
    const char *paths[] = {"/data/dalvik-cache", "/data/resource-cache"};
    struct statfs fs;
    if (statfs("/data", &fs)) return fail("statfs data");
    if ((unsigned long)fs.f_type != F2FS_MAGIC) return 0;
    // FS without the compression feature rejects SETFLAGS; it is left alone.
    for (unsigned i=0; i<sizeof(paths)/sizeof(paths[0]); ++i) {
        int fd = open(paths[i], O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) return fail("open cache directory");
        unsigned long flags = 0;
        if (ioctl(fd, FS_IOC_GETFLAGS, &flags)) { close(fd); return fail("get cache flags"); }
        unsigned long original = flags; flags |= FS_COMPR_FL;
        if (ioctl(fd, FS_IOC_SETFLAGS, &flags)) {
            int saved = errno; close(fd);
            if (saved == EOPNOTSUPP) return 0; // existing ordinary F2FS, never convert
            errno = saved; return fail("set cache compression");
        }
        // GET_COMPRESS_OPTION accepts regular files, not directories. An
        // unnamed empty child proves inheritance without leaving a probe file.
        int probe = openat(fd, ".", O_TMPFILE | O_RDWR | O_CLOEXEC, 0600);
        struct comp_option option = {0};
        int bad = probe < 0 || ioctl(probe, GET_COMPRESS_OPTION, &option) ||
                  option.algorithm != 1 || option.log_cluster_size != 2;
        if (probe >= 0) close(probe);
        if (bad) {
            (void)ioctl(fd, FS_IOC_SETFLAGS, &original); close(fd); errno = ENOTSUP;
            return fail("cache default must be LZ4 with 16 KiB clusters");
        }
        close(fd);
    }
    return 0;
}
int main(int argc, char **argv) {
    if (argc == 3 && !strcmp(argv[1], "format-blank")) return format_blank(argv[2]);
    if (argc == 2 && !strcmp(argv[1], "policy")) return policy();
    fprintf(stderr, "usage: userdata-compression format-blank USERDATA | policy\n"); return 2;
}
