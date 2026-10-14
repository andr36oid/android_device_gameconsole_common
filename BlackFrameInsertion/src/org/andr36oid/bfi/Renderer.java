package org.andr36oid.bfi;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceHolder;

/**
 * Draws the overlay once per display refresh on its own thread: black in the black slots of
 * the pattern, see-through in the others. The slot comes from the frame time, counted from
 * the first frame, never from the number of callbacks, so a missed callback doesn't shift
 * the pattern (as in BFI's SlotClock). A buffer is only posted when the slot changes between
 * black and see-through.
 */
final class Renderer implements Choreographer.FrameCallback {

    private static final String TAG = "BfiRenderer";

    private final SurfaceHolder mHolder;
    private final long mPeriodNs;
    private HandlerThread mThread;
    private Choreographer mChoreographer;
    private volatile boolean mActive;
    private long mAnchor = -1;
    private long mLastSlot = -1;
    private int mShown = -1; // 1 black, 0 see-through, -1 nothing posted yet

    Renderer(SurfaceHolder holder, float hz) {
        mHolder = holder;
        mPeriodNs = Math.round(1e9 / hz);
    }

    void start() {
        mActive = true;
        mThread = new HandlerThread("bfi-render", Process.THREAD_PRIORITY_DISPLAY);
        mThread.start();
        new Handler(mThread.getLooper()).post(() -> {
            mChoreographer = Choreographer.getInstance();
            if (mActive) mChoreographer.postFrameCallback(this);
        });
    }

    /** Stops drawing and waits for the thread, so the surface can go right after. */
    void stop() {
        mActive = false;
        if (mThread == null) return;
        new Handler(mThread.getLooper()).post(() -> {
            if (mChoreographer != null) mChoreographer.removeFrameCallback(this);
            mThread.quitSafely();
        });
        try {
            mThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        mThread = null;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mActive) return;
        if (mAnchor < 0) mAnchor = frameTimeNanos;
        final long slot = Math.round((double) (frameTimeNanos - mAnchor) / mPeriodNs);
        if (slot > mLastSlot) {
            mLastSlot = slot;
            final int normal = Bfi.sNormal;
            final int black = Bfi.sBlack;
            draw(slot % (normal + black) >= normal ? 1 : 0);
        }
        mChoreographer.postFrameCallback(this);
    }

    private void draw(int black) {
        if (black == mShown) return;
        final Canvas canvas;
        try {
            canvas = mHolder.lockHardwareCanvas();
        } catch (RuntimeException e) {
            Log.w(TAG, "surface gone", e);
            return;
        }
        if (canvas == null) return;
        try {
            if (black == 1) {
                canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC);
            } else {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            }
        } finally {
            mHolder.unlockCanvasAndPost(canvas);
        }
        mShown = black;
    }
}
