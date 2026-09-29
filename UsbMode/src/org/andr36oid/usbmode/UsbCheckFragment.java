package org.andr36oid.usbmode;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "What did I plug in?": every USB device on the OTG port with a plain name and
 * whether it works. Updates every 2 s while shown. The details (English) are
 * kept in files/usb-check.txt for the problem report and can be copied.
 */
public class UsbCheckFragment extends PreferenceFragment {

    private static final String TAG = "UsbCheck";
    private static final String TABLE = "usb_support.txt";
    private static final String WIFI_IDS = "/vendor/etc/wifi_id_list.txt";
    static final String SAVED = "usb-check.txt";
    private static final long POLL_MS = 2000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private UsbTable mTable;
    private Map<String, String> mWifiIds;
    private PreferenceCategory mList;
    private Preference mCopy;
    private String mShown;          // what the list shows now, to rebuild only on change
    private String mDetails = "";
    private boolean mResumed;

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (mResumed) mHandler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context ctx = getActivity();
        final PreferenceScreen root = getPreferenceManager().createPreferenceScreen(ctx);
        setPreferenceScreen(root);

        mList = new PreferenceCategory(ctx);
        mList.setTitle(R.string.usb_check_plugged_in);
        root.addPreference(mList);

        mCopy = new Preference(ctx);
        mCopy.setTitle(R.string.usb_check_copy);
        mCopy.setSummary(R.string.usb_check_copy_summary);
        mCopy.setOnPreferenceClickListener(p -> {
            copyDetails();
            return true;
        });
        root.addPreference(mCopy);

        final Preference about = new Preference(ctx);
        about.setSelectable(false);
        about.setSummary(R.string.usb_check_about);
        root.addPreference(about);

