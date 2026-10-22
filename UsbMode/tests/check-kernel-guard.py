#!/usr/bin/env python3
# Compile the real guard and unlock store against small block-layer stubs.
import pathlib
import subprocess
import sys

kernel, scratch = map(pathlib.Path, sys.argv[1:])
source = (kernel / 'drivers/mmc/core/block.c').read_text()


def function(name):
    end = source.index('\n}', source.index(name + '(')) + 2
    start = source.rfind('\nstatic ', 0, source.index(name + '(')) + 1
    return source[start:end]


code = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>
#define ANDR36OID_LOADER_END 32768
#define MMC_BLK_DATA_AREA_BOOT 2
#define CCC_BLOCK_WRITE 16
struct node { bool builtin, ro; } node;
struct host { struct node *of_node; } host = { &node };
struct mmc_card { struct host *host; unsigned raw_cid[4]; bool hard_ro;
    struct { int cmdclass; } csd; } card = { .host = &host, .csd = {16} };
struct disk { bool ro; char *disk_name; } disks[3];
struct mmc_blk_data { struct { struct mmc_card *card; } queue;
    unsigned part_type, area_type; int read_only;
    struct disk *disk; struct mmc_blk_data *part, *next; } md, boot0, boot1;
struct request { int op; unsigned long sector; };
struct device {};
struct device_attribute {};
enum { REQ_OP_READ, REQ_OP_FLUSH, REQ_OP_DRV_IN, REQ_OP_DRV_OUT,
    REQ_OP_WRITE, REQ_OP_DISCARD, REQ_OP_WRITE_ZEROES };
#define req_op(r) ((r)->op)
#define blk_rq_pos(r) ((r)->sector)
#define mmc_dev(h) (h)
#define mmc_card_readonly(c) ((c)->hard_ro)
#define pr_info(...) ((void)0)
#define pr_warn(...) ((void)0)
#define dev_to_disk(d) (&disks[0])
#define list_for_each_entry(p, head, member) for (p=*(head); p; p=p->next)
static bool of_property_read_bool(struct node *n, const char *key) {
    return !strcmp(key, "andr36oid,builtin-emmc") ? n->builtin : n->ro;
}
static bool sysfs_streq(const char *a, const char *b) { return !strcmp(a,b); }
static struct mmc_blk_data *mmc_blk_get(struct disk *d) { return &md; }
static void mmc_blk_put(struct mmc_blk_data *m) {}
static void set_disk_ro(struct disk *d, bool ro) { d->ro = ro; }
'''
code += source[source.index('static bool andr36oid_unlocked;'):
               source.index('static inline int mmc_blk_readonly(struct mmc_card *card);')]
# The definition occurs after the prototype in this kernel.
start = source.index('static inline int mmc_blk_readonly(struct mmc_card *card)\n{')
code += source[start:source.index('\n}', start) + 2]
code += '\n' + function('andr36oid_unlock_store')
code += r'''
int main(void) {
    int checks = 0;
    md.queue.card = &card;
    md.disk = &disks[0]; md.part = &boot0;
    boot0.disk = &disks[1]; boot0.next = &boot1;
    boot1.disk = &disks[2]; boot0.area_type = boot1.area_type = MMC_BLK_DATA_AREA_BOOT;
    for (int builtin = 0; builtin < 2; builtin++)
    for (int sd = 0; sd < 2; sd++)
    for (int unlocked = 0; unlocked < 2; unlocked++)
    for (int part = 0; part < 3; part++)
    for (int op = 0; op < 7; op++)
    for (int pos = 0; pos < 3; pos++) {
        if (unlocked && !sd) continue; /* impossible: store refuses it */
        node.builtin = builtin; node.ro = sd; andr36oid_unlocked = unlocked;
        md.part_type = part; md.area_type = part == 1 ? MMC_BLK_DATA_AREA_BOOT : 0;
        struct request req = { op, pos == 0 ? 0 : pos == 1 ? 32767 : 32768 };
        bool allowed;
        if (!builtin) allowed = true;
        else if (op == REQ_OP_READ || op == REQ_OP_FLUSH || op == REQ_OP_DRV_IN) allowed = true;
        else if (op == REQ_OP_DRV_OUT) allowed = false;
        else if (part == 2) allowed = false; /* other hardware areas keep their guard */
        else if (part == 1) allowed = sd && unlocked;
        else if (sd) allowed = unlocked;
        else allowed = pos == 2;
        assert(mmc_blk_andr36oid_refused(&md, &req) == !allowed);
        checks++;
    }
    node.builtin = true; node.ro = true; andr36oid_unlocked = false;
    md.part_type = 0;
    const char *unlock = "unlock 00000000000000000000000000000000";
    assert(andr36oid_unlock_store(NULL, NULL, unlock, strlen(unlock)) == (ssize_t)strlen(unlock));
    assert(andr36oid_unlocked && !md.read_only && !disks[0].ro);
    assert(!boot0.read_only && !boot1.read_only && disks[1].ro && disks[2].ro);
    assert(andr36oid_unlock_store(NULL, NULL, "lock", 4) == 4);
    assert(!andr36oid_unlocked && md.read_only && disks[0].ro);
    assert(boot0.read_only && boot1.read_only && disks[1].ro && disks[2].ro);
    assert(andr36oid_unlock_store(NULL, NULL, "unlock wrong", 12) == -EINVAL);
    node.ro = false;
    assert(andr36oid_unlock_store(NULL, NULL, unlock, strlen(unlock)) == -EPERM);
    assert(!andr36oid_unlocked);
    printf("%d guard cases and 9 unlock/boot protection checks passed\n", checks);
}
'''
scratch.mkdir(parents=True, exist_ok=True)
(scratch / 'guard.c').write_text(code)
subprocess.run(['cc', '-std=c99', '-Wall', '-Wextra', '-Wno-unused-parameter', '-Werror',
                str(scratch / 'guard.c'), '-o', str(scratch / 'guard')], check=True)
subprocess.run([str(scratch / 'guard')], check=True)
