package org.andr36oid.firststart;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * The home screen while the first-start tutorial runs. It has no tutorial logic: each time it
 * comes to the front (the setup wizard ended, FN was pressed, the console started) it tells the
 * quick start guide, and the guide shows its tutorial again. Its own page only shows for a
 * moment, or if the tutorial doesn't come back: then A goes back to the tutorial and Skip
 * tutorial ends it, which gives the home screen back to Daijishou.
 *
 * Daijishou only becomes home once its library is written (the guide waits for
 * SETTING_DAIJISHOU_READY from gameconsole-setup). Until then this page says the games are being
 * prepared, and tells the guide again the moment they are, in case the guide was stopped.
 */
public class HomeActivity extends Activity {

    private static final String TAG = "FirstStart";

    private static final String GUIDE_PACKAGE = "org.andr36oid.guide";
    private static final String GUIDE_RECEIVER = GUIDE_PACKAGE + ".FirstStartReceiver";
    /** Home came to the front: after the wizard, after FN, after a start. */
    private static final String ACTION_HOME = GUIDE_PACKAGE + ".action.FIRST_START_HOME";
    /** The user asked for the tutorial again. */
    private static final String ACTION_OPEN = GUIDE_PACKAGE + ".action.FIRST_START_OPEN";
    /** The user wants to skip the tutorial and go to the real home screen. */
    private static final String ACTION_SKIP = GUIDE_PACKAGE + ".action.FIRST_START_SKIP";
    /** Settings.Global, set by gameconsole-setup once Daijishou's library is written. */
    private static final String SETTING_DAIJISHOU_READY = "andr36oid_daijishou_ready";
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mResumed;
    private boolean mReady;

