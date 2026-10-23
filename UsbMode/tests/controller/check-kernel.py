#!/usr/bin/env python3
# Compile the actual FunctionFS parser and endpoint rewrite against host USB headers.
from pathlib import Path
import os
import re
import subprocess
import sys

kernel = Path(os.environ.get('CONTROLLER_KERNEL', Path(__file__).resolve().parents[4] / 'xinput-kernel'))
source = (kernel / 'drivers/usb/gadget/function/f_fs.c').read_text()
def function(name):
    match = re.search(r'static [^{;]+\b' + name + r'\([^;]+?\n\{', source)
    assert match, name
    start = match.start()
    pos = match.end()
    depth = 1
    while depth:
        if source[pos] == '{': depth += 1
        if source[pos] == '}': depth -= 1
        pos += 1
    return source[start:pos]

text = r'''
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <assert.h>
#include <linux/usb/ch9.h>
typedef uint8_t u8;
#define __must_check
#define ENTER()
#define pr_vdebug(...) ((void)0)
#define pr_debug(...) ((void)0)
#define unlikely(x) (x)
#define ARRAY_SIZE(a) (sizeof(a)/sizeof((a)[0]))
#define HID_DT_HID 0x21
struct hid_descriptor { u8 bytes[9]; };
enum ffs_entity_type {FFS_DESCRIPTOR,FFS_INTERFACE,FFS_STRING,FFS_ENDPOINT};
typedef int (*ffs_entity_callback)(enum ffs_entity_type,u8*,struct usb_descriptor_header*,void*);
struct usb_string { int id; };
struct tab { struct usb_string *strings; };
struct ffs_data { u8 eps_addrmap[15]; struct tab **stringtabs; };
struct ffs_ep { void *ep; struct usb_endpoint_descriptor *descs[3]; };
struct ffs_function { struct ffs_data *ffs; int interfaces_nums[4]; struct ffs_ep eps[2]; void *conf; int function; };
static int usb_interface_id(void *conf, int *func) { (void)conf; (void)func; return 0; }
static int accept(enum ffs_entity_type type,u8 *p,struct usb_descriptor_header *d,void *priv) {
    (void)type; (void)p; (void)d; (void)priv; return 0;
}
'''
for name in ('ffs_xinput_desc', 'ffs_do_single_desc', 'ffs_ep_addr2idx', '__ffs_func_bind_do_nums'):
    text += function(name) + '\n'
text += r'''
int main(void) {
    u8 gamepad[] = {17,0x21,0,1,1,0x25,0x81,0x14,0,0,0,0,0x13,2,8,0,0};
    u8 hid[9] = {9,0x21};
    assert(ffs_do_single_desc((char*)gamepad,17,accept,NULL)==17);
    assert(ffs_do_single_desc((char*)gamepad,16,accept,NULL)==-EINVAL);
    assert(ffs_do_single_desc((char*)hid,9,accept,NULL)==9);
    for (unsigned i=2;i<17;i++) {
        if (i==6 || i==13) continue;
        gamepad[i] ^= 0xff;
        assert(ffs_do_single_desc((char*)gamepad,17,accept,NULL)==-EINVAL);
        gamepad[i] ^= 0xff;
    }
    struct ffs_data data = {.eps_addrmap={0,0x81,2}};
    struct usb_endpoint_descriptor in = {.bEndpointAddress=0x83}, out = {.bEndpointAddress=4};
    struct ffs_function func = {.ffs=&data};
    func.eps[0].ep = &in; func.eps[0].descs[0] = &in;
    func.eps[1].ep = &out; func.eps[1].descs[0] = &out;
    assert(__ffs_func_bind_do_nums(FFS_DESCRIPTOR,NULL,(void*)gamepad,&func)==0);
    assert(gamepad[6]==0x83 && gamepad[13]==4);
    assert(ffs_do_single_desc((char*)gamepad,17,accept,NULL)==17);
    assert(__ffs_func_bind_do_nums(FFS_DESCRIPTOR,NULL,NULL,&func)==0);
    puts("Kernel FunctionFS: HID, XUSB validation and relocated endpoint references passed");
}
'''
target = Path(sys.argv[1]) / 'kernel-check.c'
target.write_text(text)
subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-Wno-sign-compare', str(target), '-o', str(target.with_suffix(''))], check=True)
subprocess.run([str(target.with_suffix(''))], check=True)
assert 'CONFIG_USB_CONFIGFS_F_HID=y' in (kernel / 'arch/arm64/configs/lineageos_r36s_defconfig').read_text()
