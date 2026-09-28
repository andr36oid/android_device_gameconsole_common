package org.andr36oid.cardcheck;

import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Talks to the andr36oid-cardcheck init service (helper/andr36oid-cardcheck.sh): we write
 * a request and start it, it writes key=value status lines and keeps the last result of
 * each test per card.
 */
final class Checker {

    private static final String TAG = "CardCheck";
    static final String SERVICE = "andr36oid-cardcheck";
    private static final File DIR = new File("/data/misc/andr36oid-cardcheck");
    private static final File STATUS = new File(DIR, "status");

    static final String SPEED = "speed", FULL = "full", CLEANUP = "cleanup";

    private Checker() {
    }

    static boolean start(String action, Card card) {
        final String request = "action=" + action + "\n"
                + "path=" + card.internalPath + "\n"
                + "disk=" + card.disk + "\n"
                + "disk_mb=" + (card.diskBytes / (1024 * 1024)) + "\n";
        try {
            new File(DIR, "stop").delete();
            STATUS.delete();
            try (FileOutputStream out = new FileOutputStream(new File(DIR, "request"))) {
                out.write(request.getBytes(StandardCharsets.UTF_8));
            }
            SystemProperties.set("ctl.start", SERVICE);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "can't start the check", e);
            return false;
        }
    }

    static void stop() {
        try {
            new FileOutputStream(new File(DIR, "stop")).close();
        } catch (IOException e) {
            Log.w(TAG, "can't ask the check to stop", e);
        }
    }

    static boolean running() {
        final String state = SystemProperties.get("init.svc." + SERVICE, "");
        return "running".equals(state) || "restarting".equals(state);
    }

    /** The status of the current or last run, empty if none. */
    static Map<String, String> status() {
        return read(STATUS);
    }

    /** The last finished result of a test on this card, empty if none. */
    static Map<String, String> result(String action, Card card) {
        return read(new File(DIR, "result-" + action + "-" + card.uuid));
    }

    private static Map<String, String> read(File f) {
        final Map<String, String> map = new HashMap<>();
        try (InputStream in = new FileInputStream(f)) {
            final byte[] buf = new byte[4096];
            final int n = in.read(buf);
            if (n <= 0) return map;
            for (String line : new String(buf, 0, n, StandardCharsets.UTF_8).split("\n")) {
                final int eq = line.indexOf('=');
                if (eq > 0) map.put(line.substring(0, eq), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            // no status yet
        }
        return map;
    }

    /** Seconds from a "seconds.nanoseconds" time the service wrote, NaN if missing. */
    static double seconds(Map<String, String> s, String key) {
        try {
            return Double.parseDouble(s.get(key));
        } catch (NullPointerException | NumberFormatException e) {
            return Double.NaN;
        }
    }

    static long number(Map<String, String> s, String key) {
        try {
            return Long.parseLong(s.get(key));
        } catch (NullPointerException | NumberFormatException e) {
            return -1;
        }
    }

    /** MB/s between two time keys, NaN if they're missing. */
    static double speed(Map<String, String> s, long mb, String from, String to) {
        final double t = seconds(s, to) - seconds(s, from);
        return t > 0 ? mb / t : Double.NaN;
    }
}
