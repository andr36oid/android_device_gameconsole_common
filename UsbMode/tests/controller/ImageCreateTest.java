package org.andr36oid.usbmode;

public final class ImageCreateTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Create completion " + checks); }
    public static void main(String[] args) {
        UsbImageCreate create = new UsbImageCreate();
        UsbDrive.Offer image = new UsbDrive.Offer();
        image.kind = UsbDrive.Kind.IMAGE; image.node = "image1.img"; image.cid = "card1";
        check(!create.matches(image));
        create.begin("image1.img", "card1", UsbFunctions.STORAGE | UsbFunctions.KEYBOARD, true);
        check(create.waiting && create.use && create.matches(image));
        check(create.functions == (UsbFunctions.STORAGE | UsbFunctions.KEYBOARD));
        image.node = "image2.img"; check(!create.matches(image));
        image.node = "image1.img"; image.cid = "card2"; check(!create.matches(image));
        image.cid = "card1"; image.kind = UsbDrive.Kind.EASYROMS; check(!create.matches(image));
        check(create.session("file", "image1.img", "card1"));
        check(!create.session("file", "image1.img", "card2"));
        check(!create.session("file", "image2.img", "card1"));
        check(!create.session("mmcblk1", "image1.img", "card1"));
        // Retained work is authoritative even if Android saved a page before session submission.
        UsbImageCreate retained = create;
        retained.waiting = false;
        image.kind = UsbDrive.Kind.IMAGE;
        check(!create.matches(image) && !create.session("file", "image1.img", "card1"));
        create.begin("image1.img", "card1", UsbFunctions.STORAGE, false);
        check(create.matches(image) && !create.session("file", "image1.img", "card1"));
        System.out.println(checks + " create-and-use identity, retained completion and active session checks passed");
    }
}
