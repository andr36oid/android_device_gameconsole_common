package org.andr36oid.wifitransfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.service.quicksettings.TileService;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs the web server. The server only exists while this foreground service does, and the
 * service always shows a notification with a Stop button while it runs. It stops itself
 * after half an hour without any request.
 */
public class TransferService extends Service implements FileApi.Events {

    private static final String TAG = "WifiTransfer";

    static final String ACTION_STOP = "org.andr36oid.wifitransfer.action.STOP";

    /** Tried first; the next few ports and then any free port if it's taken. */
    static final int PORT = 8080;

    private static final String CHANNEL = "transfer";
    private static final int NOTIFICATION_ID = 1;
    private static final long IDLE_STOP_MS = 30 * 60 * 1000;
    private static final long IDLE_CHECK_MS = 60 * 1000;
    /** A batch of uploads counts as finished after this long without a new one. */
    private static final long BATCH_DONE_MS = 3000;
    private static final long NOTIFY_EVERY_MS = 1000;
    private static final int RECENT = 20;

    /** Told on the main thread whenever anything on the screen could change. */
    interface Listener {
        void onTransferStateChanged();
    }

    /** One finished (or failed) upload, for the list on the console screen. */
    static final class Transfer {
        final String name;
        final String where;
        final long bytes;
        final long time;
        final String error;

        Transfer(String name, String where, long bytes, String error) {
            this.name = name;
            this.where = where;
            this.bytes = bytes;
            this.time = System.currentTimeMillis();
            this.error = error;
        }
    }

    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();
    private static TransferService sRunning;
    /** Why the last session ended by itself, 0 if it didn't. */
    private static int sStopReason;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private FileApi mApi;
    private HttpServer mServer;
    private int mPort = -1;
    private String mError;
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    // State below is only touched on the main thread
    private final List<Transfer> mRecent = new ArrayList<>();
    private int mReceived;
    private long mReceivedBytes;
    private int mBatchCount;
    private String mCurrentName;
    private long mCurrentDone;
    private long mCurrentTotal;
    private long mCurrentStarted;
    private final List<String> mClients = new ArrayList<>();
    private long mLastNotify;
    private String mLastWarning;

    static void start(Context context) {
        sStopReason = 0;
        context.startForegroundService(new Intent(context, TransferService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, TransferService.class));
    }

    /** The running service, null when off. Main thread only. */
    static TransferService running() {
        return sRunning;
    }

    static int stopReason() {
        return sStopReason;
    }

    static void addListener(Listener l) {
        sListeners.add(l);
    }

    static void removeListener(Listener l) {
        sListeners.remove(l);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = this;
        startForeground(NOTIFICATION_ID, buildNotification());

        mWakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.acquire();
        final WifiManager wifi = getSystemService(WifiManager.class);
        if (wifi != null) {
            // Keeps Wi-Fi out of power save, which makes uploads several times faster
            mWifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG);
            mWifiLock.acquire();
        }

