package org.andr36oid.usbmode;

/** A queued focus request only belongs to the page that scheduled it. */
final class UsbPageFocus {
    private int mVersion;
    private boolean mPending;
    private String mTarget = "";

    int begin(boolean samePage, String target) {
        mTarget = samePage ? target : "";
        mPending = true;
        return ++mVersion;
    }
    int version() { return mVersion; }
    String target() { return mTarget; }
    boolean ready(int version, boolean resumed, boolean windowFocused, boolean dialogShowing) {
        return mPending && version == mVersion && resumed && windowFocused && !dialogShowing;
    }
    void applied(int version) { if (version == mVersion) mPending = false; }
}
