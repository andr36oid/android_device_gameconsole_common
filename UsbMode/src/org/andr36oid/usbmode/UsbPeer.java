package org.andr36oid.usbmode;

final class UsbPeer {
    static boolean matches(int vendor, int product, String manufacturer, String name) {
        final int identity = product - 0x4000;
        final int set = identity & 0x1fff;
        return vendor == 0x18d1 && identity > 63 && identity <= 0x3fff && ((identity & 0x2000) == 0 || (set & UsbFunctions.SOUND) != 0) && (set & (2048 | 4096)) != 0
                && "andr36oid".equals(manufacturer)
                && ("andr36oid USB Device".equals(name) || "andr36oid XInput Device".equals(name));
    }
}
