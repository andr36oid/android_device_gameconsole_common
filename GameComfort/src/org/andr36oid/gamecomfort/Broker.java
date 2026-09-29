package org.andr36oid.gamecomfort;

import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import org.andr36oid.gamecomfort.resume.FileAccess;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads files through the andr36oid-gamecomfort init service (helper/andr36oid-gamecomfort.sh),
 * which runs as root: we write a request, start it, and wait for its answer. One request at a
 * time; each takes a few hundred milliseconds. Call off the main thread.
 */
final class Broker implements FileAccess {

    private static final String TAG = "GameComfort";
    private static final String SERVICE = "andr36oid-gamecomfort";
    private static final File DIR = new File("/data/misc/andr36oid-gamecomfort");
    private static final long TIMEOUT_MS = 5000;
    private static final Object LOCK = new Object();

    @Override
    public Map<String, Found> read(List<String> paths) {
        final Map<String, Found> map = new HashMap<>();
        final StringBuilder request = new StringBuilder();
        for (String p : paths) {
            request.append("read\t").append(p).append('\n');
        }
        for (String[] line : run(request.toString())) {
            if (line.length == 5 && line[0].equals("found")) {
                final String text = readText(new File(DIR, "f" + line[4]));
                if (text != null) {
                    map.put(line[3], new Found(line[3], num(line[1]), num(line[2]), text));
                }
            }
        }
        return map;
    }

    @Override
    public Map<String, Found> stat(List<String> paths) {
        final Map<String, Found> map = new HashMap<>();
        final StringBuilder request = new StringBuilder();
        for (String p : paths) {
            request.append("stat\t").append(p).append('\n');
        }
        for (String[] line : run(request.toString())) {
            if (line.length == 4 && line[0].equals("stat")) {
                map.put(line[3], new Found(line[3], num(line[1]), num(line[2]), null));
            }
        }
        return map;
    }

    @Override
    public List<Found> list(List<String> dirs, List<String> prefixes) {
        final List<Found> list = new ArrayList<>();
        final StringBuilder request = new StringBuilder();
        for (String d : dirs) {
            request.append("dir\t").append(d).append('\n');
        }
        for (String p : prefixes) {
            request.append("prefix\t").append(p).append('\n');
        }
        for (String[] line : run(request.toString())) {
            if (line.length == 4 && line[0].equals("file")) {
                list.add(new Found(line[3], num(line[1]), num(line[2]), null));
            }
        }
        return list;
    }

    /** The helper's answer lines, split at tabs; none if it didn't answer in time. */
    private static List<String[]> run(String request) {
        final List<String[]> lines = new ArrayList<>();
        synchronized (LOCK) {
            final File status = new File(DIR, "status");
            final File out = new File(DIR, "out");
            try {
                // One started by a previous, timed out request would answer the wrong one
                waitForStop(TIMEOUT_MS);
                status.delete();
                out.delete();
                write(new File(DIR, "request"), request);
                SystemProperties.set("ctl.start", SERVICE);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Can't start the file helper", e);
                return lines;
            }
            final long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
            while (true) {
                final String s = readText(status);
                if (s != null && s.trim().equals("done")) {
                    break;
                }
                if (s != null && s.startsWith("error")) {
                    Log.w(TAG, "File helper: " + s.trim());
                    return lines;
                }
                if (SystemClock.uptimeMillis() > end) {
                    Log.w(TAG, "File helper didn't answer");
                    return lines;
                }
                SystemClock.sleep(30);
            }
            final String text = readText(out);
            if (text != null) {
                for (String line : text.split("\n")) {
                    if (!line.isEmpty()) {
                        lines.add(line.split("\t", -1));
                    }
                }
            }
        }
        return lines;
    }

    private static void waitForStop(long ms) {
        final long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            final String state = SystemProperties.get("init.svc." + SERVICE, "");
            if (!"running".equals(state) && !"restarting".equals(state)) {
                return;
            }
            SystemClock.sleep(30);
        }
    }

    private static void write(File file, String text) throws IOException {
        final File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("can't write " + file);
        }
    }

    static String readText(File file) {
        try (Reader in = new InputStreamReader(new FileInputStream(file),
                StandardCharsets.UTF_8)) {
            final StringBuilder sb = new StringBuilder();
            final char[] buf = new char[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
