package org.andr36oid.firststart;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

/**
 * The home screen while the first-start tutorial runs. It has no tutorial logic: each time it
 * comes to the front (the setup wizard ended, FN was pressed, the console started) it tells the
 * quick start guide, and the guide shows its tutorial again. Its own page only shows for a
 * moment, or if the tutorial doesn't come back: then A goes back to the tutorial and Skip
 * tutorial ends it, which gives the home screen back to Daijishou.
 *
 * Daijishou only becomes home once its library is written (the guide waits for
 * SETTING_DAIJISHOU_READY from gameconsole-emu). Until then this page says the games are being
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
    /** Settings.Global, set by gameconsole-emu once Daijishou's library is written. */
    private static final String SETTING_DAIJISHOU_READY = "andr36oid_daijishou_ready";
    /** Settings.Global, the home screen chosen in the setup wizard (empty: Daijishou). */
    private static final String SETTING_HOME = "andr36oid_home";
    private static final String DAIJISHOU = "com.magneticchen.daijishou";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final long mWaitingSince = SystemClock.elapsedRealtime();
    private boolean mResumed;
    private boolean mReady;
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
        final String home = Settings.Global.getString(getContentResolver(), SETTING_HOME);
        final boolean ready = (home != null && !home.isEmpty() && !DAIJISHOU.equals(home))
                || Settings.Global.getInt(getContentResolver(), SETTING_DAIJISHOU_READY, 0) == 1;
        ((TextView) findViewById(R.id.title)).setText(
                ready ? R.string.home_title : R.string.preparing_title);
        ((TextView) findViewById(R.id.body)).setText(
                ready ? R.string.home_body : R.string.preparing_body);
        findViewById(R.id.buttons).setVisibility(ready ? View.VISIBLE : View.INVISIBLE);
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
        ((TextView) findViewById(R.id.preparing_elapsed)).setText(getString(
                R.string.preparing_elapsed, DateUtils.formatElapsedTime(
                        (SystemClock.elapsedRealtime() - mWaitingSince) / 1000)));
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
