package org.andr36oid.batterydetails;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The graph's data: one line per sample in files/history.csv,
 * "time,percent,mV,mA,charging". A few kB, trimmed to the last week.
 */
final class History {

    private static final String TAG = "BatteryDetails";
    private static final String FILE = "history.csv";
    private static final long KEEP_MS = 7 * 24 * 3600 * 1000L;
    /** Rewrite the file to drop old lines once it has this many. */
    private static final int TRIM_AT = 1000;

    static final class Point {
        final long time;
        final int percent;
        final boolean charging;

        Point(long time, int percent, boolean charging) {
            this.time = time;
            this.percent = percent;
            this.charging = charging;
        }
    }

    private History() {
    }

    static synchronized void record(Context context, Gauge gauge) {
        if (gauge.percent == Gauge.UNKNOWN) {
            return;
        }
        final File file = new File(context.getFilesDir(), FILE);
        final String line = gauge.time + "," + gauge.percent + "," + gauge.voltage + ","
                + gauge.current + "," + (gauge.isCharging() ? 1 : 0) + "\n";
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file, true),
                StandardCharsets.UTF_8)) {
            out.write(line);
        } catch (IOException e) {
            Log.w(TAG, "Couldn't save a battery sample", e);
            return;
        }
        final List<String> lines = readLines(file);
        if (lines.size() >= TRIM_AT) {
            final long oldest = System.currentTimeMillis() - KEEP_MS;
            final StringBuilder kept = new StringBuilder();
            for (String l : lines) {
                if (parseTime(l) >= oldest) {
                    kept.append(l).append('\n');
                }
            }
            try (Writer out = new OutputStreamWriter(new FileOutputStream(file, false),
                    StandardCharsets.UTF_8)) {
                out.write(kept.toString());
            } catch (IOException e) {
                Log.w(TAG, "Couldn't trim the battery history", e);
            }
        }
    }

    /** The time of the newest sample, 0 if there's none. */
    static synchronized long lastTime(Context context) {
        final List<String> lines = readLines(new File(context.getFilesDir(), FILE));
        return lines.isEmpty() ? 0 : Math.max(0, parseTime(lines.get(lines.size() - 1)));
    }

    /** Samples newer than {@code since}, oldest first. */
    static synchronized List<Point> load(Context context, long since) {
        final List<Point> points = new ArrayList<>();
        for (String line : readLines(new File(context.getFilesDir(), FILE))) {
            final String[] f = line.split(",");
            if (f.length < 5) {
                continue;
            }
            try {
                final long time = Long.parseLong(f[0]);
                if (time >= since) {
                    points.add(new Point(time, Integer.parseInt(f[1]), "1".equals(f[4])));
                }
            } catch (NumberFormatException e) {
                // A torn line from a crash while writing, skip it.
            }
        }
        // The clock may have jumped (no network time on this console), keep the order sane.
        points.sort((a, b) -> Long.compare(a.time, b.time));
        return points;
    }

    private static long parseTime(String line) {
        final int comma = line.indexOf(',');
        try {
            return Long.parseLong(comma < 0 ? line : line.substring(0, comma));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static List<String> readLines(File file) {
        final List<String> lines = new ArrayList<>();
        if (!file.exists()) {
            return lines;
        }
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Couldn't read the battery history", e);
        }
        return lines;
    }
}
