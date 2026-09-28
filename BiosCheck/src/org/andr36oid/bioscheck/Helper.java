package org.andr36oid.bioscheck;

import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to the andr36oid-bioscheck init service (helper/andr36oid-bioscheck.sh), which
 * does the file work as root: we write a request (and the operations) and start it, it
 * writes one status line, the scan and the results.
 */
final class Helper {

    private static final String TAG = "BiosCheck";
    static final String SERVICE = "andr36oid-bioscheck";
    private static final File DIR = new File("/data/misc/andr36oid-bioscheck");
    private static final File STATUS = new File(DIR, "status");
    private static final File SCAN = new File(DIR, "scan");
    private static final File RESULT = new File(DIR, "result");

    private Helper() {
    }

    static boolean scan() {
        return start("scan", null);
    }

    /** Runs the operations (see Report.ops), then scans again. */
    static boolean apply(String ops) {
        return start("apply", ops);
    }

    private static boolean start(String action, String ops) {
        try {
            STATUS.delete();
            if (ops != null) {
                RESULT.delete();
                write(new File(DIR, "ops"), ops);
            }
            write(new File(DIR, "request"), "action=" + action + "\n");
            SystemProperties.set("ctl.start", SERVICE);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "can't start the helper", e);
            return false;
        }
    }

    static boolean running() {
        final String state = SystemProperties.get("init.svc." + SERVICE, "");
        return "running".equals(state) || "restarting".equals(state);
    }

    /** "running <action>", "done", "error <code>", or null before the helper wrote any. */
    static String status() {
        final String s = read(STATUS);
        return s != null ? s.trim() : null;
    }

    /** The last scan, null if there is none yet. */
    static Scan readScan() {
        try (InputStream in = new FileInputStream(SCAN)) {
            return Scan.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** The last apply's result lines ("copied <path>" etc.). */
    static List<String> result() {
        final List<String> lines = new ArrayList<>();
        final String s = read(RESULT);
        if (s == null) return lines;
        for (String line : s.split("\n")) {
            if (!line.trim().isEmpty()) lines.add(line.trim());
        }
        return lines;
    }

    private static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            final byte[] buf = new byte[65536];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