        // The table is ~0.5 MB of text: read it off the main thread
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            final UsbTable t = loadTable(app);
            final Map<String, String> w = loadWifiIds();
            mHandler.post(() -> {
                mTable = t;
                mWifiIds = w;
                mShown = null;
                refresh();
            });
        }, "usb-check-table").start();
    }

    @Override
    public void onResume() {
        super.onResume();
        mResumed = true;
        mHandler.removeCallbacks(mPoll);
        mHandler.post(mPoll);
    }

    @Override
    public void onPause() {
        mResumed = false;
        mHandler.removeCallbacks(mPoll);
        super.onPause();
    }

    private static UsbTable loadTable(Context ctx) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open(TABLE), StandardCharsets.UTF_8))) {
            return UsbTable.parse(r);
        } catch (IOException e) {
            Log.w(TAG, "no " + TABLE, e);
            return new UsbTable();
        }
    }

    /** The Wi-Fi HAL's list: "vid pid module probe" per line */
    private static Map<String, String> loadWifiIds() {
        final Map<String, String> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(WIFI_IDS))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String[] f = line.trim().split("\\s+");
                if (f.length >= 3) m.put(UsbTable.id(f[0], f[1]), f[2]);
            }
        } catch (IOException e) {
            Log.w(TAG, "no " + WIFI_IDS);
            return null;
        }
        return m;
    }

    private void refresh() {
        if (mTable == null || getActivity() == null) return;
        final boolean wifiOn = wifiOn();
        final boolean portIsDevice = new UsbMode(getActivity()).isDevice();
        final List<UsbCheck.Result> results = new ArrayList<>();
        for (UsbScan.Device d : UsbScan.scan(new File(UsbScan.SYSFS))) {
            results.add(UsbCheck.check(d, mTable, mWifiIds, wifiOn));
        }

        final StringBuilder body = new StringBuilder();
        body.append("Wi-Fi: ").append(wifiOn ? "on" : "off")
                .append("   USB port: ").append(portIsDevice ? "computer" : "accessories")
                .append("   table: ").append(mTable.size()).append(" IDs\n\n");
        for (UsbCheck.Result r : results) body.append(UsbCheck.details(r)).append('\n');
        if (results.isEmpty()) body.append("No USB devices.\n");
        final String shown = body.toString();
        if (shown.equals(mShown)) return;
        mShown = shown;
        mDetails = "andr36oid USB check\n"
                + "Date:  " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())
                + "\nBuild: " + Build.DISPLAY + "\n" + shown;
        save(mDetails);
        rebuild(results, portIsDevice);
    }

    private void rebuild(List<UsbCheck.Result> results, boolean portIsDevice) {
        final Context ctx = getActivity();
        mList.removeAll();
        if (portIsDevice) {
            final Preference p = new Preference(ctx);
            p.setSelectable(false);
            p.setTitle(R.string.usb_check_port_device);
            mList.addPreference(p);
        }
        if (results.isEmpty()) {
            final Preference p = new Preference(ctx);
            p.setSelectable(false);
            p.setTitle(R.string.usb_check_none);
            p.setSummary(R.string.usb_check_none_summary);
            mList.addPreference(p);
            return;
        }
        for (UsbCheck.Result r : results) {
            final Preference p = new Preference(ctx);
            final String name = UsbCheck.name(r.dev);
            p.setTitle(name != null ? name : getString(kindName(r.kind)));
            p.setSummary(statusText(r) + "\n" + detailLine(r, name != null));
            final String block = UsbCheck.details(r);
            p.setOnPreferenceClickListener(x -> {
                new AlertDialog.Builder(ctx)
                        .setTitle(x.getTitle())
                        .setMessage(x.getSummary() + "\n\n" + block)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return true;
            });
            mList.addPreference(p);
        }
    }

    private String statusText(UsbCheck.Result r) {
        final String chipId = r.dev.id();
        switch (r.status) {
            case WORKS:
                return getString(R.string.usb_check_works);
            case BT_ONLY:
                return getString(R.string.usb_check_bt_only, chipOrId(r));
            case WIFI_OFF:
                return getString(R.string.usb_check_wifi_off);
            case NOT_STARTED:
                return getString(R.string.usb_check_not_started);
            case NO_POWER:
                return getString(R.string.usb_check_no_power);
            case CD_MODE:
                return getString(R.string.usb_check_cd_mode);
            case CD_UNKNOWN:
                return getString(R.string.usb_check_cd_unknown, chipId);
            case NOT_SUPPORTED:
            default:
                final String s = r.chip != null
                        ? getString(R.string.usb_check_not_supported_named, r.chip, chipId)
                        : getString(R.string.usb_check_not_supported, chipId);
                return "wifi".equals(r.kind) ? s + " " + getString(R.string.usb_check_wifi_hint) : s;
        }
    }

    private static String chipOrId(UsbCheck.Result r) {
        return r.chip != null ? r.chip + " " + r.dev.id() : r.dev.id();
    }

    /** "Wi-Fi adapter · Realtek RTL8811CU/8821CU · 0bda:c811" */
    private String detailLine(UsbCheck.Result r, boolean withKind) {
        final StringBuilder sb = new StringBuilder();
        if (withKind) sb.append(getString(kindName(r.kind)));
        if (r.chip != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(r.chip);
        }
        if (sb.length() > 0) sb.append(" · ");
        sb.append(r.dev.id());
        return sb.toString();
    }

    static int kindName(String kind) {
        if (kind == null) return R.string.usb_check_kind_other;
        switch (kind) {
            case "wifi": return R.string.usb_check_kind_wifi;
            case "bluetooth": return R.string.usb_check_kind_bluetooth;
            case "network": return R.string.usb_check_kind_network;
            case "audio": return R.string.usb_check_kind_audio;
            case "gamepad": return R.string.usb_check_kind_gamepad;
            case "keyboard": return R.string.usb_check_kind_keyboard;
            case "mouse": return R.string.usb_check_kind_mouse;
            case "input": return R.string.usb_check_kind_input;
            case "storage": return R.string.usb_check_kind_storage;
            case "serial": return R.string.usb_check_kind_serial;
            case "video": return R.string.usb_check_kind_video;
            case "hub": return R.string.usb_check_kind_hub;
            default: return R.string.usb_check_kind_other;
        }
    }

    /** Settings.Global.WIFI_ON: 1 on, 2 on in airplane mode, 0 and 3 off */
    private boolean wifiOn() {
        final int v = Settings.Global.getInt(getActivity().getContentResolver(),
                Settings.Global.WIFI_ON, 0);
        return v == 1 || v == 2;
    }

    /** files/usb-check.txt, read by the problem report (runs as root) */
    private void save(String text) {
        final File f = new File(getActivity().getFilesDir(), SAVED);
        final File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "can't save " + f, e);
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private void copyDetails() {
        final ClipboardManager cm = getActivity().getSystemService(ClipboardManager.class);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.usb_check_title), mDetails));
        Toast.makeText(getActivity(), R.string.usb_check_copied, Toast.LENGTH_SHORT).show();
    }
}
