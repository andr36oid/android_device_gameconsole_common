package org.andr36oid.touchmapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.view.View;
import android.view.WindowManager;

/**
 * Faint labels where the buttons touch the screen, on top of the game while its touch controls
 * are on. The window never takes focus or touches: the virtual touches go straight through to
 * the game underneath.
 */
final class HintOverlay {

    private static final int HINT_ALPHA = 150;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private HintView mView;

    HintOverlay(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
    }

    void show(Profile profile) {
        if (mView == null) {
            mView = new HintView(mContext);
            mWindowManager.addView(mView, layoutParams());
        }
        mView.setProfile(profile);
    }

    void hide() {
        if (mView != null) {
            mWindowManager.removeViewImmediate(mView);
            mView = null;
        }
    }

    private static WindowManager.LayoutParams layoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                // Above the app, no "displaying over other apps" notice
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("TouchControlsHints");
        // The same full screen the virtual touchscreen covers, bars or not
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return lp;
    }

    private static final class HintView extends View {
        private final MarkerPainter mPainter;
        private Profile mProfile;

        HintView(Context context) {
            super(context);
            mPainter = new MarkerPainter(context);
            mPainter.setAlpha(HINT_ALPHA);
        }

        void setProfile(Profile profile) {
            mProfile = profile;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mProfile == null) {
                return;
            }
            final String shift = mProfile.shift == null ? null
                    : Inputs.label(mProfile.shift) + "+";
            for (Profile.Control c : mProfile.controls) {
                mPainter.draw(canvas, c, getWidth(), getHeight(), shift, 0, c.layer == 1);
            }
        }
    }
}
