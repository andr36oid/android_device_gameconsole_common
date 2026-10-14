package org.andr36oid.bfi;

import android.app.AlertDialog;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.Display;
import android.widget.Toast;

/** Settings > Display > Black frame insertion: on/off and the frame pattern. */
public class BfiFragment extends PreferenceFragment {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SwitchPreference mSwitch;
    private ListPreference mNormal;
    private ListPreference mBlack;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context c = getContext();
        Bfi.load(c);
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);

        mSwitch = new SwitchPreference(c);
        mSwitch.setPersistent(false);
        mSwitch.setTitle(R.string.bfi_switch);
        mSwitch.setSummaryOn(R.string.bfi_switch_on);
        mSwitch.setSummaryOff(R.string.bfi_switch_off);
        mSwitch.setOnPreferenceChangeListener((p, value) -> {
            if ((Boolean) value) {
                turnOn();
            } else {
                Bfi.set(false);
            }
            // the switch follows the service, see refresh()
            mHandler.postDelayed(this::refresh, 300);
            return false;
        });
        screen.addPreference(mSwitch);

        final Preference about = new Preference(c);
        about.setSummary(R.string.bfi_about);
        about.setSelectable(false);
        screen.addPreference(about);

        mNormal = frames(c, R.string.bfi_normal_frames);
        mBlack = frames(c, R.string.bfi_black_frames);
        mNormal.setValue(String.valueOf(Bfi.sNormal));
        mBlack.setValue(String.valueOf(Bfi.sBlack));
        mNormal.setOnPreferenceChangeListener((p, v) -> {
            Bfi.setPattern(c, Integer.parseInt((String) v), Bfi.sBlack);
            mHandler.post(this::refresh);
            return true;
        });
        mBlack.setOnPreferenceChangeListener((p, v) -> {
            Bfi.setPattern(c, Bfi.sNormal, Integer.parseInt((String) v));
            mHandler.post(this::refresh);
            return true;
        });
        screen.addPreference(mNormal);
        screen.addPreference(mBlack);

        final Preference hint = new Preference(c);
        hint.setSummary(R.string.bfi_pattern_hint);
        hint.setSelectable(false);
        screen.addPreference(hint);

        final Preference fps = new Preference(c);
        fps.setSummary(R.string.bfi_fps_hint);
        fps.setSelectable(false);
        screen.addPreference(fps);

        final Preference credit = new Preference(c);
        credit.setSummary(R.string.bfi_credit);
        credit.setSelectable(false);
        screen.addPreference(credit);
    }

    private ListPreference frames(Context c, int title) {
        final ListPreference list = new ListPreference(c);
        list.setPersistent(false);
        list.setTitle(title);
        list.setDialogTitle(title);
        final String[] values = new String[Bfi.MAX_FRAMES];
        for (int i = 0; i < values.length; i++) values[i] = String.valueOf(i + 1);
        list.setEntries(values);
        list.setEntryValues(values);
        return list;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (getActivity() == null) return;
        mSwitch.setChecked(Bfi.running());
        mNormal.setSummary(mNormal.getValue());
        mBlack.setSummary(mBlack.getValue());
    }

    private void turnOn() {
        final Context c = getContext();
        final Display display = c.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        if (display.getRefreshRate() < Bfi.MIN_HZ) {
            Toast.makeText(c, R.string.bfi_low_rate, Toast.LENGTH_LONG).show();
            return;
        }
        if (Bfi.warned(c)) {
            Bfi.set(true);
            return;
        }
        new AlertDialog.Builder(c)
                .setTitle(R.string.bfi_warning_title)
                .setMessage(R.string.bfi_warning)
                .setPositiveButton(R.string.bfi_warning_ok, (d, w) -> {
                    Bfi.setWarned(c);
                    Bfi.set(true);
                    mHandler.postDelayed(this::refresh, 300);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
