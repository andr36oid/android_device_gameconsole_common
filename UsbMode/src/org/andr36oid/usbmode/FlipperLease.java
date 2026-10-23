package org.andr36oid.usbmode;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

final class FlipperLease {
    static final String PACKAGE = "org.andr36oid.flipper.r36s";
    static String start(int pid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/stat"))) {
            return start(reader.readLine());
        } catch (IOException | RuntimeException e) { return ""; }
    }
    static String start(String stat) {
        if (stat == null || stat.lastIndexOf(')') < 0) return "";
        String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
        return fields.length > 19 && fields[19].matches("[0-9]+") ? fields[19] : "";
    }
    static boolean allowed(int uid, int pid, String pinnedStart, String currentStart,
            String[] packages, boolean signed, boolean foreground, boolean screen, boolean focused) {
        return uid >= 10000 && pid > 0 && !pinnedStart.isEmpty() && pinnedStart.equals(currentStart)
                && packages != null && packages.length == 1 && PACKAGE.equals(packages[0])
                && signed && foreground && screen && focused;
    }
}
