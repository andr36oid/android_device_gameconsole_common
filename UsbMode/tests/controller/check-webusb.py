#!/usr/bin/env python3
"""Compile the production BOS, WebUSB/OS dispatch and configfs URL handlers."""
from pathlib import Path
import re
import subprocess
import sys

kernel, work = Path(sys.argv[1]), Path(sys.argv[2])
source = (kernel / 'drivers/usb/gadget/composite.c').read_text()
configfs = (kernel / 'drivers/usb/gadget/configfs.c').read_text()
header = (kernel / 'include/linux/usb/webusb.h').read_text()
uuid = (kernel / 'include/linux/uuid.h').read_text()

def function(text, name):
    return re.search(r'^static [^\n]*\b' + name + r'\(.*?^}', text, re.M | re.S).group(0)

bos = function(source, 'bos_desc')
# Keep both real dispatch branches, including their ordering and recipient checks.
dispatch = source[source.index('\t\tif (cdev->use_os_string &&', source.index('unknown:')):]
dispatch = dispatch[:dispatch.index('\n\t\tVDBG(cdev,')]
device = source[source.index('\t\tcase USB_DT_DEVICE:', source.index('composite_setup(')):]
device = device[:device.index('\t\tcase USB_DT_DEVICE_QUALIFIER:')]
bos_case = source[source.index('\t\tcase USB_DT_BOS:', source.index('composite_setup(')):]
bos_case = bos_case[:bos_case.index('\t\tcase USB_DT_OTG:')]
bind = configfs[configfs.index('\tcdev->use_webusb = gi->use_webusb;'):]
bind = bind[:bind.index('\n\tif (gi->use_os_desc)')]
assert 'landing_page[WEBUSB_URL_RAW_MAX_LENGTH + 1]' in configfs
assert 'landing_page[WEBUSB_URL_RAW_MAX_LENGTH + 1]' in (kernel / 'include/linux/usb/composite.h').read_text()
assert 'gi->bcd_webusb_version = 0x0100;' in configfs
for attr in ('use', 'bcdVersion', 'bVendorCode', 'landingPage'):
    assert 'CONFIGFS_ATTR(webusb_, ' + attr + ');' in configfs
    assert '&webusb_attr_' + attr in configfs

