#!/usr/bin/env python3
# Compile the actual FunctionFS parser and endpoint rewrite against host USB headers.
from pathlib import Path
import os
import re
import subprocess
import sys

kernel = Path(os.environ.get('CONTROLLER_KERNEL', Path(__file__).resolve().parents[4] / 'xinput-kernel'))
source = Path(os.environ.get('CONTROLLER_FFS', kernel / 'drivers/usb/gadget/function/f_fs.c')).read_text()
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
#include <linux/usb/functionfs.h>
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
enum ffs_os_desc_type { FFS_OS_DESC_EXT_COMPAT, FFS_OS_DESC_EXT_PROP };
#define le32_to_cpu(x) (x)
#define le16_to_cpu(x) (x)
#define USB_EXT_PROP_UNICODE 1
#define USB_EXT_PROP_UNICODE_ENV 2
#define USB_EXT_PROP_UNICODE_LINK 6
#define USB_EXT_PROP_UNICODE_MULTI 7
struct list_head { int unused; };
struct usb_os_desc_ext_prop { unsigned type, name_len, data_len; char *data, *name; struct list_head entry; };
struct usb_os_desc { char ext_compat_id[16]; unsigned ext_prop_len, ext_prop_count; struct list_head ext_prop; };
struct usb_os_desc_table { int if_id; struct usb_os_desc *os_desc; };
struct usb_function { struct usb_os_desc_table *os_desc_table; };
static void list_add_tail(struct list_head *a, struct list_head *b) { (void)a; (void)b; }
static void *usb_ext_prop_data_len_ptr(void *p, unsigned n) { return (char*)p + n; }
static void *usb_ext_prop_data_ptr(void *p, unsigned n) { return (char*)p + n; }
static void *usb_ext_prop_name_ptr(void *p) { return p; }
struct ffs_data { u8 eps_addrmap[15]; struct tab **stringtabs;
    void *ms_os_descs_ext_prop_avail; char *ms_os_descs_ext_prop_name_avail, *ms_os_descs_ext_prop_data_avail; };
struct ffs_ep { void *ep; struct usb_endpoint_descriptor *descs[3]; };
struct ffs_function { struct ffs_data *ffs; int interfaces_nums[4]; struct ffs_ep eps[2]; void *conf; struct usb_function function; };
static int usb_interface_id(void *conf, void *func) { (void)conf; (void)func; return 0; }
static int accept(enum ffs_entity_type type,u8 *p,struct usb_descriptor_header *d,void *priv) {
    (void)type; (void)p; (void)d; (void)priv; return 0;
}
'''
for name in ('ffs_xinput_desc', 'ffs_do_single_desc', 'ffs_ep_addr2idx', '__ffs_func_bind_do_nums', '__ffs_func_bind_do_os_desc'):
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
    struct usb_os_desc os = {0};
    struct usb_os_desc_table table = {.os_desc=&os};
    struct usb_ext_compat_desc compat = {.bFirstInterfaceNumber=0, .CompatibleID="XUSB10"};
    func.function.os_desc_table = &table;
    for (int interface = 0; interface < 6; ++interface) {
        func.interfaces_nums[0] = interface;
        assert(__ffs_func_bind_do_os_desc(FFS_OS_DESC_EXT_COMPAT,NULL,&compat,sizeof(compat),&func)==sizeof(compat));
        assert(table.if_id == interface && memcmp(os.ext_compat_id,"XUSB10",6)==0);
    }
    puts("Kernel FunctionFS: HID, relocated XUSB endpoints and per-interface XUSB10 OS descriptors passed");
}
'''
if os.environ.get('FLIPPER_CDC_TEST'):
    text = text.replace('int main(void) {', r'''int main(void) {
    u8 union_desc[] = {5,0x24,6,0,1}, call[] = {5,0x24,1,0,1};
    struct ffs_function cdc = {.interfaces_nums={4,5,6,7}};
    assert(ffs_do_single_desc((char*)union_desc,5,__ffs_func_bind_do_nums,&cdc)==5);
    assert(union_desc[3]==4 && union_desc[4]==5);
    assert(ffs_do_single_desc((char*)call,5,__ffs_func_bind_do_nums,&cdc)==5 && call[4]==5);
    u8 iad[] = {8,11,0,2,2,2,1,0};
    assert(ffs_do_single_desc((char*)iad,8,__ffs_func_bind_do_nums,&cdc)==8 && iad[2]==4);
    u8 bad_cdc[] = {4,0x24,6,0};
    assert(ffs_do_single_desc((char*)bad_cdc,4,accept,NULL)==-EINVAL);
    for (int dual=0; dual<2; dual++) {
        char path[4096]; snprintf(path,sizeof(path),"%s/cdc-%s",getenv("TMPDIR"),dual ? "dual" : "single");
        FILE *f=fopen(path,"rb"); assert(f);
        u8 bytes[512]; size_t size=fread(bytes,1,sizeof(bytes),f); fclose(f);
        size_t offset=20;
        for (unsigned i=0; i<(dual ? 40 : 20); i++) {
            assert(offset<size); int length=bytes[offset];
            assert(ffs_do_single_desc((char*)bytes+offset,size-offset,accept,NULL)==length);
            offset+=length;
        }
        assert(offset==size);
    }
''')
    text = '#include <stdlib.h>\n' + text

