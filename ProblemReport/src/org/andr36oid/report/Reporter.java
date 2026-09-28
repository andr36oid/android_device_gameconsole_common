package org.andr36oid.report;

import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Talks to the andr36oid-report init service (helper/andr36oid-report.sh), which does the
 * collecting as root. We hand over the note and start it; it writes one status line.
 */
final class Reporter {

    private static final String TAG = "ProblemReport";
    static final String SERVICE = "andr36oid-report";
    private static final File DIR = new File("/data/misc/andr36oid-report");
    private static final File NOTE = new File(DIR, "note.txt");
    private static final File STATUS = new File(DIR, "status");
    static final File PSTORE = new File("/sys/fs/pstore");

    static final int IDLE = 0, RUNNING = 1, DONE = 2, FAILED = 3;

    /** The parsed status line. */
    static final class Status {
        int state = IDLE;
        int step, steps;
        /** Step name while running, the saved file when done, the error code when failed. */
        String text = "";
    }

    private Reporter() {
    }

    static boolean start(String note) {
        try {
            STATUS.delete();
            try (FileOutputStream out = new FileOutputStream(NOTE)) {
                out.write((note == null ? "" : note).getBytes(StandardCharsets.UTF_8));
            }
            SystemProperties.set("ctl.start", SERVICE);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "can't start the report", e);
            return false;
        }
    }

    static boolean serviceRunning() {
        final String state = SystemProperties.get("init.svc." + SERVICE, "");
        return "running".equals(state) || "restarting".equals(state);
    }

    static Status readStatus() {
        final Status s = new Status();
        final String line = readSmall(STATUS);
        if (line == null) return s;
        final String[] parts = line.trim().split(" ", 4);
        switch (parts[0]) {
            case "running":
                if (parts.length < 4) break;
                s.state = RUNNING;
                try {
                    s.step = Integer.parseInt(parts[1]);
                    s.steps = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    s.step = s.steps = 0;
                }
                s.text = parts[3];
                break;
            case "done":
                s.state = DONE;
                s.text = line.trim().substring("done".length()).trim();
                break;
            case "error":
                s.state = FAILED;
                s.text = parts.length > 1 ? parts[1] : "";
                break;
        }
        return s;
    }

    /** A kernel crash record (oops or panic) from the last start, if pstore kept one. */
    static File crashRecord() {
        final File[] files = PSTORE.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().startsWith("dmesg-ramoops")) return f;
        }
        return null;
    }

    /** The last start's kernel log, kept across a restart. */
    static boolean hasPreviousLog() {
        final File[] files = PSTORE.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.getName().startsWith("console-ramoops")) return true;
        }
        return false;
    }

    /** A short fingerprint of a file, to tell the same crash record from a new one. */
    static String fingerprint(File f) {
        try (InputStream in = new FileInputStream(f)) {
            final byte[] buf = new byte[4096];
            final int n = in.read(buf);
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            if (n > 0) md.update(buf, 0, n);
            final StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readSmall(File f) {
        try (InputStream in = new FileInputStream(f)) {
            final byte[] buf = new byte[1024];
            final int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
