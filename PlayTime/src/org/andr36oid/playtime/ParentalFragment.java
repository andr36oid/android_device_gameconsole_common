package org.andr36oid.playtime;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.widget.NumberPicker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

/** The parental controls settings, opened after the PIN (see ParentalActivity). */
public class ParentalFragment extends PreferenceFragment {

    private static final int REQUEST_NEW_PIN = 1;
    private static final int REQUEST_CHANGE_PIN = 2;
    /** the "Custom" entry of the rest hours */
    private static final int CUSTOM = -1;

    private SwitchPreference mOn;
    private ListPreference mLimit;
    private ListPreference mRefill;
    private ListPreference mShutdown;
    private MultiSelectListPreference mAllowed;
    private MultiSelectListPreference mBlocked;
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
        mRefill.setOnPreferenceChangeListener((p, v) -> {
            final int hours = Integer.parseInt((String) v);
            if (hours == CUSTOM) {
                pickHours(c);
            } else {
                Parental.setRefillHours(c, hours);
                refresh();
            }
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

        mAllowed = apps(c, true);
        screen.addPreference(mAllowed);
        mBlocked = apps(c, false);
        screen.addPreference(mBlocked);

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

    private CharSequence hoursName(int hours) {
        return hours > 0
                ? getResources().getQuantityString(R.plurals.parental_refill_hours, hours, hours)
                : getString(R.string.parental_refill_pin);
    }

    /** The usual rest times, a custom one if that is set, and Custom to set one. */
    private void refillEntries(Context c) {
        final List<CharSequence> names = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        final int current = Parental.refillHours(c);
        boolean listed = false;
        for (int h : Parental.REFILL_HOURS) {
            listed |= h == current;
        }
        for (int h : Parental.REFILL_HOURS) {
            if (!listed && (h == 0 || h > current)) {
                names.add(hoursName(current));
                values.add(String.valueOf(current));
                listed = true;
            }
            names.add(hoursName(h));
            values.add(String.valueOf(h));
        }
        names.add(getString(R.string.parental_refill_custom));
        values.add(String.valueOf(CUSTOM));
        mRefill.setEntries(names.toArray(new CharSequence[0]));
        mRefill.setEntryValues(values.toArray(new CharSequence[0]));
    }

    /** Any number of hours, picked with up and down on the D-pad. */
    private void pickHours(Context c) {
        final NumberPicker picker = new NumberPicker(c);
        picker.setMinValue(1);
        picker.setMaxValue(Parental.CUSTOM_HOURS_MAX);
        picker.setValue(Math.max(1, Parental.refillHours(c)));
        picker.setWrapSelectorWheel(false);
        picker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        final AlertDialog dialog = new AlertDialog.Builder(c)
                .setTitle(R.string.parental_refill_custom_title)
                .setView(picker)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Parental.setRefillHours(c, picker.getValue());
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        // Up and down change the number, so they can't reach the buttons: A takes the number,
        // left or right goes to the buttons
        picker.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus();
                    return true;
                default:
                    return false;
            }
        });
        picker.requestFocus();
    }

    /** The apps on the home screen to pick from, by name; not Settings or home screens. */
    private MultiSelectListPreference apps(Context c, boolean allowed) {
        final PackageManager pm = c.getPackageManager();
        final Set<String> skip = new HashSet<>();
        skip.add(c.getPackageName());
        skip.add("com.android.settings");
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME), 0)) {
            skip.add(r.activityInfo.packageName);
        }
        final TreeMap<String, String> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String category : new String[] {Intent.CATEGORY_LAUNCHER,
                Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                    .addCategory(category), 0)) {
                final String pkg = r.activityInfo.packageName;
                if (!skip.contains(pkg) && !byName.containsValue(pkg)) {
                    byName.put(r.activityInfo.applicationInfo.loadLabel(pm).toString(), pkg);
                }
            }
        }
        final MultiSelectListPreference p = new MultiSelectListPreference(c);
        p.setPersistent(false);
        p.setTitle(allowed ? R.string.parental_allowed : R.string.parental_blocked_apps);
        p.setDialogTitle(allowed ? R.string.parental_allowed : R.string.parental_blocked_apps);
        p.setEntries(byName.keySet().toArray(new CharSequence[0]));
        p.setEntryValues(byName.values().toArray(new CharSequence[0]));
        p.setOnPreferenceChangeListener((pref, v) -> {
            @SuppressWarnings("unchecked")
            final Set<String> apps = (Set<String>) v;
            Parental.setApps(c, allowed, apps);
            refresh();
            return false;
        });
        return p;
    }

    private void appsSummary(Context c, MultiSelectListPreference p, Set<String> apps,
            int none) {
        p.setValues(apps);
        final List<String> names = new ArrayList<>();
        final CharSequence[] entries = p.getEntries();
        final CharSequence[] values = p.getEntryValues();
        for (int i = 0; i < values.length; i++) {
            if (apps.contains(values[i].toString())) names.add(entries[i].toString());
        }
        p.setSummary(names.isEmpty() ? getString(none) : String.join(", ", names));
    }

    private void refresh() {
        final Context c = getContext();
        if (c == null) return;
        final boolean on = Parental.isOn(c);
        mOn.setChecked(on);
        mLimit.setValue(String.valueOf(Parental.limitMinutes(c)));
        mLimit.setSummary(mLimit.getEntry());
        refillEntries(c);
        mRefill.setValue(String.valueOf(Parental.refillHours(c)));
        mRefill.setSummary(mRefill.getEntry());
        appsSummary(c, mAllowed, Parental.allowedApps(c), R.string.parental_allowed_none);
        appsSummary(c, mBlocked, Parental.blockedApps(c), R.string.parental_blocked_none);
        mShutdown.setValue(String.valueOf(Parental.shutdownMinutes(c)));
        mShutdown.setSummary(mShutdown.getEntry());
        mLeft.setEnabled(on);
        mLeft.setSummary(getString(R.string.parental_left_summary,
                Usage.format(c, Parental.leftMs(c)),
                Usage.format(c, Parental.allowanceMs(c))));
        mPin.setEnabled(Parental.hasPin(c));
    }
}
