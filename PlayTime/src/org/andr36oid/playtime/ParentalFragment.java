package org.andr36oid.playtime;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

/** The parental controls settings, opened after the PIN (see ParentalActivity). */
public class ParentalFragment extends PreferenceFragment {

    private static final int REQUEST_NEW_PIN = 1;
    private static final int REQUEST_CHANGE_PIN = 2;

    private SwitchPreference mOn;
    private ListPreference mLimit;
    private ListPreference mRefill;
    private ListPreference mShutdown;
    private Preference mLeft;
    private Preference mPin;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context c = getContext();
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);

        mOn = new SwitchPreference(c);
        mOn.setPersistent(false);
        mOn.setTitle(R.string.parental_title);
        mOn.setSummary(R.string.parental_switch_summary);
        mOn.setOnPreferenceChangeListener((p, v) -> {
            if ((Boolean) v) {
                if (Parental.hasPin(c)) {
                    Parental.setOn(c, true);
                    refresh();
                } else {
                    startActivityForResult(new Intent(c, PinActivity.class)
                            .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_NEW),
                            REQUEST_NEW_PIN);
                }
            } else {
                Parental.setOn(c, false);
                refresh();
            }
            return false;
        });
        screen.addPreference(mOn);

        mLimit = new ListPreference(c);
        mLimit.setPersistent(false);
        mLimit.setTitle(R.string.parental_limit);
        mLimit.setDialogTitle(R.string.parental_limit);
        final int[] limits = Parental.LIMIT_MINUTES;
        final CharSequence[] limitNames = new CharSequence[limits.length];
        final CharSequence[] limitValues = new CharSequence[limits.length];
        for (int i = 0; i < limits.length; i++) {
            limitNames[i] = Usage.format(c, limits[i] * 60_000L);
            limitValues[i] = String.valueOf(limits[i]);
        }
        mLimit.setEntries(limitNames);
        mLimit.setEntryValues(limitValues);
        mLimit.setOnPreferenceChangeListener((p, v) -> {
            Parental.setLimitMinutes(c, Integer.parseInt((String) v));
            refresh();
            return false;
        });
        screen.addPreference(mLimit);

        mRefill = new ListPreference(c);
        mRefill.setPersistent(false);
        mRefill.setTitle(R.string.parental_refill);
        mRefill.setDialogTitle(R.string.parental_refill);
        final int[] hours = Parental.REFILL_HOURS;
        final CharSequence[] refillNames = new CharSequence[hours.length];
        final CharSequence[] refillValues = new CharSequence[hours.length];
        for (int i = 0; i < hours.length; i++) {
            refillNames[i] = hours[i] > 0
                    ? getResources().getQuantityString(R.plurals.parental_refill_hours,
                            hours[i], hours[i])
                    : getString(R.string.parental_refill_pin);
            refillValues[i] = String.valueOf(hours[i]);
        }
        mRefill.setEntries(refillNames);
        mRefill.setEntryValues(refillValues);
        mRefill.setOnPreferenceChangeListener((p, v) -> {
            Parental.setRefillHours(c, Integer.parseInt((String) v));
            refresh();
            return false;
        });
        screen.addPreference(mRefill);

        mShutdown = new ListPreference(c);
        mShutdown.setPersistent(false);
        mShutdown.setTitle(R.string.parental_shutdown);
        mShutdown.setDialogTitle(R.string.parental_shutdown);
        final int[] waits = Parental.SHUTDOWN_MINUTES;
        final CharSequence[] waitNames = new CharSequence[waits.length];
        final CharSequence[] waitValues = new CharSequence[waits.length];
        for (int i = 0; i < waits.length; i++) {
            waitNames[i] = waits[i] > 0
                    ? getString(R.string.parental_shutdown_after,
                            Usage.format(c, waits[i] * 60_000L))
                    : getString(R.string.parental_shutdown_never);
            waitValues[i] = String.valueOf(waits[i]);
        }
        mShutdown.setEntries(waitNames);
        mShutdown.setEntryValues(waitValues);
        mShutdown.setOnPreferenceChangeListener((p, v) -> {
            Parental.setShutdownMinutes(c, Integer.parseInt((String) v));
            refresh();
            return false;
        });
        screen.addPreference(mShutdown);

        mLeft = new Preference(c);
        mLeft.setTitle(R.string.parental_left_title);
        mLeft.setOnPreferenceClickListener(p -> {
            TimeUpActivity.showTimeChoices(c, this::refresh);
            return true;
        });
        screen.addPreference(mLeft);

        mPin = new Preference(c);
        mPin.setTitle(R.string.parental_change_pin);
        mPin.setOnPreferenceClickListener(p -> {
            startActivityForResult(new Intent(c, PinActivity.class)
                    .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_NEW), REQUEST_CHANGE_PIN);
            return true;
        });
        screen.addPreference(mPin);

        final Preference about = new Preference(c);
        about.setSummary(R.string.parental_about);
        about.setSelectable(false);
        screen.addPreference(about);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_NEW_PIN && resultCode == android.app.Activity.RESULT_OK) {
            Parental.setOn(getContext(), true);
        }
        refresh();
    }

    private void refresh() {
        final Context c = getContext();
        if (c == null) return;
        final boolean on = Parental.isOn(c);
        mOn.setChecked(on);
        mLimit.setValue(String.valueOf(Parental.limitMinutes(c)));
        mLimit.setSummary(mLimit.getEntry());
        mRefill.setValue(String.valueOf(Parental.refillHours(c)));
        mRefill.setSummary(mRefill.getEntry());
        mShutdown.setValue(String.valueOf(Parental.shutdownMinutes(c)));
        mShutdown.setSummary(mShutdown.getEntry());
        mLeft.setEnabled(on);
        mLeft.setSummary(getString(R.string.parental_left_summary,
                Usage.format(c, Parental.leftMs(c)),
                Usage.format(c, Parental.allowanceMs(c))));
        mPin.setEnabled(Parental.hasPin(c));
    }
}