target = Path(sys.argv[1]) / 'kernel-check.c'
target.write_text(text)
subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-Wno-sign-compare', '-Wno-unused-parameter', str(target), '-o', str(target.with_suffix(''))], check=True)
subprocess.run([str(target.with_suffix(''))], check=True)
assert 'CONFIG_USB_CONFIGFS_F_HID=y' in (kernel / 'arch/arm64/configs/lineageos_r36s_defconfig').read_text()

# Compile production boot/report length selection; the userspace write still accepts
# the full report, while the USB request uses the negotiated protocol's length.
hid = (kernel / 'drivers/usb/gadget/function/f_hid.c').read_text()
source = hid
length = function('hidg_report_length')
boot = r"""
#include <assert.h>
#include <stdio.h>
#define USB_INTERFACE_SUBCLASS_BOOT 1
#define USB_INTERFACE_PROTOCOL_MOUSE 2
#define HID_BOOT_PROTOCOL 0
#define min_t(t,a,b) ((t)(a)<(t)(b)?(t)(a):(t)(b))
struct f_hidg { int bInterfaceSubClass, bInterfaceProtocol, protocol; };
""" + length + r"""
int main(void) {
    struct f_hidg mouse = {1,2,0}, keyboard = {1,1,0}, gamepad = {0,0,0};
    assert(hidg_report_length(&mouse,4)==3);
    assert(hidg_report_length(&mouse,2)==2);
    mouse.protocol=1; assert(hidg_report_length(&mouse,4)==4);
    assert(hidg_report_length(&keyboard,8)==8);
    assert(hidg_report_length(&gamepad,11)==11);
    puts("Kernel HID: boot mouse 3/report mouse 4, keyboard 8 and gamepad 11 bytes passed");
}
"""
assert 'req->length   = hidg_report_length(hidg, count);' in hid
assert 'status = count;' in hid
assert 'length = hidg_report_length(hidg,' in hid
assert 'hidg->protocol = value;' in hid and 'hidg->protocol = HID_REPORT_PROTOCOL;' in hid
assert 'if (hidg->use_out_ep)\n\t\t\tgoto stall;' in hid
target = Path(sys.argv[1]) / 'kernel-hid.c'
target.write_text(boot)
subprocess.run(['cc','-std=c99','-Wall','-Wextra','-Werror',str(target),'-o',str(target.with_suffix(''))],check=True)
subprocess.run([str(target.with_suffix(''))],check=True)
# The DT array starts at dedicated FIFO 1; ep0 has a separate 16-word FIFO.
dts = (kernel / 'arch/arm64/boot/dts/rockchip/px30.dtsi').read_text()
assert 'g-tx-fifo-size = <256 128 128 64 32 16>;' in dts
assert 'g-rx-fifo-size = <280>;' in dts and 'g-np-tx-fifo-size = <16>;' in dts
params = (kernel / 'drivers/usb/dwc2/params.c').read_text()
assert '&p->g_tx_fifo_size[1]' in params
print('dwc2 DT FIFO budget and ep0 reservation checked')

config = (kernel / 'arch/arm64/configs/lineageos_r36s_defconfig').read_text()
for option in ('USB_CONFIGFS_NCM', 'USB_CONFIGFS_ACM', 'USB_CONFIGFS_F_UAC2',
               'NET', 'TTY', 'SND', 'SND_PCM', 'SND_RAWMIDI', 'USB_CONFIGFS_F_MIDI',
               'USB_NET_CDC_NCM', 'USB_NET_RNDIS_HOST', 'SND_USB_AUDIO', 'USB_ACM'):
    assert 'CONFIG_' + option + '=y' in config, option
uac = (kernel / 'drivers/usb/gadget/function/f_uac2.c').read_text()
assert 'USB_ENDPOINT_SYNC_ADAPTIVE' in uac
assert 'hs_epout_desc.bInterval' not in uac or 'DIV_ROUND_UP' in uac
assert 'UAC_ATTRIBUTE(c_chmask)' in uac and 'UAC_ATTRIBUTE(p_chmask)' in uac
print('NCM, ACM and UAC2 dependencies, host drivers and adaptive audio OUT verified')
