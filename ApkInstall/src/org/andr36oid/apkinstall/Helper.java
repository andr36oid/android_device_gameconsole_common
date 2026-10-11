package org.andr36oid.apkinstall;

import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to the andr36oid-apkinstall init service (helper/andr36oid-apkinstall.sh), which
 * reads EASYROMS and runs pm as root: we write a request and start it, it writes its
 * status and results into /data/misc/andr36oid-apkinstall.
 */
final class Helper {

    private static final String TAG = "ApkInstall";
    static final String SERVICE = "andr36oid-apkinstall";
    static final File DIR = new File("/data/misc/andr36oid-apkinstall");
    /** How long the service may take to show up after ctl.start. */
    private static final long START_GRACE_MS = 5000;

    /** One APK in EASYROMS/apk. */
    static final class Apk {
        final String name;
        final long size;
        final long mtime;

        Apk(String name, long size, long mtime) {
            this.name = name;
            this.size = size;
            this.mtime = mtime;
        }

        /** Changes when the file is replaced, so a new version is offered again. */
        String key() {
            return name + "|" + size + "|" + mtime;
        }
    }

    /** What happened to one APK. */
    static final class Result {
        final String name;
        final boolean ok;
        final String reason;

        Result(String name, boolean ok, String reason) {
            this.name = name;
            this.ok = ok;
            this.reason = reason;
        }
    }

    interface Progress {
        /** Called on the worker thread for "running N TOTAL NAME". */
        void onProgress(int n, int total, String name);
    }

    private Helper() {
    }

    /** The APKs in EASYROMS/apk; empty when there are none or EASYROMS isn't there. */
    static List<Apk> scan() {
        final List<Apk> list = new ArrayList<>();
        if (!run("action=scan\n", 120_000, null)) {
            return list;
        }
        for (String line : lines(new File(DIR, "list"))) {
            final String[] f = line.split("\t", 3);
            if (f.length < 3) continue;
            try {
                list.add(new Apk(f[2], Long.parseLong(f[0]), Long.parseLong(f[1])));
            } catch (NumberFormatException e) {
                Log.w(TAG, "bad list line: " + line);
            }
        }
        return list;
    }

    /** Installs the APKs one after the other; one result per APK. */
    static List<Result> install(List<Apk> apks, Progress progress) {
        final List<Result> results = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        for (Apk a : apks) sb.append(a.name).append('\n');
        final boolean ran;
        try {
            write(new File(DIR, "install"), sb.toString());
            new File(DIR, "result").delete();
            // a big APK on a slow card can take a while
            ran = run("action=install\n", 10 * 60_000L * Math.max(1, apks.size()), progress);
        } catch (IOException e) {
            Log.w(TAG, "can't write the install list", e);
            for (Apk a : apks) results.add(new Result(a.name, false, "internal error"));
            return results;
        }
        for (String line : lines(new File(DIR, "result"))) {
            final String[] f = line.split("\t", 3);
            if (f.length < 2) continue;
            results.add(new Result(f[1], "ok".equals(f[0]), f.length > 2 ? f[2] : ""));
        }
        if (!ran && results.isEmpty()) {
            for (Apk a : apks) results.add(new Result(a.name, false, "installer didn't run"));
        }
        return results;
    }

    /** Runs a request to its end on the calling (background) thread; false on an error. */
    private static boolean run(String request, long timeoutMs, Progress progress) {
        if (running()) {
            Log.w(TAG, "helper is busy");
            return false;
        }
        try {
            new File(DIR, "status").delete();
            write(new File(DIR, "request"), request);
            SystemProperties.set("ctl.start", SERVICE);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "can't start the helper", e);
            return false;
        }
        final long start = SystemClock.elapsedRealtime();
        String last = "";
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            SystemClock.sleep(300);
            final String s = status();
            if (s.equals("done")) return true;
            if (s.startsWith("error")) {
                Log.i(TAG, "helper: " + s);
                return false;
            }
            if (progress != null && s.startsWith("running ") && !s.equals(last)) {
                last = s;
                final String[] f = s.split(" ", 4);
                if (f.length == 4) {
                    try {
                        progress.onProgress(Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3]);
                    } catch (NumberFormatException e) {
                        // not a progress line
                    }
                }
            }
            if (!running() && SystemClock.elapsedRealtime() - start > START_GRACE_MS
                    && status().isEmpty()) {
                Log.w(TAG, "helper ended without a status");
                return false;
            }
        }
        Log.w(TAG, "helper timed out");
        return false;
    }

    private static boolean running() {
        final String state = SystemProperties.get("init.svc." + SERVICE, "");
        return "running".equals(state) || "restarting".equals(state);
    }

    private static String status() {
        final List<String> l = lines(new File(DIR, "status"));
        return l.isEmpty() ? "" : l.get(0).trim();
    }

    private static List<String> lines(File f) {
        final List<String> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (!line.isEmpty()) out.add(line);
            }
        } catch (IOException e) {
            // not there (yet)
        }
        return out;
    }

    private static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
