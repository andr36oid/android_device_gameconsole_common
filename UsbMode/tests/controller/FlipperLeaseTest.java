package org.andr36oid.usbmode;

public class FlipperLeaseTest {
    public static void main(String[] args) {
        String[] packages = {FlipperLease.PACKAGE};
        if (!FlipperLease.allowed(10001,123,"456","456",packages,true,true,true,true)) throw new AssertionError();
        for (int i = 0; i < 9; i++) {
            if (FlipperLease.allowed(i == 0 ? 1000 : 10001,i == 1 ? 0 : 123,"456",i == 2 ? "457" : "456",
                    i == 3 ? new String[]{"other"} : i == 4 ? new String[]{FlipperLease.PACKAGE,"shared"} : packages,
                    i != 5,i != 6,i != 7,i != 8)) throw new AssertionError(i);
        }
        if (!FlipperLease.start("123 (name with ) spaces) S 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 456 0").equals("456")) throw new AssertionError();
        if (!FlipperLease.start("bad").isEmpty() || !FlipperLease.start(-1).isEmpty()) throw new AssertionError();
        System.out.println("Flipper package/signature, unique UID, PID/start, foreground, focus and screen lease checks passed");
    }
}
