package org.andr36oid.gamecomfort;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * A full width strip at the top of the screen for a few seconds, drawn as a system overlay so
 * it shows on top of full screen games. It never takes focus or touches, the game keeps the
 * buttons.
 */
final class Banner {

    private static final long SHOW_MS = 5000;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHide = this::hide;
    private View mView;

    Banner(Context context) {
        // Colours follow the system's light or dark theme, like a stock dialog or panel.
        mContext = new ContextThemeWrapper(context,
                android.R.style.Theme_DeviceDefault_DayNight);
        mWindowManager = context.getSystemService(WindowManager.class);
    }

    void show(CharSequence text) {
        hide();
        mView = LayoutInflater.from(mContext).inflate(R.layout.banner, null);
        ((TextView) mView.findViewById(R.id.text)).setText(text);
        mWindowManager.addView(mView, layoutParams());
        mHandler.postDelayed(mHide, SHOW_MS);
    }

    void hide() {
        mHandler.removeCallbacks(mHide);
        if (mView != null) {
            mWindowManager.removeViewImmediate(mView);
            mView = null;
        }
    }

    private static WindowManager.LayoutParams layoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // Above apps, the status bar and the shade, and no "displaying over other
                // apps" notice.
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("LowBatteryBanner");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.gravity = Gravity.TOP;
        lp.windowAnimations = android.R.style.Animation_Toast;
        return lp;
    }
}
