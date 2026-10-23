#!/usr/bin/env python3
"""Run the kernel configfs forced-eject handler against a fake locked LUN."""
from pathlib import Path
import re
import subprocess
import sys

kernel = Path(sys.argv[1]) / 'drivers/usb/gadget/function/f_mass_storage.c'
work = Path(sys.argv[2])
source = kernel.read_text()
assert 'CONFIGFS_ATTR_WO(fsg_lun_opts_, forced_eject);' in source
assert '&fsg_lun_opts_attr_forced_eject,' in source
handler = re.search(r'static ssize_t fsg_lun_opts_forced_eject_store\(.*?^}', source, re.M | re.S).group(0)
stubs = r'''
#include <stdbool.h>
#include <stddef.h>
#include <sys/types.h>
#include <errno.h>
#include <string.h>
#include <assert.h>
struct config_item { struct config_item *ci_parent; };
struct fsg_lun { int removable, prevent_medium_removal, opened, unit_attention_data; };
struct fsg_lun_opts { struct { struct config_item cg_item; } group; struct fsg_lun *lun; };
struct common { int filesem; };
struct fsg_opts { struct common *common; };
static struct fsg_lun lun;
static struct common common;
static struct fsg_opts opts = {&common};
static struct fsg_lun_opts lun_opts = {{{0}},&lun};
static int locks, closes;
#define SS_MEDIUM_NOT_PRESENT 0x3a00
static struct fsg_lun_opts *to_fsg_lun_opts(struct config_item *item) { (void)item; return &lun_opts; }
static struct fsg_opts *to_fsg_opts(struct config_item *item) { (void)item; return &opts; }
static void down_write(int *sem) { (void)sem; assert(locks == 0); locks++; }
static void up_write(int *sem) { (void)sem; assert(locks == 1); locks--; }
static int fsg_lun_is_open(struct fsg_lun *l) { assert(locks); return l->opened; }
static void fsg_lun_close(struct fsg_lun *l) { assert(locks); l->opened = 0; closes++; }
'''
tests = r'''
int main(void) {
    lun = (struct fsg_lun){1,1,1,0};
    assert(fsg_lun_opts_forced_eject_store(0,"",0) == 0);
    assert(lun.opened && lun.prevent_medium_removal && !closes && !locks);
    assert(fsg_lun_opts_forced_eject_store(0,"1",1) == 1);
    assert(!lun.opened && !lun.prevent_medium_removal && closes == 1 && !locks);
    assert(lun.unit_attention_data == SS_MEDIUM_NOT_PRESENT);
    assert(fsg_lun_opts_forced_eject_store(0,"true",4) == 4 && closes == 1 && !locks);
    lun = (struct fsg_lun){0,1,1,0};
    assert(fsg_lun_opts_forced_eject_store(0,"1",1) == -EINVAL);
    assert(lun.opened && lun.prevent_medium_removal && !locks && closes == 1);
}
'''
p = work / 'eject.c'
p.write_text(stubs + handler + tests)
subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-Wno-unused-parameter',str(p),'-o',str(work / 'eject')],check=True)
subprocess.run([str(work / 'eject')],check=True)
print('Kernel forced eject: locked removable LUN, medium attention, validation and locking passed')
