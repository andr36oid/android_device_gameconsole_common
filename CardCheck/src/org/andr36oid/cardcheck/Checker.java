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
    /** Go on with a paused real size check, or delete its test data. */
    static final String RESUME = "resume", DELETE = "delete";

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

    /** Asks a running check to stop; a real size check keeps its test data if pausing. */
    static void stop(boolean pause) {
        try (FileOutputStream out = new FileOutputStream(new File(DIR, "stop"))) {
            if (pause) out.write("pause\n".getBytes(StandardCharsets.UTF_8));
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

    /**
     * What a paused (or cut off) real size check on this card left: phase, total_mb,
     * written_mb, checked_mb. Empty if there is none.
     */
    static Map<String, String> progress(Card card) {
        return read(new File(DIR, "progress-" + card.uuid));
    }

    /**
     * The test data a paused (or cut off) size check left on any card, in MB; -1 if none.
     * Read at start-up, when no check runs.
     */
    static long pausedAnywhereMb() {
        final File[] files = DIR.listFiles((dir, name) -> name.startsWith("progress-"));
        if (files == null) return -1;
        long mb = -1;
        for (File f : files) {
            final Map<String, String> p = read(f);
            if (p.isEmpty()) continue;
            mb = Math.max(mb, Math.max(0, number(p, "written_mb")));
        }
        return mb;
    }

    /** A real size check on this card is paused: its test data is on the card. */
    static boolean paused(Card card) {
        if (progress(card).isEmpty()) return false;
        if (!running()) return true;
        final Map<String, String> s = status();
        return !(FULL.equals(s.get("action")) && card.uuid.equals(s.get("uuid")));
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

    static double seconds(Map<String, String> s, String key) {
        return Stats.seconds(s, key);
    }

    static long number(Map<String, String> s, String key) {
        return Stats.number(s, key);
    }

    static double speed(Map<String, String> s, long mb, String from, String to) {
        return Stats.speed(s, mb, from, to);
    }
}
