package org.andr36oid.usbmode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Submission and cancellation finish before another activity can submit a request. */
final class UsbRequest {
    static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger mVersion = new AtomicInteger();
    private int mPending;
    private boolean mImage;

    int begin(boolean image, boolean busy) {
        if (pending() || busy) return 0;
        mImage = image; mPending = mVersion.incrementAndGet();
        return mPending;
    }
    boolean pending() { return mPending != 0; }
    boolean image() { return mImage; }
    boolean current(int operation) { return mVersion.get() == operation; }
    void cancel() { mVersion.incrementAndGet(); }
    boolean finish(int operation) {
        if (mPending != operation) return false;
        mPending = 0; return true;
    }
}
