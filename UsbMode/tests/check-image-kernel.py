#!/usr/bin/env python3
"""Compile the actual backing-file size code with fake kernel I/O, never a kernel build."""
import pathlib
import re
import subprocess
import sys

kernel = pathlib.Path(sys.argv[1]) / "drivers/usb/gadget/function"
work = pathlib.Path(sys.argv[2])
work.mkdir(parents=True, exist_ok=True)
gadget = (kernel / "f_mass_storage.c").read_text()
assert "CONFIGFS_ATTR(fsg_lun_opts_, cdrom);" in gadget
assert "&fsg_lun_opts_attr_cdrom," in gadget
source = (kernel / "storage_common.c").read_text()
function = re.search(r"int fsg_lun_open\(.*?^}", source, re.M | re.S).group(0)

stubs = r'''
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>

typedef int64_t loff_t;
#define O_RDONLY 0
#define O_RDWR 2
#define O_LARGEFILE 0100000
#define FMODE_WRITE 1
#define FMODE_CAN_READ 2
#define FMODE_CAN_WRITE 4
#define S_ISREG(mode) ((mode) == 1)
#define S_ISBLK(mode) ((mode) == 2)
#define PTR_ERR(p) ((intptr_t)(p))
#define IS_ERR(p) (PTR_ERR(p) < 0)
#define ETOOSMALL 525
#define LINFO(...) ((void)0)
#define LDBG(...) ((void)0)

struct inode;
struct address_space { struct inode *host; };
struct inode { int i_mode; struct address_space *i_mapping; void *i_bdev; };
struct file { int f_mode; };
struct fsg_lun {
    int initially_ro, ro, cdrom;
    unsigned int blksize, blkbits;
    struct file *filp;
    loff_t file_length, num_sectors;
};
static loff_t test_size;
static int open_flags;
static struct address_space mapping;
static struct inode backing = {1, &mapping, NULL};
static struct file backing_file = {FMODE_WRITE | FMODE_CAN_READ | FMODE_CAN_WRITE};
static struct file *filp_open(const char *name, int flags, int mode) {
    (void)name; (void)mode; open_flags = flags; return &backing_file;
}
static struct inode *file_inode(struct file *file) { (void)file; return &backing; }
static loff_t i_size_read(struct inode *inode) { (void)inode; return test_size; }
static unsigned int bdev_logical_block_size(void *bdev) { (void)bdev; return 512; }
static unsigned int blksize_bits(unsigned int size) { (void)size; return 9; }
static int fsg_lun_is_open(struct fsg_lun *lun) { return lun->filp != NULL; }
static void fsg_lun_close(struct fsg_lun *lun) { lun->filp = NULL; }
static void fput(struct file *file) { (void)file; }
'''
checks = r'''
int main(void) {
    const struct { loff_t size, disk_blocks, cd_blocks; } cases[] = {
        {614400, 1200, 300},
        {2359293952LL, 4607996, 1151999},
        {3221225472LL, 6291456, 1151999},
        {4294967296LL, 8388608, 1151999},
        {5368709120LL, 10485760, 1151999}
    };
    unsigned int checks = 0;
    mapping.host = &backing;
    for (unsigned int i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        for (int cdrom = 0; cdrom <= 1; cdrom++) {
            struct fsg_lun lun = {.initially_ro = cdrom, .cdrom = cdrom};
            test_size = cases[i].size;
            if (fsg_lun_open(&lun, "test.iso") != 0) return 1;
            loff_t blocks = cdrom ? cases[i].cd_blocks : cases[i].disk_blocks;
            if (!(open_flags & O_LARGEFILE) || lun.file_length != test_size ||
                    lun.num_sectors != blocks || lun.blksize != (cdrom ? 2048U : 512U) ||
                    lun.ro != cdrom) return 2;
            checks++;
        }
    }
    printf("%u kernel image-size checks and cdrom configfs attribute passed\n", checks);
    return 0;
}
'''
harness = work / "image-kernel.c"
harness.write_text(stubs + function + checks)
binary = work / "image-kernel"
subprocess.run(["cc", "-std=c99", "-Wall", "-Wextra", "-Werror", str(harness), "-o", str(binary)], check=True)
subprocess.run([str(binary)], check=True)
