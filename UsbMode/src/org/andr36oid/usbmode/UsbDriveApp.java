package org.andr36oid.usbmode;

import android.app.Application;

/** A new app process must never inherit another process's drive session. */
public class UsbDriveApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        UsbDriveControl.stop();
        UsbDeviceLock.restore(this);
    }
}
