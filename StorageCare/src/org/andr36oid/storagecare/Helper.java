package org.andr36oid.storagecare;

import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

/**
 * Talks to the andr36oid-storagecare init service (helper/andr36oid-storagecare.sh), which
 * runs the Engine as root: we write a request and start it, it writes one status line and
 * its results into /data/misc/andr36oid-storagecare.
 */
final class Helper {

    private static final String TAG = "StorageCare";
    static final String SERVICE = "andr36oid-storagecare";
    static final File DIR = new File("/data/misc/andr36oid-storagecare");

    private Helper() {
    }

    static String scanRequest(android.content.Context c) {
        return "action=scan\nkeep_states=" + Prefs.keepStates(c) + "\nkeep_backups="
                + Prefs.keepBackups(c) + "\ndups=" + (Prefs.dups(c) ? 1 : 0) + "\n";
    }

    static String deleteRequest(android.content.Context c) {
        return "action=delete\nkeep_states=" + Prefs.keepStates(c) + "\nkeep_backups="
                + Prefs.keepBackups(c) + "\n";
    }

    static final String HEALTH_REQUEST = "action=health\n";

    /** Writes the list for a delete request: "GROUP<tab>PATH[<tab>KEEP]" per line. */
    static boolean writeDeleteList(Collection<Scan.Item> items) {
        final StringBuilder sb = new StringBuilder();
        for (Scan.Item i : items) {
            sb.append(i.group).append('\t').append(i.path);
            if (i.keep != null) sb.append('\t').append(i.keep);
            sb.append('\n');
        }
        try {
            write(new File(DIR, "delete"), sb.toString());
            return true;
        } catch (IOException e) {
            Log.w(TAG, "can't write the delete list", e);
            return false;
        }
    }

    /** Starts the helper, false if it's busy or can't be started. */
    static boolean start(String request) {
        if (running()) return false;
        try {
            new File(DIR, "status").delete();
            write(new File(DIR, "request"), request);
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

    /** "running …", "done …", "error CODE", or "" before the helper wrote any. */
    static String status() {
        final String s = Engine.read(new File(DIR, "status"));
        return s != null ? s.trim() : "";
    }

    /** The last scan, empty if there is none. */
    static Scan scan() {
        return Scan.parse(Engine.read(new File(DIR, "scan")));
    }

    static Map<String, String> health() {
        return Engine.keyValues(Engine.read(new File(DIR, "health")));
    }

    private static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Runs a request to its end on the calling (background) thread; false on a timeout. */
    static boolean runAndWait(String request, long timeoutMs) {
        if (!start(request)) return false;
        final long end = SystemClock.elapsedRealtime() + timeoutMs;
        final long grace = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(300);
            final String s = status();
            if (s.startsWith("done") || s.startsWith("error")) return s.startsWith("done");
            if (!running() && SystemClock.elapsedRealtime() > grace) return false;
        }
        return false;
    }

    /**
     * Starts a request (or follows one that's already running) and reports its progress and
     * end on the handler's thread.
     */
    static final class Runner {

        interface Listener {
            void onProgress(String status);

            /** The final status line: "done …" or "error CODE". */
            void onDone(String status);
        }

        private static final long POLL_MS = 500;
        /** How long the service may take to show up after ctl.start. */
        private static final long START_GRACE_MS = 5000;

        private final Handler mHandler;
        private final Listener mListener;
        private final Runnable mPoll = this::poll;
        private long mStartedAt;
        private boolean mActive;

        Runner(Handler handler, Listener listener) {
            mHandler = handler;
            mListener = listener;
        }

        boolean start(String request) {
            if (!Helper.start(request)) return false;
            mStartedAt = SystemClock.elapsedRealtime();
            mActive = true;
            mListener.onProgress("running start");
            mHandler.removeCallbacks(mPoll);
            mHandler.postDelayed(mPoll, POLL_MS);
            return true;
        }

        /** Follows a run that someone else started; true if one is running. */
        boolean follow() {
            if (!Helper.running()) return false;
            mStartedAt = SystemClock.elapsedRealtime();
            mActive = true;
            poll();
            return true;
        }

        boolean active() {
            return mActive;
        }

        void stop() {
            mActive = false;
            mHandler.removeCallbacks(mPoll);
        }

        private void poll() {
            mHandler.removeCallbacks(mPoll);
            if (!mActive) return;
            final String s = status();
            final boolean finished = s.startsWith("done") || s.startsWith("error");
            if (Helper.running() || (!finished
                    && SystemClock.elapsedRealtime() - mStartedAt < START_GRACE_MS)) {
                if (s.startsWith("running")) mListener.onProgress(s);
                mHandler.postDelayed(mPoll, POLL_MS);
                return;
            }
            mActive = false;
            mListener.onDone(finished ? s : "error start");
        }
    }
}
