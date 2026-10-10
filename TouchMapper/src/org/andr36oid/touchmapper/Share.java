package org.andr36oid.touchmapper;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * "Save layouts to the SD card" and "Load layouts from the SD card". We may not touch
 * EASYROMS ourselves (system user), so the andr36oid-touchmap-share init service
 * (helper/andr36oid-touchmap-share.sh) copies the files; an import is then checked and added
 * here ({@link SharedLayouts}).
 */
final class Share {

    private static final String TAG = "TouchControls";
    static final String SERVICE = "andr36oid-touchmap-share";
    static final File DIR = new File("/data/misc/andr36oid-touchmap");

    private static final long POLL_MS = 300;
    /** Give up if the helper hasn't answered by then; copying ~200 small files is quick. */
    private static final long TIMEOUT_MS = 20000;

    /** How it ended, for the message. */
    static final class Outcome {
        boolean ok;
        /** "nocard", "none", "empty", "copy", "start" when not ok. */
        String error;
        int exported;
        int added;
        int kept;
        int bad;
    }

    interface Listener {
        void onDone(Outcome outcome);
    }

    private Share() {
    }

    static void export(Context context, Handler handler, Listener listener) {
        run(context, handler, "export", listener);
    }

    static void load(Context context, Handler handler, Listener listener) {
        run(context, handler, "import", listener);
    }

    private static void run(Context context, Handler handler, String request, Listener listener) {
        final Context app = context.getApplicationContext();
        try {
            new File(DIR, "status").delete();
            try (FileOutputStream out = new FileOutputStream(new File(DIR, "request"))) {
                out.write(request.getBytes(StandardCharsets.UTF_8));
            }
            SystemProperties.set("ctl.start", SERVICE);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Couldn't start " + SERVICE, e);
            final Outcome o = new Outcome();
            o.error = "start";
            handler.post(() -> listener.onDone(o));
            return;
        }
        final long started = SystemClock.elapsedRealtime();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                final String status = SharedLayouts.readFile(new File(DIR, "status"));
                if (status == null || status.trim().isEmpty()) {
                    if (SystemClock.elapsedRealtime() - started < TIMEOUT_MS) {
                        handler.postDelayed(this, POLL_MS);
                    } else {
                        final Outcome o = new Outcome();
                        o.error = "start";
                        listener.onDone(o);
                    }
                    return;
                }
                // Checking a few dozen small files is quick, but not on the main thread
                new Thread(() -> {
                    final Outcome o = finish(app, status.trim());
                    handler.post(() -> listener.onDone(o));
                }, "TouchControlsShare").start();
            }
        }, POLL_MS);
    }

    /** Turns the helper's status line into an outcome; for an import, adds the layouts. */
    private static Outcome finish(Context context, String status) {
        final Outcome o = new Outcome();
        final String[] f = status.split("\\s+");
        if (f.length >= 3 && "done".equals(f[0]) && "export".equals(f[1])) {
            o.ok = true;
            o.exported = number(f[2]);
        } else if (f.length >= 3 && "done".equals(f[0]) && "import".equals(f[1])) {
            final File in = new File(DIR, "in");
            final SharedLayouts.Result r = SharedLayouts.read(in, Profiles.packages(),
                    pkg -> EditReceiver.canHaveControls(context, pkg));
            o.ok = true;
            o.kept = r.kept;
            o.bad = r.bad + (f.length >= 4 ? number(f[3]) : 0);
            for (Profile p : r.added) {
                if (Profiles.save(context, p)) {
                    o.added++;
                } else {
                    o.bad++;
                }
            }
            deleteAll(in);
        } else {
            o.error = f.length >= 2 && "error".equals(f[0]) ? f[1] : "start";
            if ("empty".equals(o.error)) {
                deleteAll(new File(DIR, "in"));
            }
        }
        Log.i(TAG, "SD card " + status + ": added " + o.added + ", kept " + o.kept
                + ", unusable " + o.bad);
        return o;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void deleteAll(File dir) {
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }
}