stubs = r'''
#define _DEFAULT_SOURCE
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <string.h>
#include <strings.h>
#include <errno.h>
#include <endian.h>
#include <sys/types.h>
#include <linux/usb/ch9.h>
typedef uint8_t u8;
typedef uint16_t u16;
#define U8_MAX 255
#define __packed __attribute__((packed))
#define cpu_to_le16 htole16
#define cpu_to_le32 htole32
#define le16_to_cpu le16toh
#define le16_add_cpu(p, v) (*(p) = cpu_to_le16(le16_to_cpu(*(p)) + (v)))
#define min(a,b) ((a) < (b) ? (a) : (b))
#define min_t(t,a,b) min((t)(a),(t)(b))
#define USB_DEFAULT_U1_DEV_EXIT_LAT 1
#define USB_DEFAULT_U2_DEV_EXIT_LAT 0x01f4
#define USB_COMP_EP0_OS_DESC_BUFSIZ 4096
#define MAX_CONFIG_INTERFACES 16
#define pr_err(...) ((void)0)
struct usb_dcd_config_params { u8 bU1devExitLat; u16 bU2DevExitLat; };
struct usb_gadget_ops { void (*get_config_params)(struct usb_dcd_config_params *); };
struct ep { unsigned maxpacket; };
struct usb_gadget { int speed, max_speed, lpm_capable; struct usb_gadget_ops *ops; struct ep *ep0; };
struct usb_request { void *buf, *context; void (*complete)(void); };
struct usb_configuration { void *interface[MAX_CONFIG_INTERFACES]; };
static bool gadget_is_superspeed(struct usb_gadget *g) { return g->max_speed >= USB_SPEED_SUPER; }
static bool gadget_is_superspeed_plus(struct usb_gadget *g) { return g->max_speed >= USB_SPEED_SUPER_PLUS; }
static void composite_setup_complete(void) { }
static void put_unaligned_le16(u16 v, void *p) { v = cpu_to_le16(v); memcpy(p, &v, 2); }
static void put_unaligned_le32(uint32_t v, void *p) { v = cpu_to_le32(v); memcpy(p, &v, 4); }
static int count_ext_compat(struct usb_configuration *c) { return 1; }
static int fill_ext_compat(struct usb_configuration *c, u8 *p) { memcpy(p + 18, "XUSB10", 6); return 40; }
static int count_ext_prop(struct usb_configuration *c, int i) { return 0; }
static int len_ext_prop(struct usb_configuration *c, int i) { return 10; }
static int fill_ext_prop(struct usb_configuration *c, int i, u8 *p) { return 10; }
'''
# Compile the actual descriptor definitions, including the UUID initializer.
stubs += uuid[uuid.index('#define UUID_SIZE'):uuid.index('/*\n * The length')]
stubs += header.replace('#include "uapi/linux/usb/ch9.h"', '')
stubs += r'''
struct usb_composite_dev {
    struct usb_gadget *gadget;
    struct usb_request *req, *os_desc_req;
    struct usb_configuration *os_desc_config;
    bool use_webusb, use_os_string;
    u8 b_vendor_code, b_webusb_vendor_code;
    u16 bcd_webusb_version;
    char landing_page[WEBUSB_URL_RAW_MAX_LENGTH + 1];
    struct usb_device_descriptor desc;
};
static int count_configs(struct usb_composite_dev *c, int type) { return 1; }
static struct usb_request *selected;
static int dispatch(struct usb_composite_dev *cdev, const struct usb_ctrlrequest *ctrl) {
    struct usb_request *req = cdev->req;
    u16 w_index = le16_to_cpu(ctrl->wIndex), w_value = le16_to_cpu(ctrl->wValue), w_length = le16_to_cpu(ctrl->wLength);
    int value = -EOPNOTSUPP;
'''
stubs += dispatch + '\n    return -999;\ncheck_value:\n    selected = req; return value;\n}\n'
stubs += bos
stubs += r'''
static int descriptor(struct usb_composite_dev *cdev, int type, u16 w_length) {
    struct usb_gadget *gadget = cdev->gadget;
    struct usb_request *req = cdev->req;
    int value = -EOPNOTSUPP;
    switch (type) {
'''
stubs += device + bos_case + '\n    }\n    return value;\n}\n'
stubs += r'''
struct config_item { int dummy; };
struct gadget_info {
    int lock;
    bool use_webusb;
    u16 bcd_webusb_version;
    u8 b_webusb_vendor_code;
    char landing_page[WEBUSB_URL_RAW_MAX_LENGTH + 1];
};
static struct gadget_info gi;
static struct gadget_info *webusb_item_to_gadget_info(struct config_item *item) { return &gi; }
static void mutex_lock(int *p) { assert(*p == 0); ++*p; }
static void mutex_unlock(int *p) { assert(*p == 1); --*p; }
static int number(const char *p, unsigned long max, unsigned long *out) {
    char *end; errno = 0; *out = strtoul(p, &end, 0);
    return errno || end == p || (*end && strcmp(end, "\n")) || *out > max ? -EINVAL : 0;
}
static int kstrtou16(const char *p, int base, u16 *v) { unsigned long n; int r = number(p, 65535, &n); *v = n; return r; }
static int kstrtou8(const char *p, int base, u8 *v) { unsigned long n; int r = number(p, 255, &n); *v = n; return r; }
static int kstrtobool(const char *p, bool *v) {
    if (!strcmp(p,"1")) { *v = true; return 0; }
    if (!strcmp(p,"0")) { *v = false; return 0; }
    return -EINVAL;
}
'''
stubs += function(configfs, 'is_valid_bcd')
for attr in ('use', 'bcdVersion', 'bVendorCode', 'landingPage'):
    for op in ('show', 'store'):
        stubs += function(configfs, 'webusb_' + attr + '_' + op) + '\n'
