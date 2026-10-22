package org.andr36oid.savebackup;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Save backup > Sync saves: keeps the saves the same on this console and the user's PC,
 * phone or other consoles, with the Syncthing app (Syncthing-Fork). Off by default.
 *
 * <p>On sets Syncthing up: it may reach all files, starts at boot but only syncs on Wi-Fi
 * while charging (its own run conditions), and gets our two save folders (SyncSetup).
 * While this page is open Syncthing runs anyway, so the user sees devices and progress.
 * Devices are added here with A: one that asked to connect, or one found on the Wi-Fi.
 */
public class SyncFragment extends PreferenceFragment {

    private static final String TAG = "SaveBackup";
    private static final long POLL_MS = 3000;
    /** Syncthing's first start makes its keys and database; slow on the A35. */
    private static final int KEY_WAIT_S = 120;
    private static final long PING_WAIT_MS = 90000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final Runnable mPoll = this::poll;

    private SwitchPreference mSwitch;
    private Preference mStatus;
    private Preference mMyId;
    private PreferenceCategory mDevices;
    private PreferenceCategory mAdd;
    private Preference mAddHint;
    private Preference mOpen;
    private Preference mNote;

    private boolean mVisible;
    /** Turning on or off is going on; its step is shown instead of the state. */
    private String mBusy;
    private SyncSetup.State mState;
    private boolean mReachable;
    /** What the device lists showed last, to rebuild them only when they change. */
    private String mListKey;
    /** Our folders were checked (path, ignores) since the page was opened. */
    private volatile boolean mSetUpThisVisit;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context c = getActivity();
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);

        mSwitch = new SwitchPreference(c);
        mSwitch.setPersistent(false);
        mSwitch.setTitle(R.string.sync_switch_title);
        mSwitch.setChecked(Prefs.sync(c));
        mSwitch.setOnPreferenceChangeListener((p, v) -> {
            if (mBusy != null) return false;
            if ((Boolean) v) {
                turnOn();
            } else {
                confirmOff();
            }
            return false; // set when it worked
        });
        mSwitch.setOrder(0);
        screen.addPreference(mSwitch);

        mStatus = new Preference(c);
        mStatus.setSelectable(false);
        mStatus.setOrder(1);
        screen.addPreference(mStatus);

        mMyId = new Preference(c);
        mMyId.setTitle(R.string.sync_my_id_title);
        mMyId.setOnPreferenceClickListener(p -> {
            showMyId();
            return true;
        });
        mMyId.setOrder(2);
        screen.addPreference(mMyId);

        mDevices = new PreferenceCategory(c);
        mDevices.setTitle(R.string.sync_devices_category);
        mDevices.setOrder(3);
        screen.addPreference(mDevices);

        mAdd = new PreferenceCategory(c);
        mAdd.setTitle(R.string.sync_add_category);
        mAdd.setOrder(4);
        screen.addPreference(mAdd);
        mAddHint = new Preference(c);
        mAddHint.setSelectable(false);
        mAddHint.setSummary(R.string.sync_add_hint);

        mOpen = new Preference(c);
        mOpen.setTitle(R.string.sync_open_title);
        mOpen.setSummary(R.string.sync_open_summary);
        mOpen.setOnPreferenceClickListener(p -> {
            openSyncthing();
            return true;
        });
        mOpen.setOrder(5);
        screen.addPreference(mOpen);

        mNote = new Preference(c);
        mNote.setSelectable(false);
        mNote.setSummary(R.string.sync_note);
        mNote.setOrder(6);
        screen.addPreference(mNote);
        show();
    }

    @Override
    public void onResume() {
        super.onResume();
        mVisible = true;
        mSetUpThisVisit = false;
        if (Prefs.sync(getActivity())) {
            // run now, whatever the Wi-Fi and charger say, while the user looks
            tell(getActivity(), "START");
        }
        poll();
    }

    @Override
    public void onPause() {
        mVisible = false;
        mHandler.removeCallbacks(mPoll);
        if (Prefs.sync(getActivity()) && mBusy == null) {
            // back to "Wi-Fi and charging only"
            tell(getActivity(), "FOLLOW");
        }
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mWorker.shutdownNow();
        super.onDestroy();
    }

    /** Is the Syncthing app installed (and not disabled)? */
    static boolean installed(Context c) {
        try {
            return c.getPackageManager().getApplicationInfo(SyncSetup.PKG, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Syncthing-Fork's service control broadcasts: START, STOP or FOLLOW (run conditions). */
    static void tell(Context c, String action) {
        final Intent i = new Intent(SyncSetup.PKG + ".action." + action)
                .setPackage(SyncSetup.PKG)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        c.sendBroadcast(i);
    }

    // ---- turning on and off ----------------------------------------------------------

    private void turnOn() {
        final Context c = getActivity().getApplicationContext();
        busy(c.getString(R.string.sync_step_prepare));
        mWorker.execute(() -> {
            String error = null;
            try {
                String r = Helper.runBlocking(Helper.syncPrefsRequest(true), 60000);
                if (!r.startsWith("done")) {
                    error = r;
                } else {
                    tell(c, "START");
                    step(c.getString(R.string.sync_step_start));
                    r = Helper.runBlocking(Helper.syncKeyRequest(KEY_WAIT_S),
                            (KEY_WAIT_S + 30) * 1000L);
                    final SyncApi api = r.startsWith("done") ? Helper.syncApi() : null;
                    if (api == null) {
                        error = r.startsWith("error") ? r : "error st_no_config";
                    } else if (!waitForPing(api)) {
                        error = "error st_not_running";
                    } else {
                        step(c.getString(R.string.sync_step_folders));
                        SyncSetup.setUp(api, paths(c), labels(c), deviceName(c));
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "sync setup failed", e);
                error = "error st_api";
            }
            if (error != null) {
                // leave Syncthing as it was: not started at boot
                Helper.runBlocking(Helper.syncPrefsRequest(false), 60000);
            } else {
                Prefs.get(c).edit().putBoolean(Prefs.SYNC, true).apply();
            }
            final String e = error;
            mHandler.post(() -> {
                mBusy = null;
                mSwitch.setChecked(Prefs.sync(c));
                if (e != null && getActivity() != null) {
                    new AlertDialog.Builder(getActivity())
                            .setTitle(R.string.sync_failed_title)
                            .setMessage(errorText(getActivity(), e))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                show();
                poll();
            });
        });
    }

    private void confirmOff() {
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.sync_off_title)
                .setMessage(R.string.sync_off_message)
                .setPositiveButton(R.string.sync_off_button, (d, w) -> turnOff())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void turnOff() {
        final Context c = getActivity().getApplicationContext();
        busy(c.getString(R.string.sync_step_stop));
        mWorker.execute(() -> {
            boolean others = false;
            final SyncApi api = Helper.syncApi();
            if (api != null && api.ping()) {
                try {
                    SyncSetup.pause(api);
                    others = SyncSetup.otherFoldersActive(api);
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "can't pause the save folders", e);
                }
            }
            // Syncthing keeps running for the user's own folders; else it's stopped and
            // no longer started at boot
            if (!others) Helper.runBlocking(Helper.syncPrefsRequest(false), 60000);
            Prefs.get(c).edit().putBoolean(Prefs.SYNC, false).apply();
            mHandler.post(() -> {
                mBusy = null;
                mSwitch.setChecked(false);
                mState = null;
                show();
            });
        });
    }

    private boolean waitForPing(SyncApi api) {
        final long until = SystemClock.elapsedRealtime() + PING_WAIT_MS;
        while (SystemClock.elapsedRealtime() < until) {
            if (api.ping()) return true;
            SystemClock.sleep(1000);
        }
        return false;
    }

    private void busy(String step) {
        mBusy = step;
        show();
    }

    private void step(String step) {
        mHandler.post(() -> {
            if (mBusy != null) busy(step);
        });
    }

    // ---- where the saves are, as the Syncthing app sees them --------------------------

    private static Map<String, String> paths(Context c) {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put(SyncSetup.FOLDER_INTERNAL, "/storage/emulated/0");
        final StorageManager sm = c.getSystemService(StorageManager.class);
        for (VolumeInfo v : sm.getVolumes()) {
            if (MainFragment.easyromsId().equals(v.getId()) && v.isMountedWritable() && v.getPath() != null) {
                m.put(SyncSetup.FOLDER_EASYROMS, v.getPath().getPath());
            }
        }
        return m;
    }

    private static Map<String, String> labels(Context c) {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put(SyncSetup.FOLDER_EASYROMS, c.getString(R.string.sync_label_easyroms));
        m.put(SyncSetup.FOLDER_INTERNAL, c.getString(R.string.sync_label_internal));
        return m;
    }

    /** The name other devices see: Settings > About > Device name. */
    private static String deviceName(Context c) {
        final String n = Settings.Global.getString(c.getContentResolver(),
                Settings.Global.DEVICE_NAME);
        return n != null && !n.isEmpty() ? n : Build.MODEL;
    }

    // ---- reading Syncthing's state -----------------------------------------------------

    private void poll() {
        mHandler.removeCallbacks(mPoll);
        if (!mVisible) return;
        final Context c = getActivity().getApplicationContext();
        if (!Prefs.sync(c) || mBusy != null) {
            show();
            return;
        }
        mWorker.execute(() -> {
            SyncSetup.State s = null;
            boolean reachable = false;
            final SyncApi api = Helper.syncApi();
            if (api != null && api.ping()) {
                reachable = true;
                try {
                    if (!mSetUpThisVisit) {
                        // EASYROMS may have a new UUID (another card), or was not mounted
                        SyncSetup.setUp(api, paths(c), labels(c), null);
                        mSetUpThisVisit = true;
                    }
                    s = SyncSetup.state(api);
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "can't read Syncthing's state", e);
                }
            }
            final SyncSetup.State state = s;
            final boolean r = reachable;
            mHandler.post(() -> {
                mState = state;
                mReachable = r;
                if (state != null) {
                    Prefs.get(c).edit().putInt(Prefs.SYNC_DEVICES, state.devices.size()).apply();
                }
                show();
                if (mVisible) mHandler.postDelayed(mPoll, POLL_MS);
            });
        });
    }

    private void show() {
        final Context c = getActivity();
        if (c == null) return;
        final boolean on = Prefs.sync(c);
        final SyncSetup.State s = on ? mState : null;
        mSwitch.setEnabled(mBusy == null);
        mSwitch.setSummary(on ? R.string.sync_switch_on : R.string.sync_switch_off);
        mStatus.setSummary(statusText(c, on, s));
        mMyId.setEnabled(s != null && !s.myId.isEmpty());
        mMyId.setSummary(s != null && !s.myId.isEmpty() ? s.myId
                : c.getString(R.string.sync_my_id_none));
        mOpen.setEnabled(installed(c));
        showLists(c, s);
    }

    private String statusText(Context c, boolean on, SyncSetup.State s) {
        if (mBusy != null) return mBusy;
        if (!on) return c.getString(R.string.sync_status_off);
        if (!mReachable || s == null) return c.getString(R.string.sync_status_starting);
        if (s.devices.isEmpty()) return c.getString(R.string.sync_status_no_devices);
        if (s.errors > 0) {
            return c.getResources().getQuantityString(R.plurals.sync_status_errors,
                    (int) s.errors, (int) s.errors);
        }
        for (String st : s.folders.values()) {
            if (st.startsWith("sync") || st.startsWith("scan")) {
                return s.needFiles > 0
                        ? c.getResources().getQuantityString(R.plurals.sync_status_syncing,
                                (int) s.needFiles, (int) s.needFiles)
                        : c.getString(R.string.sync_status_scanning);
            }
            if (st.equals("error")) return c.getString(R.string.sync_status_folder_error);
        }
        if (s.connected.isEmpty()) return c.getString(R.string.sync_status_waiting);
        return c.getString(R.string.sync_status_done);
    }

    /** The devices and the devices to add; rebuilt only when the set of rows changes. */
    private void showLists(Context c, SyncSetup.State s) {
        final StringBuilder key = new StringBuilder();
        if (s != null) {
            key.append(s.devices.keySet()).append(s.pending.keySet()).append(s.nearby);
            key.append(s.devices.values()).append(s.pending.values());
        }
        if (key.toString().equals(mListKey) && mListKey != null) {
            // same rows: only the connected/not connected summaries change
            if (s != null) {
                for (int i = 0; i < mDevices.getPreferenceCount(); i++) {
                    final Preference p = mDevices.getPreference(i);
                    if (p.getKey() == null) continue;
                    p.setSummary(s.connected.contains(p.getKey())
                            ? R.string.sync_device_connected : R.string.sync_device_offline);
                }
            }
            return;
        }
        mListKey = key.toString();
        mDevices.removeAll();
        mAdd.removeAll();
        final boolean lists = s != null;
        mDevices.setEnabled(lists);
        mAdd.setEnabled(lists);
        if (!lists) {
            getPreferenceScreen().removePreference(mDevices);
            getPreferenceScreen().removePreference(mAdd);
            return;
        }
        getPreferenceScreen().addPreference(mDevices);
        getPreferenceScreen().addPreference(mAdd);

        if (s.devices.isEmpty()) {
            final Preference none = new Preference(c);
            none.setSelectable(false);
            none.setSummary(R.string.sync_devices_none);
            mDevices.addPreference(none);
        }
        for (Map.Entry<String, String> e : s.devices.entrySet()) {
            final String id = e.getKey();
            final String name = name(e.getValue(), id);
            final Preference p = new Preference(c);
            p.setKey(id);
            p.setTitle(name);
            p.setSummary(s.connected.contains(id)
                    ? R.string.sync_device_connected : R.string.sync_device_offline);
            p.setOnPreferenceClickListener(x -> {
                confirmRemove(id, name);
                return true;
            });
            mDevices.addPreference(p);
        }

        for (Map.Entry<String, String> e : s.pending.entrySet()) {
            final String id = e.getKey();
            final String name = name(e.getValue(), id);
            final Preference p = new Preference(c);
            p.setTitle(name);
            p.setSummary(R.string.sync_pending_summary);
            p.setOnPreferenceClickListener(x -> {
                confirmAdd(id, name);
                return true;
            });
            mAdd.addPreference(p);
        }
        for (String id : s.nearby) {
            final String name = c.getString(R.string.sync_nearby_title, SyncSetup.shortId(id));
            final Preference p = new Preference(c);
            p.setTitle(name);
            p.setSummary(R.string.sync_nearby_summary);
            p.setOnPreferenceClickListener(x -> {
                confirmAdd(id, "");
                return true;
            });
            mAdd.addPreference(p);
        }
        mAdd.addPreference(mAddHint);
    }

    private static String name(String name, String id) {
        return name != null && !name.isEmpty() ? name : SyncSetup.shortId(id);
    }

    // ---- dialogs -----------------------------------------------------------------------

    private void confirmAdd(String id, String name) {
        final Context c = getActivity();
        new AlertDialog.Builder(c)
                .setTitle(c.getString(R.string.sync_add_title,
                        name.isEmpty() ? SyncSetup.shortId(id) : name))
                .setMessage(c.getString(R.string.sync_add_message, id))
                .setPositiveButton(R.string.sync_add_button, (d, w) -> change(() -> {
                    final SyncApi api = Helper.syncApi();
                    if (api == null) throw new IOException("no key");
                    SyncSetup.addDevice(api, id, name);
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemove(String id, String name) {
        final Context c = getActivity();
        new AlertDialog.Builder(c)
                .setTitle(c.getString(R.string.sync_remove_title, name))
                .setMessage(R.string.sync_remove_message)
                .setPositiveButton(R.string.sync_remove_button, (d, w) -> change(() -> {
                    final SyncApi api = Helper.syncApi();
                    if (api == null) throw new IOException("no key");
                    SyncSetup.removeDevice(api, id);
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface Change {
        void run() throws IOException;
    }

    /** Runs a change on the worker, then reads the state again. */
    private void change(Change change) {
        final Context c = getActivity().getApplicationContext();
        mWorker.execute(() -> {
            boolean ok = true;
            try {
                change.run();
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "sync change failed", e);
                ok = false;
            }
            final boolean worked = ok;
            mHandler.post(() -> {
                if (!worked) {
                    Toast.makeText(c, R.string.sync_change_failed, Toast.LENGTH_LONG).show();
                }
                poll();
            });
        });
    }

    /** The console's ID, big, with a QR code from Syncthing for a phone to scan. */
    private void showMyId() {
        final SyncSetup.State s = mState;
        if (s == null || s.myId.isEmpty()) return;
        final String id = s.myId;
        final Context c = getActivity().getApplicationContext();
        mWorker.execute(() -> {
            Bitmap qr = null;
            final SyncApi api = Helper.syncApi();
            if (api != null) {
                try {
                    final byte[] png = api.bytes("/qr/?text=" + SyncApi.enc(id));
                    qr = BitmapFactory.decodeByteArray(png, 0, png.length);
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "no QR code", e);
                }
            }
            final Bitmap bitmap = qr;
            mHandler.post(() -> {
                if (getActivity() == null) return;
                final LinearLayout box = new LinearLayout(getActivity());
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                final int pad = (int) (16 * c.getResources().getDisplayMetrics().density);
                box.setPadding(pad, pad, pad, 0);
                if (bitmap != null) {
                    final ImageView img = new ImageView(getActivity());
                    final int size = (int) (180 * c.getResources().getDisplayMetrics().density);
                    img.setImageBitmap(Bitmap.createScaledBitmap(bitmap, size, size, false));
                    box.addView(img);
                }
                final TextView text = new TextView(getActivity());
                // two lines of four groups, easy to read out and type on a PC
                text.setText(id.substring(0, 31) + "\n" + id.substring(32));
                text.setTypeface(Typeface.MONOSPACE);
                text.setTextIsSelectable(false);
                text.setGravity(Gravity.CENTER_HORIZONTAL);
                text.setPadding(0, pad / 2, 0, 0);
                box.addView(text);
                new AlertDialog.Builder(getActivity())
                        .setTitle(R.string.sync_my_id_title)
                        .setView(box)
                        .setMessage(R.string.sync_my_id_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private void openSyncthing() {
        final Context c = getActivity();
        Intent i = c.getPackageManager().getLaunchIntentForPackage(SyncSetup.PKG);
        if (i == null) i = c.getPackageManager().getLeanbackLaunchIntentForPackage(SyncSetup.PKG);
        if (i == null) return;
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "can't open Syncthing", e);
        }
    }

    static String errorText(Context c, String status) {
        switch (status.replaceFirst("^error ", "")) {
            case "st_missing":
                return c.getString(R.string.sync_error_missing);
            case "st_no_config":
            case "st_not_running":
                return c.getString(R.string.sync_error_not_running);
            case "busy":
                return c.getString(R.string.error_busy);
            default:
                return c.getString(R.string.sync_error_other);
        }
    }
}
