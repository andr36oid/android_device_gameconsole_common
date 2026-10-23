package org.andr36oid.usbmode;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;

/** Authorizes the visible Flipper process; the native shared owner handles USB. */
public class FlipperUsbService extends Service {
    private static final String TOKEN = "org.andr36oid.usbmode.FlipperUsb";
    private static final String PREFIX = UsbDeviceControl.PREFIX;
    private final Handler handler = new Handler();
    private int uid, pid;
    private String start = "";
    private long renewed;
    private IBinder death;
    private android.view.WindowId window;
    private final IBinder.DeathRecipient died = () -> handler.post(this::release);
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (pid == 0) return;
            if (SystemClock.elapsedRealtime() - renewed > 1500 || !allowed() ||
                    "stopped".equals(SystemProperties.get("init.svc.andr36oid-controller")) ||
                    "0".equals(SystemProperties.get(PREFIX + "flipper_tick"))) {
                release(); return;
            }
            SystemProperties.set(PREFIX + "flipper_tick", Long.toString(SystemClock.elapsedRealtime()));
            handler.postDelayed(this, 100);
        }
    };
    private boolean allowed() {
        boolean foreground = false, focused = false;
        ActivityManager manager = getSystemService(ActivityManager.class);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid == pid && process.uid == uid && process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) foreground = true;
        }
        java.util.List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
        if (!tasks.isEmpty() && tasks.get(0).topActivity != null) focused = FlipperLease.PACKAGE.equals(tasks.get(0).topActivity.getPackageName());
        return FlipperLease.allowed(uid,pid,start,FlipperLease.start(pid),getPackageManager().getPackagesForUid(uid),
                getPackageManager().checkSignatures("android",FlipperLease.PACKAGE) == PackageManager.SIGNATURE_MATCH,
                foreground,getSystemService(PowerManager.class).isInteractive() &&
                !getSystemService(KeyguardManager.class).isKeyguardLocked(),focused && window != null && window.isFocused());
    }
    private void release() {
        handler.removeCallbacks(poll);
        if (pid != 0) {
            SystemProperties.set(PREFIX + "flipper_tick","0");
            SystemProperties.set(PREFIX + "request","stop");
            if (death != null) death.unlinkToDeath(died,0);
        }
        pid = 0; uid = 0; start = ""; death = null; window = null;
    }
    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code < 1 || code > 3) return super.onTransact(code,data,reply,flags);
            data.enforceInterface(TOKEN);
            int callerUid = Binder.getCallingUid(), callerPid = Binder.getCallingPid();
            IBinder token = code == 1 ? data.readStrongBinder() : null;
            android.view.WindowId focusedWindow = code == 1 ? android.view.WindowId.CREATOR.createFromParcel(data) : null;
            // Run all lease state on the main thread, including death and polling.
            final java.util.concurrent.FutureTask<Boolean> action = new java.util.concurrent.FutureTask<>(() -> {
                if (code == 1) {
                    if (pid != 0 || UsbDriveControl.busy() || token == null ||
                            !"stopped".equals(SystemProperties.get("init.svc.andr36oid-controller","stopped"))) return false;
                    uid = callerUid; pid = callerPid; start = FlipperLease.start(pid); window = focusedWindow;
                    if (!allowed()) { pid = 0; return false; }
                    try { token.linkToDeath(died,0); } catch (android.os.RemoteException e) { pid = 0; return false; }
                    death = token; renewed = SystemClock.elapsedRealtime();
                    SystemProperties.set(PREFIX + "flipper_pid",Integer.toString(pid));
                    SystemProperties.set(PREFIX + "flipper_uid",Integer.toString(uid));
                    SystemProperties.set(PREFIX + "flipper_start",start);
                    SystemProperties.set(PREFIX + "flipper_tick",Long.toString(renewed));
                    SystemProperties.set(PREFIX + "active_functions",Integer.toString(UsbFunctions.FLIPPER));
                    SystemProperties.set(PREFIX + "flipper_mount","0");
                    SystemProperties.set(PREFIX + "ready","0");
                    SystemProperties.set("ctl.start","andr36oid-controller");
                    handler.postDelayed(poll,500);
                    return true;
                }
                if (pid != callerPid || uid != callerUid || !allowed()) return false;
                if (code == 3) release(); else renewed = SystemClock.elapsedRealtime();
                return true;
            });
            handler.post(action);
            try { reply.writeNoException(); reply.writeInt(action.get() ? 1 : 0); }
            catch (Exception e) { throw new android.os.RemoteException("USB lease unavailable"); }
            return true;
        }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public boolean onUnbind(Intent intent) { release(); return false; }
    @Override public void onDestroy() { release(); super.onDestroy(); }
}