stubs += 'static void bind_webusb(struct usb_composite_dev *cdev, struct gadget_info *gi) {\n' + bind + '\n}\n'
tests = r'''
int main(void) {
    u8 buf[4096], os_buf[4096];
    struct usb_request req = { .buf = buf }, os_req = { .buf = os_buf };
    struct usb_gadget_ops ops = {0}; struct ep ep = {64};
    struct usb_gadget gadget = {USB_SPEED_HIGH, USB_SPEED_HIGH, 0, &ops, &ep};
    struct usb_configuration os_config = {0}; os_config.interface[0] = &os_config;
    struct usb_composite_dev cdev = { .gadget = &gadget, .req = &req, .os_desc_req = &os_req,
        .use_webusb = true, .bcd_webusb_version = 0x0100, .b_webusb_vendor_code = 0x30,
        .os_desc_config = &os_config, .use_os_string = true, .b_vendor_code = 1,
        .landing_page = "https://github.com/andr36oid" };
    const u8 expected[] = {5,15,29,0,1,24,16,5,0,0x38,0xb6,8,0x34,0xa9,9,0xa0,0x47,0x8b,0xfd,0xa0,0x76,0x88,0x15,0xb6,0x65,0,1,0x30,1};
    const u8 url[] = {23,3,1,'g','i','t','h','u','b','.','c','o','m','/','a','n','d','r','3','6','o','i','d'};
    assert(sizeof(url) == 23);
    memset(buf, 0xa5, sizeof(buf));
    assert(bos_desc(&cdev) == sizeof(expected) && !memcmp(buf, expected, sizeof(expected)));
    assert(buf[29] == 0xa5);
    assert(descriptor(&cdev, USB_DT_DEVICE, 18) == 18 && le16_to_cpu(cdev.desc.bcdUSB) == 0x0210);
    for (unsigned l = 0; l < 40; l++) {
        assert(descriptor(&cdev, USB_DT_BOS, l) == (int)min(l, 29u));
        struct usb_ctrlrequest ctrl = {0xc0,0x30,cpu_to_le16(1),cpu_to_le16(2),cpu_to_le16(l)};
        memset(buf,0xa5,sizeof(buf));
        assert(dispatch(&cdev,&ctrl) == (int)min(l, 23u) && selected == &req);
        assert(!memcmp(buf,url,sizeof(url)) && buf[23] == 0xa5);
    }
    struct usb_ctrlrequest ctrl = {0xc0,0x30,cpu_to_le16(1),cpu_to_le16(2),cpu_to_le16(65535)};
    assert(dispatch(&cdev,&ctrl) == 23);
    ctrl.wValue = 0; assert(dispatch(&cdev,&ctrl) == -EOPNOTSUPP);
    ctrl.wValue = cpu_to_le16(2); assert(dispatch(&cdev,&ctrl) == -EOPNOTSUPP);
    ctrl.wValue = cpu_to_le16(1); ctrl.wIndex = cpu_to_le16(3); assert(dispatch(&cdev,&ctrl) == -EOPNOTSUPP);
    ctrl.wIndex = cpu_to_le16(2);
    ctrl.bRequestType = 0xc1; assert(dispatch(&cdev,&ctrl) == -999);
    ctrl.bRequestType = 0x40; assert(dispatch(&cdev,&ctrl) == -999);
    ctrl.bRequestType = 0xc0; ctrl.bRequest = 0x31; assert(dispatch(&cdev,&ctrl) == -999);
    ctrl.bRequest = 1; ctrl.wValue = 0; ctrl.wIndex = cpu_to_le16(4); ctrl.wLength = cpu_to_le16(40);
    assert(dispatch(&cdev,&ctrl) == 40 && selected == &os_req && !memcmp(os_buf + 18,"XUSB10",6));
    ctrl.bRequestType = 0xc1; ctrl.wIndex = cpu_to_le16(5); ctrl.wLength = cpu_to_le16(10);
    assert(dispatch(&cdev,&ctrl) == 10 && selected == &os_req);
    cdev.use_webusb = false;
    assert(descriptor(&cdev,USB_DT_DEVICE,18) == 18 && le16_to_cpu(cdev.desc.bcdUSB) == 0x0200);
    assert(descriptor(&cdev,USB_DT_BOS,255) == -EOPNOTSUPP);
    cdev.desc.bcdUSB = cpu_to_le16(0x0201); assert(descriptor(&cdev,USB_DT_BOS,255) == 5);
    gadget.lpm_capable = 1;
    assert(descriptor(&cdev,USB_DT_DEVICE,18) == 18 && le16_to_cpu(cdev.desc.bcdUSB) == 0x0201);
    const u8 extension[] = {5,15,12,0,1,7,16,2,6,0,0,0};
    assert(bos_desc(&cdev) == 12 && !memcmp(buf,extension,12));
    cdev.use_webusb = true; assert(bos_desc(&cdev) == 36 && buf[4] == 2 && !memcmp(buf+12,expected+5,24));
    gadget.max_speed = USB_SPEED_SUPER; gadget.speed = USB_SPEED_SUPER;
    assert(descriptor(&cdev,USB_DT_DEVICE,18) == 18 && le16_to_cpu(cdev.desc.bcdUSB) == 0x0320);
    assert(bos_desc(&cdev) == 46 && buf[4] == 3);
    gadget.max_speed = USB_SPEED_HIGH; gadget.lpm_capable = 0;
    cdev.bcd_webusb_version = 0; cdev.landing_page[0] = 0;
    assert(bos_desc(&cdev) == 29 && buf[25] == 0 && buf[26] == 1 && buf[28] == 0);
    ctrl = (struct usb_ctrlrequest){0xc0,0x30,cpu_to_le16(1),cpu_to_le16(2),cpu_to_le16(255)};
    assert(dispatch(&cdev,&ctrl) == -EOPNOTSUPP);
    char page[512];
    assert(webusb_use_store(0,"1",1) == 1 && webusb_use_show(0,page) == 2 && !strcmp(page,"1\n"));
    assert(webusb_use_store(0,"bad",3) == -EINVAL && gi.use_webusb);
    assert(webusb_bcdVersion_store(0,"0x0100",6) == 6 && gi.bcd_webusb_version == 0x0100);
    assert(webusb_bcdVersion_show(0,page) == 7 && !strcmp(page,"0x0100\n"));
    assert(webusb_bcdVersion_store(0,"0x01a0",6) == -EINVAL && gi.bcd_webusb_version == 0x0100);
    assert(webusb_bVendorCode_store(0,"0x30",4) == 4 && gi.b_webusb_vendor_code == 0x30);
    assert(webusb_bVendorCode_show(0,page) == 5 && !strcmp(page,"0x30\n"));
    assert(webusb_bVendorCode_store(0,"256",3) == -EINVAL && gi.b_webusb_vendor_code == 0x30);
    const char *schemes[] = {"https://","http://","", "HTTPS://"};
    for (unsigned s = 0; s < 4; s++) {
        size_t prefix = strlen(schemes[s]), max = 252 + prefix;
        memcpy(page,schemes[s],prefix); memset(page + prefix,'x',253);
        assert(webusb_landingPage_store(0,page,max+1) == -EINVAL);
        page[max] = '\n';
        assert(webusb_landingPage_store(0,page,max+1) == (ssize_t)max+1);
        assert(strlen(gi.landing_page) == max && !gi.lock);
        bind_webusb(&cdev,&gi);
        for (unsigned l = 0; l < 257; l++) {
            ctrl.wLength = cpu_to_le16(l);
            assert(dispatch(&cdev,&ctrl) == (int)min(l,255u) && buf[0] == 255 && buf[2] == (s == 0 || s == 3 ? 1 : s == 1 ? 0 : 255));
            for (unsigned i = 3; i < 255; i++) assert(buf[i] == 'x');
        }
        assert(webusb_landingPage_show(0,page) == (ssize_t)max+1 && page[max] == '\n');
    }
    assert(webusb_landingPage_store(0,"https://github.com/andr36oid\n",29) == 29);
    assert(!strcmp(gi.landing_page,"https://github.com/andr36oid"));
    assert(webusb_landingPage_store(0,"x",1) == 1 && !strcmp(gi.landing_page,"x"));
    assert(webusb_landingPage_store(0,"",0) == 0 && !gi.landing_page[0]);
    assert(webusb_landingPage_store(0,"\n",1) == 1 && !gi.landing_page[0]);
    assert(webusb_use_store(0,"0",1) == 1); bind_webusb(&cdev,&gi);
    ctrl.wLength = cpu_to_le16(255); assert(dispatch(&cdev,&ctrl) == -999 && !cdev.use_webusb);
    assert(!gi.lock);
    puts("WebUSB: exact BOS/URL bytes, short reads, routing, LPM, limits and rebind passed");
}
'''
includes = work / 'webusb-headers'
(includes / 'linux/usb').mkdir(parents=True, exist_ok=True)
(includes / 'linux/usb/ch9.h').write_text((kernel / 'include/uapi/linux/usb/ch9.h').read_text())
p = work / 'webusb.c'
p.write_text(stubs + tests)
subprocess.run(['cc','-std=gnu11','-Wall','-Wextra','-Werror','-Wno-unused-parameter',
                '-fsanitize=address,undefined','-fno-omit-frame-pointer','-no-pie',
                '-I' + str(includes),str(p),'-o',str(work / 'webusb')],check=True)
subprocess.run([str(work / 'webusb')],check=True)
