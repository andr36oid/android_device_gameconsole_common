package org.andr36oid.savebackup;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.widget.Toast;

/**
 * The main page: Back up now (with the last backup as its summary), Restore saves, Back up
 * to USB drive now, the automatic backup switch and how many backups to keep.
 */
public class MainFragment extends PreferenceFragment {

    /** EASYROMS is the card's 7th partition; every other public volume is a drive. */
    private static final String EASYROMS_ID = "public:179,7";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Helper.Runner mRunner;
    private Preference mBackupNow;
    private Preference mUsb;
    private ListPreference mKeep;
    /** What the last run we started or followed said, shown until the page is left. */
    private String mOutcome;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.main);
        mBackupNow = findPreference("backup_now");
        mUsb = findPreference("usb");
        mKeep = (ListPreference) findPreference("keep");
        mRunner = new Helper.Runner(mHandler, new Helper.Runner.Listener() {
            @Override
            public void onProgress(String status) {
                showBusy(status);
            }

            @Override
            public void onDone(String status) {
                finished(status);
            }
        });

        mBackupNow.setOnPreferenceClickListener(p -> {
            start(Helper.backupRequest(getActivity(), "manual", false));
            return true;
        });
        mUsb.setOnPreferenceClickListener(p -> {
            start(Helper.backupRequest(getActivity(), "usb", true));
            return true;
        });
        findPreference("restore").setOnPreferenceClickListener(p -> {
            startActivity(new Intent(getActivity(), RestoreActivity.class));
            return true;
        });
        findPreference(Prefs.AUTO).setOnPreferenceChangeListener((p, v) -> {
            // the preference is saved after this returns
            mHandler.post(() -> Schedule.update(getActivity()));
            return true;
        });
        mKeep.setOnPreferenceChangeListener((p, v) -> {
            showKeep((String) v);
            return true;
        });
        showKeep(mKeep.getValue());
    }

    @Override
    public void onResume() {
        super.onResume();
        mOutcome = null;
        if (!mRunner.follow()) showIdle();
    }

    @Override
    public void onPause() {
        mRunner.stop();
        super.onPause();
    }

    private void start(String request) {
        if (mRunner.active()) return;
        if (!mRunner.start(request)) {
            Toast.makeText(getActivity(), Texts.error(getActivity(),
                    Helper.running() ? "error busy" : "error start"), Toast.LENGTH_LONG).show();
        }
    }

    private void showBusy(String status) {
        mBackupNow.setEnabled(false);
        mUsb.setEnabled(false);
        mBackupNow.setSummary(Texts.progress(getActivity(), status));
    }

    private void finished(String status) {
        final Context c = getActivity();
        if (c == null) return;
        mOutcome = status.startsWith("done list") || status.startsWith("done plan")
                || status.startsWith("done restore") ? null : Texts.backupDone(c, status);
        if (mOutcome != null) Toast.makeText(c, mOutcome, Toast.LENGTH_LONG).show();
        SummaryProvider.changed(c);
        showIdle();
    }

    private void showIdle() {
        final Context c = getActivity();
        final String last = Texts.lastSummary(c, Helper.last());
        mBackupNow.setEnabled(true);
        mBackupNow.setSummary(mOutcome != null ? mOutcome + "\n" + last : last);
        final String drive = usbDrive(c);
        mUsb.setEnabled(drive != null);
        mUsb.setSummary(drive != null ? c.getString(R.string.usb_summary, drive)
                : c.getString(R.string.usb_none));
    }

    private void showKeep(String value) {
        mKeep.setSummary(getString(R.string.keep_summary, value != null ? value : "10"));
    }

    /** The name of a plugged in USB drive (any mounted public volume but EASYROMS). */
    static String usbDrive(Context c) {
        final StorageManager sm = c.getSystemService(StorageManager.class);
        for (VolumeInfo v : sm.getVolumes()) {
            if (v.getType() != VolumeInfo.TYPE_PUBLIC || EASYROMS_ID.equals(v.getId())
                    || !v.isMountedWritable()) {
                continue;
            }
            final String name = sm.getBestVolumeDescription(v);
            return name != null ? name : c.getString(R.string.where_usb);
        }
        return null;
    }
}
