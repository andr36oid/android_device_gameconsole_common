# Flipper FunctionFS kernel support

The Flipper lease needs `flipper-functionfs.patch` in the R36S kernel as well as
this ROM overlay. Apply the patch to the controller-enabled R36S kernel before
the normal ROM/kernel build. Development and host tests apply it only to a scratch
copy; they never change the AOSP source tree or build a kernel.

The existing FunctionFS parser accepts HID and XUSB but rejects CDC functional
descriptors. This patch accepts only the ACM header, call-management, ACM and
single-slave Union descriptors, and relocates their interface references and IAD.

`FUNCTIONFS_DEFER_OUT_STATUS` (`_IO('g', 131)`, argument 1 on ep0) opts this
function into a dwc2 extension. An OUT data read returns before the status stage.
The subsequent zero-length read accepts status; writing ep0 stalls it. This lets
SET_LINE_CODING reach firmware before the PC receives ACK or STALL. New SETUP,
close and interrupted transfers cancel the pending request. Other FunctionFS
functions retain their existing behavior unless they explicitly opt in.

The native service requires this ioctl before signalling readiness. An older
kernel fails closed and the shared helper restores the old gadget. There is no
fallback to kernel ACM or serial echo.

`UsbMode/tests/run-tests.sh` applies the patch under its temporary test directory,
compiles the actual parser/interface rewrite and deferred-status handlers against
host stubs, and exercises generated single/dual CDC descriptors, ACK, STALL,
cancellation and legacy behavior. This is a host handler check, not a kernel build
or proof of real dwc2 status timing. Hardware enumeration remains necessary.
