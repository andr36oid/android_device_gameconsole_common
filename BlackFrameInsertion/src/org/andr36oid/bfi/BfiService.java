package org.andr36oid.bfi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Keeps the black frame overlay on screen until it's turned off, the screen goes below
 * 100 Hz, or the console restarts. While the screen is off the overlay is taken away and it
 * comes back when the screen comes on. The overlay never takes touches or focus.
 */
public class BfiService extends Service {

    private static final String TAG = "BfiService";
    static final String ACTION_STOP = "org.andr36oid.bfi.STOP";
    private static final String CHANNEL = "bfi";
    private static final int NOTIFICATION_ID = 1;

    /** for the Settings page's switch */
    static volatile boolean sRunning;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private WindowManager mWindows;
    private DisplayManager mDisplays;
    private SurfaceView mOverlay;
    private Renderer mRenderer;
    private int mModeId = -1;
    private boolean mStarted;

    private final BroadcastReceiver mScreen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                hide();
            } else {
                show();
            }
        }
    };

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY || mOverlay == null) return;
            final Display display = mDisplays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display.getRefreshRate() < Bfi.MIN_HZ) {
                Toast.makeText(BfiService.this, R.string.bfi_stopped_rate,
                        Toast.LENGTH_LONG).show();
                stopSelf();
            } else if (display.getMode().getModeId() != mModeId) {
                // another rate: start again with its frame time
                hide();
                show();
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!mStarted) {
            mStarted = true;
            mWindows = getSystemService(WindowManager.class);
            mDisplays = getSystemService(DisplayManager.class);
            Bfi.load(this);
            startForeground(NOTIFICATION_ID, notification());
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(mScreen, filter);
            mDisplays.registerDisplayListener(mDisplayListener, mHandler);
            sRunning = true;
            show();
        }
        // not restarted after the process dies: flashing must never come back by itself
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        if (mStarted) {
            mDisplays.unregisterDisplayListener(mDisplayListener);
            unregisterReceiver(mScreen);
        }
        hide();
        super.onDestroy();
    }

    private void show() {
        if (mOverlay != null) return;
        final Display display = mDisplays.getDisplay(Display.DEFAULT_DISPLAY);
        final float hz = display.getRefreshRate();
        if (hz < Bfi.MIN_HZ) {
            Toast.makeText(this, R.string.bfi_stopped_rate, Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        mModeId = display.getMode().getModeId();
        final SurfaceView overlay = new SurfaceView(this);
        overlay.setZOrderOnTop(true);
        overlay.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        overlay.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                mRenderer = new Renderer(holder, hz);
                mRenderer.start();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopRenderer();
            }
        });
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.setTitle("BlackFrameInsertion");
        try {
            mWindows.addView(overlay, params);
            mOverlay = overlay;
        } catch (RuntimeException e) {
            Log.e(TAG, "overlay refused", e);
            stopSelf();
        }
    }

    private void hide() {
        stopRenderer();
        if (mOverlay == null) return;
        try {
            mWindows.removeView(mOverlay);
        } catch (RuntimeException e) {
            Log.w(TAG, "overlay already gone", e);
        }
        mOverlay = null;
    }

    private void stopRenderer() {
        if (mRenderer != null) {
            mRenderer.stop();
            mRenderer = null;
        }
    }

    private Notification notification() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.bfi_channel), NotificationManager.IMPORTANCE_LOW));
        final PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, BfiService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, BfiActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_bfi)
                .setContentTitle(getString(R.string.bfi_notification))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.bfi_stop),
                        stop).build())
                .build();
    }
}
