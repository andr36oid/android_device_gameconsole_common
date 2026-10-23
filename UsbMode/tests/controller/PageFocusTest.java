package org.andr36oid.usbmode;

public final class PageFocusTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Page focus " + checks); }
    public static void main(String[] args) {
        UsbPageFocus focus = new UsbPageFocus();
        int old = focus.begin(false, "read");
        check(focus.target().isEmpty());
        check(!focus.ready(old, false, true, false));
        check(!focus.ready(old, true, false, false));
        check(focus.ready(old, true, true, false));
        int current = focus.begin(false, "back");
        check(!focus.ready(old, true, true, false));
        focus.applied(old); check(focus.ready(current, true, true, false));
        check(focus.target().isEmpty());
        check(!focus.ready(current, true, true, true));
        focus.applied(current); check(!focus.ready(current, true, true, false));
        current = focus.begin(true, "function:8");
        check(focus.target().equals("function:8"));
        check(focus.ready(current, true, true, false));
        current = focus.begin(false, "next"); // rotation/background return starts with the first real option
        check(focus.target().isEmpty());
        check(!focus.ready(current, true, false, false));
        check(focus.ready(current, true, true, false));
        System.out.println(checks + " page transition, stale callback, window focus and dialog checks passed");
    }
}
