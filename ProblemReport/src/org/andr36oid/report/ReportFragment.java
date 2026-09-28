package org.andr36oid.report;

import android.app.NotificationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.text.TextUtils;
import android.widget.Toast;

/** One page: an optional description, a Save button with progress, and what gets kept. */
public class ReportFragment extends PreferenceFragment {

    private static final long POLL_MS = 500;
    /** How long the service may take to show up after ctl.start before we call it failed. */
    private static final long START_GRACE_MS = 5000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            poll();
        }
    };

    private EditTextPreference mNote;
    private Preference mSave;
    private Preference mLastStart;
    /** When we started a report, 0 if this page didn't start one. */
    private long mStartedAt;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.report);
        mNote = (EditTextPreference) findPreference("note");
        mSave = findPreference("save");
        mLastStart = findPreference("last_start");

        mNote.setOnPreferenceChangeListener((pref, value) -> {
            showNote((String) value);
            return true;
        });
        mSave.setOnPreferenceClickListener(pref -> {
            save();
            return true;
        });
        showNote(mNote.getText());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (Reporter.crashRecord() != null) {
            mLastStart.setSummary(R.string.last_start_crash);
        } else {
            mLastStart.setSummary(Reporter.hasPreviousLog()
                    ? R.string.last_start_kept : R.string.last_start_none);
        }
        // Opened from the crash notification, or back on the page: that's seen now.
        getActivity().getSystemService(NotificationManager.class)
                .cancel(BootReceiver.NOTIFICATION_ID);
        poll();
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mPoll);
        super.onPause();
    }

    private void showNote(String note) {
        mNote.setSummary(TextUtils.isEmpty(note) ? getString(R.string.note_hint) : note);
    }

    private void save() {
        if (Reporter.serviceRunning()) return;
        if (!Reporter.start(mNote.getText())) {
            mSave.setSummary(getString(R.string.save_failed, getString(R.string.error_start)));
            return;
        }
        mStartedAt = SystemClock.elapsedRealtime();
        mSave.setEnabled(false);
        mSave.setSummary(getString(R.string.save_running, getString(R.string.step_system), 1, 8));
        mHandler.removeCallbacks(mPoll);
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    private void poll() {
        mHandler.removeCallbacks(mPoll);
        final boolean running = Reporter.serviceRunning();
        final Reporter.Status s = Reporter.readStatus();

        if (running || (mStartedAt != 0 && s.state != Reporter.DONE
                && s.state != Reporter.FAILED
                && SystemClock.elapsedRealtime() - mStartedAt < START_GRACE_MS)) {
            mSave.setEnabled(false);
            if (s.state == Reporter.RUNNING) {
                mSave.setSummary(getString(R.string.save_running, stepName(s.text),
                        s.step, s.steps));
            }
            mHandler.postDelayed(mPoll, POLL_MS);
            return;
        }

        mSave.setEnabled(true);
        final boolean ours = mStartedAt != 0;
        mStartedAt = 0;
        switch (s.state) {
            case Reporter.DONE:
                mSave.setSummary(getString(R.string.save_done, s.text));
                if (ours) {
                    Toast.makeText(getActivity(), getString(R.string.save_done_toast, s.text),
                            Toast.LENGTH_LONG).show();
                    // the description belongs to this report, start fresh for the next one
                    mNote.setText(null);
                    showNote(null);
                }
                break;
            case Reporter.FAILED:
                mSave.setSummary(getString(R.string.save_failed, errorText(s.text)));
                break;
            case Reporter.RUNNING:
                // the service ended without its last line (killed, or a reboot)
                mSave.setSummary(getString(R.string.save_failed,
                        getString(R.string.error_stopped)));
                break;
            default:
                if (ours) {
                    mSave.setSummary(getString(R.string.save_failed,
                            getString(R.string.error_start)));
                } else {
                    mSave.setSummary(R.string.save_summary);
                }
                break;
        }
    }

    private String stepName(String step) {
        switch (step) {
            case "system": return getString(R.string.step_system);
            case "logcat": return getString(R.string.step_logcat);
            case "kernel": return getString(R.string.step_kernel);
            case "crashes": return getString(R.string.step_crashes);
            case "power": return getString(R.string.step_power);
            case "devices": return getString(R.string.step_devices);
            case "apps": return getString(R.string.step_apps);
            case "saving": return getString(R.string.step_saving);
            default: return step;
        }
    }

    private String errorText(String code) {
        switch (code) {
            case "no_storage": return getString(R.string.error_no_storage);
            case "cant_write": return getString(R.string.error_cant_write);
            default: return getString(R.string.error_stopped);
        }
    }
}
