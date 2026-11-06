package org.andr36oid.cpuoverclock;

import java.io.IOException;
import java.util.Arrays;

/** Clocks-only transaction, independent of Android for host fixtures. Never
 * reads/writes boost frequencies, voltages, thermal settings or OPP controls. */
final class StockClockSession {
    static final String CPU = "/sys/devices/system/cpu/cpufreq/policy0/";
    static final String GPU = "/sys/class/devfreq/ff400000.gpu/";
    static final String DMC = "/sys/class/devfreq/dmc/";
    static final String[] DEVICES = {CPU, GPU, DMC};
    static final long[] LIMITS = {1296000, 520000000, 786000000};
    interface Io {
        String read(String path) throws IOException;
        void write(String path, String value) throws IOException;
    }
    interface Journal {
        Snapshot load();
        boolean save(Snapshot snapshot);
    }
    static final class Snapshot {
        final String boot;
        final long[] min, max, target;
        final String[] governor;
        Snapshot(String boot, long[] min, long[] max, long[] target, String[] governor) {
            this.boot = boot; this.min = min; this.max = max;
            this.target = target; this.governor = governor;
        }
        boolean valid() {
            if (boot == null || min.length != 3 || max.length != 3 || target.length != 3
                    || governor.length != 3) return false;
            for (int i = 0; i < 3; i++) {
                if (min[i] < 0 || (i == 0 && min[i] == 0) || min[i] > max[i] || max[i] > LIMITS[i]
                        || target[i] < max[i] || target[i] > LIMITS[i]
                        || governor[i] == null || !governor[i].matches("[a-zA-Z0-9_-]+")) return false;
            }
            return true;
        }
    }
    private final Io io;
    private final Journal journal;
    private final String boot;
    private Snapshot saved;
    StockClockSession(Io io, Journal journal, String boot) {
        this.io = io; this.journal = journal; this.boot = boot;
        saved = journal.load();
        if (saved != null && (!saved.valid() || !boot.equals(saved.boot))) {
            // Kernel state does not survive a reboot; never replay an old journal.
            if (journal.save(null)) saved = null;
        }
    }
    boolean active() { return saved != null && saved.valid() && boot.equals(saved.boot); }

    boolean start() {
        try {
            if (!"0".equals(io.read("/sys/devices/system/cpu/cpufreq/boost").trim())) return false;
            if (saved != null) return active() && pinned();
            long[] min = new long[3], max = new long[3], targets = new long[3];
            String[] governors = new String[3];
            for (int i = 0; i < 3; i++) {
                String dev = DEVICES[i];
                long[] available = frequencies(io.read(dev + (i == 0
                        ? "scaling_available_frequencies" : "available_frequencies")));
                long target = 0;
                for (long hz : available) if (hz <= LIMITS[i]) target = Math.max(target, hz);
                // CPU/GPU admission requires the known stock top, not an OC table maximum.
                if (target == 0 || (i < 2 && target != LIMITS[i])) return false;
                min[i] = number(io.read(dev + minName(i)));
                max[i] = number(io.read(dev + maxName(i)));
                governors[i] = io.read(dev + governorName(i)).trim();
                targets[i] = target;
            }
            Snapshot intent = new Snapshot(boot, min, max, targets, governors);
            if (!intent.valid() || !journal.save(intent)) return false;
            saved = intent; // durable before the first write; also recovers partial admission
            for (int i = 0; i < 3; i++) {
                io.write(DEVICES[i] + maxName(i), Long.toString(targets[i]));
                io.write(DEVICES[i] + minName(i), Long.toString(targets[i]));
            }
            if (!pinned()) throw new IOException("stock clock readback mismatch");
            return true;
        } catch (IOException | RuntimeException e) {
            stop();
            return false;
        }
    }
    boolean pinned() {
        if (!active()) return false;
        try {
            for (int i = 0; i < 3; i++) {
                if (number(io.read(DEVICES[i] + minName(i))) != saved.target[i]
                        || number(io.read(DEVICES[i] + maxName(i))) != saved.target[i]) return false;
            }
            return true;
        } catch (IOException | RuntimeException e) { return false; }
    }
    boolean stop() {
        if (saved == null) return true;
        if (!saved.valid() || !boot.equals(saved.boot)) {
            if (!journal.save(null)) return false;
            saved = null; return true;
        }
        boolean ok = true;
        for (int i = 0; i < 3; i++) {
            try {
                String dev = DEVICES[i];
                long min = number(io.read(dev + minName(i)));
                long max = number(io.read(dev + maxName(i)));
                // Mixed original/target values cover an interrupted two-write pair.
                // A later explicit user/kernel change wins rather than being overwritten.
                if ((min != saved.min[i] && min != saved.target[i])
                        || (max != saved.max[i] && max != saved.target[i])) continue;
                io.write(dev + minName(i), Long.toString(saved.min[i]));
                io.write(dev + maxName(i), Long.toString(saved.max[i]));
                if (number(io.read(dev + minName(i))) != saved.min[i]
                        || number(io.read(dev + maxName(i))) != saved.max[i]) ok = false;
                // Governors are deliberately never changed: their exact original
                // values remain in place unless the user changes one deliberately.
            } catch (IOException | RuntimeException e) { ok = false; }
        }
        if (ok && journal.save(null)) saved = null;
        else ok = false;
        return ok;
    }
    private static long number(String text) { return Long.parseLong(text.trim()); }
    private static long[] frequencies(String text) {
        return Arrays.stream(text.trim().split("\\s+")).mapToLong(Long::parseLong).toArray();
    }
    private static String minName(int device) { return device == 0 ? "scaling_min_freq" : "min_freq"; }
    private static String maxName(int device) { return device == 0 ? "scaling_max_freq" : "max_freq"; }
    private static String governorName(int device) { return device == 0 ? "scaling_governor" : "governor"; }
}