    /**
     * The steps on the page, in the order the setup service does them, with the stage codes
     * (sys.andr36oid.setup_stage) that belong to each.
     */
    private static final int[] STEP_LABELS = {
            R.string.step_apps, R.string.step_emulators, R.string.step_folders,
            R.string.step_appsettings, R.string.step_library, R.string.step_cheats,
    };
    private static final String[][] STEP_STAGES = {
            {"apps"}, {"controls", "cores"}, {"storage", "folders", "games"},
            {"appsettings"}, {"library"}, {"cheats"},
    };
    /** The furthest step seen: the ones before it are done (the app page's own installs can
     *  bring "apps" back later, which doesn't undo them). */
    private int mFurthestStep = -1;
    private TextView[] mStepViews;
    private final Runnable mProgress = new Runnable() {
        @Override
        public void run() {
            if (!mResumed || mReady) return;
            updateProgress();
            mHandler.postDelayed(this, 1000);
        }
    };
    private final ContentObserver mReadyObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            if (showState()) {
                tell(ACTION_HOME);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.home_activity);
        findViewById(R.id.back).setOnClickListener(v -> tell(ACTION_OPEN));
        findViewById(R.id.skip).setOnClickListener(v -> tell(ACTION_SKIP));
        final LinearLayout steps = findViewById(R.id.preparing_steps);
        mStepViews = new TextView[STEP_LABELS.length];
        for (int i = 0; i < STEP_LABELS.length; i++) {
            final TextView row = new TextView(this);
            row.setTextSize(13);
            row.setPadding(0, 1, 0, 1);
            steps.addView(row);
            mStepViews[i] = row;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SETTING_DAIJISHOU_READY), false, mReadyObserver);
        showState();
        findViewById(R.id.back).requestFocus();
        tell(ACTION_HOME);
    }

    @Override
    protected void onPause() {
        mResumed = false;
        mHandler.removeCallbacks(mProgress);
        super.onPause();
        getContentResolver().unregisterContentObserver(mReadyObserver);
    }

    /** The page for now: "preparing" while Daijishou isn't ready. Returns whether it is. */
    private boolean showState() {
        // Both home screens need the installed apps, emulator cores and game folders.
        final boolean ready = Settings.Global.getInt(getContentResolver(),
                SETTING_DAIJISHOU_READY, 0) == 1;
        ((TextView) findViewById(R.id.title)).setText(
                ready ? R.string.home_title : R.string.preparing_title);
        ((TextView) findViewById(R.id.body)).setText(
                ready ? R.string.home_body : R.string.preparing_body);
        // while preparing the buttons take no room: the page needs all of 640x480
        findViewById(R.id.buttons).setVisibility(ready ? View.VISIBLE : View.GONE);
        mReady = ready;
        findViewById(R.id.preparing_progress).setVisibility(ready ? View.GONE : View.VISIBLE);
        mHandler.removeCallbacks(mProgress);
        if (!ready) mHandler.post(mProgress);
        return ready;
    }

    private void updateProgress() {
        final String stage = SystemProperties.get("sys.andr36oid.setup_stage", "");
        int message;
        switch (stage.split(":", 2)[0]) {
            case "apps": message = R.string.preparing_apps; break;
            case "controls": message = R.string.preparing_controls; break;
            case "cores": message = R.string.preparing_cores; break;
            case "storage": message = R.string.preparing_storage; break;
            case "folders": message = R.string.preparing_folders; break;
            case "games": message = R.string.preparing_games; break;
            case "appsettings": message = R.string.preparing_appsettings; break;
            case "library": message = R.string.preparing_library; break;
            case "cheats": message = R.string.preparing_cheats; break;
            default: message = R.string.preparing_starting;
        }
        String text = getString(message);
        if (stage.startsWith("cores:")) {
            final String[] parts = stage.split(":");
            try {
                final int done = Integer.parseInt(parts[1]);
                final int total = Integer.parseInt(parts[2]);
                if (done > 0 && done <= total && total <= 128) {
                    text = getString(R.string.preparing_cores_count, done, total);
                }
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                // A partial or older status still has the generic stage text.
            }
        }
        final TextView status = findViewById(R.id.preparing_status);
        if (!text.contentEquals(status.getText())) status.setText(text);
        final String name = SystemProperties.get("sys.andr36oid.setup_app", "");
        final TextView app = findViewById(R.id.preparing_app);
        app.setVisibility(name.isEmpty() ? View.GONE : View.VISIBLE);
        if (!name.isEmpty()) {
            final String installing = getString(R.string.preparing_installing, name);
            if (!installing.contentEquals(app.getText())) app.setText(installing);
        }
        showSteps(stage);
        showBar();
        showStats(stage);
    }

    /** Which step a stage code belongs to, -1 for none. */
    private static int stepOf(String stage) {
        final String code = stage.split(":", 2)[0];
        for (int i = 0; i < STEP_STAGES.length; i++) {
            for (String s : STEP_STAGES[i]) {
                if (s.equals(code)) return i;
            }
        }
        return -1;
    }

    /** A tick for the done steps, an arrow for the current one, a dot for the rest. */
    private void showSteps(String stage) {
        final int current = stepOf(stage);
        if (current > mFurthestStep) mFurthestStep = current;
        for (int i = 0; i < mStepViews.length; i++) {
            final boolean now = i == current;
            final boolean done = !now && i <= mFurthestStep;
            final String mark = now ? "\u25B8  " : done ? "\u2713  " : "\u00B7  ";
            final String text = mark + getString(STEP_LABELS[i]);
            final TextView row = mStepViews[i];
            if (!text.contentEquals(row.getText())) row.setText(text);
            row.setTypeface(null, now ? Typeface.BOLD : Typeface.NORMAL);
            row.setAlpha(now ? 1f : done ? 0.75f : 0.4f);
        }
    }

    /** The bar by the planned work's bytes, and the time left; spinning while unknown. */
    private void showBar() {
        final ProgressBar bar = findViewById(R.id.preparing_bar);
        final TextView pctView = findViewById(R.id.preparing_pct);
        final TextView etaView = findViewById(R.id.preparing_eta);
        final int pct = intProp("sys.andr36oid.setup_pct");
        final int eta = intProp("sys.andr36oid.setup_eta");
        if (pct < 0 || pct > 100) {
            bar.setIndeterminate(true);
            pctView.setText("");
        } else {
            bar.setIndeterminate(false);
            bar.setProgress(pct);
            pctView.setText(String.format(Locale.ROOT, "%d%%", pct));
        }
        if (eta > 0) {
            etaView.setText(getString(R.string.preparing_eta, DateUtils.formatElapsedTime(eta)));
        } else if (eta < 0 && pct >= 0) {
            etaView.setText(R.string.preparing_eta_unknown);
        } else {
            etaView.setText("");
        }
    }

    /**
     * Stats for nerds: sys.andr36oid.setup_stats is "card=<MB/s> wr=<MB> apps=<done>/<all>
     * t=<s> free=<MB>". Hidden while the service doesn't report it.
     */
    private void showStats(String stage) {
        final TextView view = findViewById(R.id.preparing_stats);
        final String stats = SystemProperties.get("sys.andr36oid.setup_stats", "");
        if (stats.isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }
        String card = "-", wr = "-", apps = "-", t = "-", free = "-";
        for (String part : stats.split(" ")) {
            final int eq = part.indexOf('=');
            if (eq <= 0) continue;
            final String key = part.substring(0, eq), value = part.substring(eq + 1);
            switch (key) {
                case "card": if (!value.startsWith("0.0")) card = value + " MB/s"; break;
                case "wr": wr = value + " MB"; break;
                case "apps": apps = value; break;
                case "t":
                    try {
                        t = DateUtils.formatElapsedTime(Long.parseLong(value));
                    } catch (NumberFormatException e) {
                        // left as "-"
                    }
                    break;
                case "free":
                    try {
                        final long mb = Long.parseLong(value);
                        free = mb >= 1024
                                ? String.format(Locale.ROOT, "%.1f GB", mb / 1024.0) : mb + " MB";
                    } catch (NumberFormatException e) {
                        // left as "-"
                    }
                    break;
            }
        }
        final String text = String.format(Locale.ROOT,
                "card %-10s wrote %s\n"
                        + "apps %-10s free  %s\n"
                        + "time %-10s stage %s",
                card, wr, apps, free, t, stage.isEmpty() ? "-" : stage);
        if (!text.contentEquals(view.getText())) view.setText(text);
        view.setVisibility(View.VISIBLE);
    }

    private static int intProp(String name) {
        try {
            return Integer.parseInt(SystemProperties.get(name, ""));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // The home screen has nowhere to go back to
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** The guide's receiver only takes this from apps holding its FIRST_START permission. */
    private void tell(String action) {
        try {
            sendBroadcast(new Intent(action)
                    .setComponent(new ComponentName(GUIDE_PACKAGE, GUIDE_RECEIVER))
                    // Also if the guide was force-stopped
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND
                            | Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't reach the quick start guide", e);
        }
    }
}