        mApi = new FileApi(new Volumes(this), name -> getAssets().open(name), this);
        mServer = new HttpServer(mApi);
        final HttpServer server = mServer;
        new Thread(() -> {
            int port = -1;
            String error = null;
            try {
                port = server.start(PORT);
            } catch (IOException e) {
                Log.e(TAG, "can't start the server", e);
                error = e.getMessage();
            }
            final int p = port;
            final String err = error;
            mHandler.post(() -> {
                if (sRunning != this) {
                    // Stopped while starting
                    server.stop();
                    return;
                }
                mPort = p;
                mError = err;
                changed(true);
            });
        }, "WifiTransferStart").start();
        mHandler.postDelayed(mIdleCheck, IDLE_CHECK_MS);
        changed(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = null;
        mHandler.removeCallbacksAndMessages(null);
        // Off the main thread: closing sockets may block for a moment
        final HttpServer server = mServer;
        new Thread(server::stop, "WifiTransferStop").start();
        if (mWakeLock.isHeld()) mWakeLock.release();
        if (mWifiLock != null && mWifiLock.isHeld()) mWifiLock.release();
        notifyListeners();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable mIdleCheck = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - mApi.lastActivity() > IDLE_STOP_MS
                    && mCurrentName == null) {
                sStopReason = R.string.stopped_idle;
                stopSelf();
                return;
            }
            // Also picks up a new IP address for the notification
            changed(false);
            mHandler.postDelayed(this, IDLE_CHECK_MS);
        }
    };

    // ---- State for the screen ----

    /** Listening port, -1 while starting or if it failed. */
    int port() {
        return mPort;
    }

    String error() {
        return mError;
    }

    String pin() {
        return mApi.pin();
    }

    int received() {
        return mReceived;
    }

    long receivedBytes() {
        return mReceivedBytes;
    }

    /** Name of the file coming in right now, null if none. */
    String currentName() {
        return mCurrentName;
    }

    long currentDone() {
        return mCurrentDone;
    }

    long currentTotal() {
        return mCurrentTotal;
    }

    /** Bytes per second of the current upload. */
    long currentSpeed() {
        final long ms = System.currentTimeMillis() - mCurrentStarted;
        return ms > 500 ? mCurrentDone * 1000 / ms : 0;
    }

    int clients() {
        return mClients.size();
    }

    List<Transfer> recent() {
        return mRecent;
    }

    /** A wrong PIN or similar, shown once on the screen. */
    String takeWarning() {
        final String w = mLastWarning;
        mLastWarning = null;
        return w;
    }

    // ---- FileApi.Events, called on server threads ----

    @Override
    public void onSignedIn(String client) {
        mHandler.post(() -> {
            if (!mClients.contains(client)) mClients.add(client);
            changed(true);
        });
    }

    @Override
    public void onWrongPin(String client, boolean lockedOut) {
        mHandler.post(() -> {
            mLastWarning = getString(lockedOut ? R.string.warning_locked : R.string.warning_pin,
                    client);
            changed(false);
        });
    }

    @Override
    public void onPinChanged(String pin) {
        mHandler.post(() -> {
            mLastWarning = getString(R.string.warning_new_pin);
            changed(true);
        });
    }

    @Override
    public void onUploadProgress(String client, String name, long done, long total) {
        mHandler.post(() -> {
            if (!name.equals(mCurrentName) || done == 0) {
                mCurrentStarted = System.currentTimeMillis();
            }
            mCurrentName = name;
            mCurrentDone = done;
            mCurrentTotal = total;
            mHandler.removeCallbacks(mBatchDone);
            changed(false);
        });
    }

    @Override
    public void onUploadDone(String client, Root root, String path, File file, long bytes) {
        scan(file);
        mHandler.post(() -> {
            mCurrentName = null;
            mReceived++;
            mReceivedBytes += bytes;
            mBatchCount++;
            final int slash = path.lastIndexOf('/');
            final String where = slash > 0 ? root.name + "/" + path.substring(0, slash)
                    : root.name;
            addRecent(new Transfer(file.getName(), where, bytes, null));
            mHandler.removeCallbacks(mBatchDone);
            mHandler.postDelayed(mBatchDone, BATCH_DONE_MS);
            changed(true);
        });
    }

    @Override
    public void onUploadFailed(String client, String name, String why) {
        mHandler.post(() -> {
            mCurrentName = null;
            if (sRunning == this) addRecent(new Transfer(name, null, 0, why));
            changed(true);
        });
    }

    @Override
    public void onChanged(File file) {
        scan(file);
    }

    private void addRecent(Transfer t) {
        mRecent.add(0, t);
        while (mRecent.size() > RECENT) mRecent.remove(mRecent.size() - 1);
    }

    /** Tells the media scanner, so music, videos and pictures show up in other apps. */
    private void scan(File file) {
        MediaScannerConnection.scanFile(this, new String[] {file.getAbsolutePath()}, null,
                null);
    }

    private final Runnable mBatchDone = new Runnable() {
        @Override
        public void run() {
            if (mBatchCount > 0 && mCurrentName == null) {
                Toast.makeText(TransferService.this, getResources().getQuantityString(
                        R.plurals.files_received, mBatchCount, mBatchCount),
                        Toast.LENGTH_LONG).show();
                mBatchCount = 0;
            }
        }
    };

    // ---- Updates ----

    /** Updates listeners, the tile and (at most once a second unless forced) the
     *  notification. */
    private void changed(boolean forceNotification) {
        if (sRunning != this) return;
        final long now = System.currentTimeMillis();
        if (forceNotification || now - mLastNotify >= NOTIFY_EVERY_MS) {
            mLastNotify = now;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                    buildNotification());
        }
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener l : sListeners) l.onTransferStateChanged();
        TileService.requestListeningState(this,
                new ComponentName(this, TransferTileService.class));
    }

    /** The address to show, e.g. "http://192.168.1.23:8080", null without a network. */
    String url() {
        if (mPort < 0) return null;
        final List<String> ips = Addresses.find();
        return ips.isEmpty() ? null : Addresses.url(ips.get(0), mPort);
    }

    private Notification buildNotification() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, TransferActivity.class), PendingIntent.FLAG_IMMUTABLE);
        final PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, TransferService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        final Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_wifi_transfer)
                .setContentTitle(getString(R.string.notification_title))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notification_stop), stop).build());
        final String url = mApi != null ? url() : null;
        if (mCurrentName != null && mCurrentTotal > 0) {
            b.setContentText(getString(R.string.notification_receiving, mCurrentName));
            b.setProgress(1000, (int) (mCurrentDone * 1000 / mCurrentTotal), false);
        } else if (url != null) {
            b.setContentText(getString(R.string.notification_text, url, mApi.pin()));
        } else if (mApi != null && mPort >= 0) {
            b.setContentText(getString(R.string.no_network_title));
        } else {
            b.setContentText(getString(R.string.starting));
        }
        if (mReceived > 0) {
            b.setSubText(getResources().getQuantityString(R.plurals.files_received,
                    mReceived, mReceived));
        }
        return b.build();
    }

    static String formatBytes(Context context, long bytes) {
        return Formatter.formatShortFileSize(context, bytes);
    }
}
