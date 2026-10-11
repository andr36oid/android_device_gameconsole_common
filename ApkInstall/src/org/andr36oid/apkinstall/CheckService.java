package org.andr36oid.apkinstall;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists EASYROMS/apk (waiting for EASYROMS to mount, its file system check can take a
 * while) and asks about the APKs not answered yet. Not during the first start's setup:
 * the console's own pages come first, the question waits for the next start.
 */
public class CheckService extends Service {

    private static final String TAG = "ApkInstall";
    private boolean mRunning;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mRunning) return START_NOT_STICKY;
        mRunning = true;
        new Thread(this::check, "apk-check").start();
        return START_NOT_STICKY;
    }

    private void check() {
        try {
            if (Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE, 0) == 0) {
                Log.i(TAG, "setup not finished, not asking yet");
                return;
            }
            final List<Helper.Apk> apks = Handled.unanswered(this, Helper.scan());
            if (apks.isEmpty()) return;
            Log.i(TAG, apks.size() + " APKs to ask about");
            final ArrayList<String> names = new ArrayList<>();
            final long[] sizes = new long[apks.size()];
            final long[] mtimes = new long[apks.size()];
            for (int i = 0; i < apks.size(); i++) {
                names.add(apks.get(i).name);
                sizes[i] = apks.get(i).size;
                mtimes[i] = apks.get(i).mtime;
            }
            startActivity(new Intent(this, PromptActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putStringArrayListExtra(PromptActivity.EXTRA_NAMES, names)
                    .putExtra(PromptActivity.EXTRA_SIZES, sizes)
                    .putExtra(PromptActivity.EXTRA_MTIMES, mtimes));
        } catch (RuntimeException e) {
            Log.w(TAG, "APK check failed", e);
        } finally {
            stopSelf();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
