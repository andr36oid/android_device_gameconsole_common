package org.andr36oid.perfoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Draws the overlay: a small non-focusable, non-touchable system window in a screen corner,
 * so it never takes buttons away from the game. Updates once a second (or as set) while the
 * screen is on and stops completely while it is off. The readings are taken on a worker thread,
 * reading SurfaceFlinger's dumps can take a moment; only the text is set on the main thread.
 */
public class HudService extends Service
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String ACTION_STOP = "org.andr36oid.perfoverlay.action.STOP";

    private static final String CHANNEL = "overlay";
    private static final int NOTIFICATION_ID = 1;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private HandlerThread mWorker;
    private Handler mWorkerHandler;
    private Stats mStats;

    private WindowManager mWindowManager;
    private TextView mView;
    private boolean mTicking;
    private CharSequence mShown;

    /** Replaced on every settings change, read on the worker thread. */
    private volatile HudFormat mFormat;
    private volatile long mIntervalMs;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            mWorkerHandler.postDelayed(this, mIntervalMs);
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            setTicking(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification());
        mStats = new Stats(this);
        mWorker = new HandlerThread("PerfOverlay");
        mWorker.start();
        mWorkerHandler = new Handler(mWorker.getLooper());

        Hud.migrate(this);
        mWindowManager = getSystemService(WindowManager.class);
        mView = new TextView(this);
        loadSettings();
        mWindowManager.addView(mView, layoutParams());

        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenReceiver, filter);
        Hud.prefs(this).registerOnSharedPreferenceChangeListener(this);

        setTicking(getSystemService(PowerManager.class).isInteractive());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // "Turn off" in the notification
            Hud.setEnabled(this, false);
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        setTicking(false);
        Hud.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        unregisterReceiver(mScreenReceiver);
        mWindowManager.removeViewImmediate(mView);
        mWorker.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (Hud.KEY_ENABLED.equals(key)) return;
        settingsChanged();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // A new accent colour comes as a configuration change
        settingsChanged();
    }

    private void settingsChanged() {
        loadSettings();
        mWindowManager.updateViewLayout(mView, layoutParams());
        if (mTicking) {
            // Show the change right away
            mShown = null;
            mWorkerHandler.removeCallbacks(mTick);
            mWorkerHandler.post(mTick);
        }
    }

    private void loadSettings() {
        final HudFormat format = new HudFormat(this);
        format.style(mView);
        mIntervalMs = Hud.intervalMs(Hud.prefs(this));
        mFormat = format;
    }

    private void setTicking(boolean ticking) {
        if (ticking == mTicking) {
            return;
        }
        mTicking = ticking;
        mWorkerHandler.removeCallbacks(mTick);
        if (ticking) {
            // A first sample now, so the second one a second later has something to compare to.
            mWorkerHandler.post(() -> mStats.reset());
            mWorkerHandler.post(mTick);
        }
    }

    /** On the worker thread: takes the readings and hands the text to the main thread. */
    private void update() {
        final HudFormat format = mFormat;
        mStats.sample(format.needsMoreThanFps());
        final CharSequence text = format.format(mStats);
        mHandler.post(() -> {
            // Only redraw on changes, every redraw is a frame SurfaceFlinger has to put up.
            if (mTicking && !TextUtils.equals(text, mShown)) {
                mShown = text;
                mView.setText(text);
            }
        });
    }

    private WindowManager.LayoutParams layoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // A system overlay, so it stays on top of the shade and of screens that hide
                // app overlays, and doesn't trigger the "displaying over other apps" notice.
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("PerfOverlay");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.gravity = mFormat.gravity();
        // x is an offset from the centre for centred positions
        lp.x = (lp.gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
                ? 0 : dp(4);
        lp.y = dp(4);
        return lp;
    }

    private Notification buildNotification() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, OverlayActivity.class), PendingIntent.FLAG_IMMUTABLE);
        final PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, HudService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_perf_overlay)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notification_turn_off), stop).build())
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
