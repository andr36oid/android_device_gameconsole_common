package org.andr36oid.usbmode;

/** Retained with the request so a saved page cannot resurrect a completed create. */
final class UsbImageCreate {
    boolean waiting, use;
    String name = "", cid = "";
    int functions;
    long waitUntil;

    void begin(String name, String cid, int functions, boolean use) {
        this.name = name; this.cid = cid; this.functions = functions; this.use = use;
        waiting = true; waitUntil = 0;
    }
    boolean matches(UsbDrive.Offer image) {
        return waiting && image.kind == UsbDrive.Kind.IMAGE && image.node.equals(name) && image.cid.equals(cid);
    }
    boolean session(String target, String image, String card) {
        return waiting && use && "file".equals(target) && name.equals(image) && cid.equals(card);
    }
}
