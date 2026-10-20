package org.andr36oid.emmc;

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
 * Talks to the andr36oid-emmc init service (helper/andr36oid-emmc.sh): we write a request
 * and start it, it writes the eMMC's info and the backup's key=value status.
 */
final class Helper {

    private static final String TAG = "EmmcTool";
    static final String SERVICE = "andr36oid-emmc";
    private static final File DIR = new File("/data/misc/andr36oid-emmc");
    static final File INFO = new File(DIR, "info");
    private static final File STATUS = new File(DIR, "status");
    private static final File SPEED_RESULT = new File(DIR, "result-speed");
    private static final File DUMP_RESULT = new File(DIR, "result-dump");
    static final String DUMP = "dump", SPEED = "speed";

    private Helper() {
    }

    /** Reads the eMMC's details again, into INFO. */
    static boolean info() {
        INFO.delete();
        return start("action=info\n");
    }

    /** Backs up the given block names into dest (a folder the helper writes as root). */
    static boolean dump(String items, Target dest) {
        STATUS.delete();
        return start("action=" + DUMP + "\n"
                + "items=" + items + "\n"
                + "dest=" + dest.path + "\n"
                + "dest_name=" + dest.name.replace('\n', ' ') + "\n"
                + "split_mb=" + dest.splitMb + "\n"
                + "keep_free_mb=" + dest.keepFreeMb + "\n");
    }

    /** Tests the read speed; the result stays in result-speed. */
    static boolean speed() {
        STATUS.delete();
        return start("action=speed\n");
    }

    private static boolean start(String request) {
        try {
            new File(DIR, "stop").delete();
            try (FileOutputStream out = new FileOutputStream(new File(DIR, "request"))) {
                out.write(request.getBytes(StandardCharsets.UTF_8));
            }
            SystemProperties.set("ctl.start", SERVICE);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "can't start the helper", e);
            return false;
        }
    }

    /** Asks a running backup to stop. */
    static void stop() {
        try (FileOutputStream out = new FileOutputStream(new File(DIR, "stop"))) {
            out.write('\n');
        } catch (IOException e) {
            Log.w(TAG, "can't ask the backup to stop", e);
        }
    }

    static boolean running() {
        final String state = SystemProperties.get("init.svc." + SERVICE, "");
        return "running".equals(state) || "restarting".equals(state);
    }

    /** The eMMC's details as last read, null if not read yet. */
    static EmmcInfo readInfo() {
        final String text = read(INFO);
        return text == null ? null : EmmcInfo.parse(text);
    }

    /** The status of the current or last backup or speed test, empty if none. */
    static Map<String, String> status() {
        return map(STATUS);
    }

    /** The last speed test's result, empty if none. */
    static Map<String, String> speedResult() {
        return map(SPEED_RESULT);
    }

    /** How the last backup ended, empty if none. */
    static Map<String, String> dumpResult() {
        return map(DUMP_RESULT);
    }

    private static Map<String, String> map(File f) {
        final Map<String, String> map = new HashMap<>();
        final String text = read(f);
        if (text == null) return map;
        for (String line : text.split("\n")) {
            final int eq = line.indexOf('=');
            if (eq > 0) map.put(line.substring(0, eq), line.substring(eq + 1).trim());
        }
        return map;
    }

    static long number(Map<String, String> s, String key) {
        try {
            return Long.parseLong(s.get(key));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A small file, null if it can't be read. */
    static String read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            final byte[] buf = new byte[16384];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
