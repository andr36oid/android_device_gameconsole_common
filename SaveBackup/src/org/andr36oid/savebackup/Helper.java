package org.andr36oid.savebackup;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Talks to the andr36oid-savebackup init service (helper/andr36oid-savebackup.sh), which
 * runs the Engine as root: we write a request and start it, it writes one status line and
 * its results into /data/misc/andr36oid-savebackup.
 */
final class Helper {

    private static final String TAG = "SaveBackup";
    static final String SERVICE = "andr36oid-savebackup";
    static final File DIR = new File("/data/misc/andr36oid-savebackup");

    private Helper() {
    }

    static String backupRequest(Context c, String reason, boolean usb) {
        return "action=backup\nreason=" + reason + "\nkeep=" + Prefs.keep(c) + "\n"
                + (usb ? "usb=1\n" : "");
    }

    static String listRequest() {
        return "action=list\n";
    }

    static String planRequest(String zip, String game) {
        return "action=plan\nzip=" + zip + "\n" + (game != null ? "game=" + game + "\n" : "");
    }

    static String restoreRequest(Context c, String zip, String game, boolean newer) {
        return "action=restore\nzip=" + zip + "\n" + (game != null ? "game=" + game + "\n" : "")
                + "newer=" + (newer ? 1 : 0) + "\nkeep=" + Prefs.keep(c) + "\n";
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

    static Index.Last last() {
        return Index.Last.parse(Engine.read(new File(DIR, "last")));
    }

    static List<Index.Backup> index() {
        return Index.parseIndex(Engine.read(new File(DIR, "index")));
    }

    static Index.Plan plan() {
        return Index.parsePlan(Engine.read(new File(DIR, "plan")));
    }

    static Map<String, String> result() {
        return Index.keyValues(Engine.read(new File(DIR, "result")));
    }

    private static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
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
