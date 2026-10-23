#!/usr/bin/env python3
"""Apply the overlay patch in scratch and exercise its actual kernel handlers."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys

kernel, work, patch = map(Path, sys.argv[1:4])
copy = work / 'flipper-kernel'
files = re.findall(r'^--- a/(.+)$', patch.read_text(), re.M)
for name in files:
    target = copy / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(kernel / name, target)
subprocess.run(['patch', '--batch', '-p1', '-d', str(copy), '-i', str(patch)], check=True, stdout=subprocess.DEVNULL)
source = (copy / 'drivers/usb/gadget/function/f_fs.c').read_text()

def function(name):
    match = re.search(r'static [^{;]+\b' + name + r'\([^;]+?\n\{', source)
    assert match, name
    return source[match.start():end(source, match.end() - 1)]

def end(text, pos):
    depth = 1
    pos += 1
    while depth:
        if text[pos] == '{': depth += 1
        if text[pos] == '}': depth -= 1
        pos += 1
    return pos

# Compile the same parser/rewrite harness with the patched implementation.
env = dict(os.environ, TMPDIR=str(work), CONTROLLER_KERNEL=str(kernel), CONTROLLER_FFS=str(copy / 'drivers/usb/gadget/function/f_fs.c'), FLIPPER_CDC_TEST='1')
subprocess.run([sys.executable, str(Path(__file__).with_name('check-kernel.py')), str(work)], env=env, check=True)
dwc = (copy / 'drivers/usb/dwc2/gadget.c').read_text()
begin = dwc.index('\tif (!hs_ep->index && hs_req->req.defer_status')
transition = dwc[begin:end(dwc, dwc.index('{', begin))]
begin = dwc.index('\tif (epnum == 0 && hsotg->ep0_state == DWC2_EP0_DATA_OUT &&')
dispatch = dwc[begin:end(dwc, dwc.index('{', begin))]
text = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <errno.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <linux/usb/ch9.h>
#define __releases(x)
#define unlikely(x) (x)
#define le16_to_cpu(x) (x)
#define GFP_ATOMIC 0
#define ENTER()
#define pr_vdebug(...) ((void)0)
#define pr_debug(...) ((void)0)
#define FUNCTIONFS_DEFER_OUT_STATUS _IO('g',131)
#define FUNCTIONFS_INTERFACE_REVMAP _IO('g',128)
#define FFS_NO_SETUP 0
#define FFS_SETUP_PENDING 1
#define DWC2_EP0_DATA_OUT 1
#define DWC2_EP0_STATUS_IN 2
struct usb_request { bool defer_status, zero; void *buf; unsigned length, actual; int status; };
struct dwc2_hsotg { int ep0_state; };
struct dwc2_hsotg_ep { int index, dir_in; };
struct dwc2_hsotg_req { struct usb_request req; };
struct usb_gadget { void *ep0; struct { int (*ioctl)(struct usb_gadget*,unsigned,unsigned long); } *ops; };
struct ffs_function { int unused; };
struct ffs_data {
    struct usb_request *ep0req; struct usb_gadget *gadget; struct ffs_function *func;
    bool defer_out_status, out_data_received;
    int setup_state, ep0req_completion;
    struct { int can_stall; struct usb_ctrlrequest setup; struct { int lock; } waitq; } ev;
};
struct file { struct ffs_data *private_data; };
static int acknowledgements, stalls, interrupted, dequeued;
static struct dwc2_hsotg controller;
static struct dwc2_hsotg_ep endpoint;
static void spin_unlock_irq(int *p) { (void)p; }
static void reinit_completion(int *p) { (void)p; }
static int wait_for_completion_interruptible(int *p) { (void)p; return interrupted; }
static int usb_ep_dequeue(void *ep, struct usb_request *r) { (void)ep; (void)r; dequeued++; return 0; }
static int usb_ep_set_halt(void *ep) { (void)ep; stalls++; return 0; }
static int ffs_func_revmap_intf(struct ffs_function *f, unsigned long n) { (void)f; return n; }
static void dwc2_hsotg_complete_request(struct dwc2_hsotg *hsotg, struct dwc2_hsotg_ep *hs_ep,
                                     struct dwc2_hsotg_req *hs_req, int result) {
'''+transition+r'''
}
static void complete_out(struct dwc2_hsotg *hsotg, struct dwc2_hsotg_ep *hs_ep,
                         struct dwc2_hsotg_req *hs_req) {
    int epnum = 0, result = 0;
'''+dispatch+r'''
    acknowledgements++;
}
static int usb_ep_queue(void *ep, struct usb_request *request, int flags) {
    (void)ep; (void)flags;
    request->actual = request->length; request->status = 0;
    struct dwc2_hsotg_req req = {*request};
    if (controller.ep0_state == DWC2_EP0_DATA_OUT) complete_out(&controller,&endpoint,&req);
    else acknowledgements++;
    return 0;
}
'''
for name in ('__ffs_ep0_queue_wait', '__ffs_ep0_stall', 'ffs_ep0_ioctl'):
    text += function(name) + '\n'
text += r'''
int main(void) {
    struct usb_request req = {0};
    struct usb_gadget gadget = {0};
    struct ffs_data ffs = {.ep0req=&req,.gadget=&gadget,.setup_state=FFS_SETUP_PENDING,
        .ev={.can_stall=1,.setup={.bRequestType=0x21,.bRequest=0x20,.wLength=7}}};
    struct file file = {&ffs}; char data[7] = {0};
    assert(ffs_ep0_ioctl(&file,FUNCTIONFS_DEFER_OUT_STATUS,0) == -EINVAL);
    assert(ffs_ep0_ioctl(&file,FUNCTIONFS_DEFER_OUT_STATUS,1) == 0 && ffs.defer_out_status);
    controller.ep0_state = DWC2_EP0_DATA_OUT;
    assert(__ffs_ep0_queue_wait(&ffs,data,7) == 7);
    assert(acknowledgements == 0 && ffs.out_data_received && ffs.setup_state == FFS_SETUP_PENDING);
    assert(controller.ep0_state == DWC2_EP0_STATUS_IN && endpoint.dir_in == 1);
    assert(__ffs_ep0_queue_wait(&ffs,data,0) == 0);
    assert(acknowledgements == 1 && !ffs.out_data_received && ffs.setup_state == FFS_NO_SETUP);
    controller.ep0_state = DWC2_EP0_DATA_OUT; ffs.setup_state = FFS_SETUP_PENDING;
    assert(__ffs_ep0_queue_wait(&ffs,data,7) == 7 && acknowledgements == 1);
    assert(__ffs_ep0_stall(&ffs) == -EL2HLT && stalls == 1 && acknowledgements == 1);
    assert(!ffs.out_data_received && ffs.setup_state == FFS_NO_SETUP);
    interrupted = 1; controller.ep0_state = DWC2_EP0_DATA_OUT;
    assert(__ffs_ep0_queue_wait(&ffs,data,7) == -EINTR && dequeued == 1);
    ffs.defer_out_status = false; interrupted = 0; controller.ep0_state = DWC2_EP0_DATA_OUT;
    assert(__ffs_ep0_queue_wait(&ffs,data,7) == 7 && acknowledgements == 2);
    puts("Kernel deferred OUT: data withheld status, explicit ACK, guest STALL, cancellation and legacy mode passed");
}
'''
file = work / 'flipper-status.c'
file.write_text(text)
subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-Wno-sign-compare', str(file), '-o', str(work / 'flipper-status')], check=True)
subprocess.run([str(work / 'flipper-status')], check=True)
