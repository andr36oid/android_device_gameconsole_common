package org.andr36oid.usbmode;

final class UsbPeer {
    static boolean matches(int vendor, int product, String manufacturer, String name) {
        final int set = product - 0x4000;
        return vendor == 0x18d1 && set > 63 && set <= 0x1fff && (set & (2048 | 4096)) != 0
                && "andr36oid".equals(manufacturer)
                && ("andr36oid USB Device".equals(name) || "andr36oid XInput Device".equals(name));
    }
}
