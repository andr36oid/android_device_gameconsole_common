package org.andr36oid.batterydetails;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;

import java.util.ArrayList;
import java.util.List;

/** Live readings, refreshed every 2 seconds while the page is open, and the graph. */
public class DetailsFragment extends PreferenceFragment {

    private static final long REFRESH_MS = 2000;
    /** Opening the page adds a point too, unless the last one is this fresh. */
    private static final long SAMPLE_ON_OPEN_MS = 5 * 60 * 1000L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            refresh();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    private Preference mHeader;
    private GraphPreference mGraph;
    /** Both null when the kernel can't change the capacity */
    private CapacityPreference mCapacity;
    private Preference mCapacityReset;
    /** Null when the kernel can't count the charge or change the capacity */
    private Preference mMeasure;
    /** Null when the kernel has no charge limit */
    private ListPreference mLimit;
    /** Null when the kernel has no charging speed setting */
    private ListPreference mSpeed;
    /** Null when the kernel has no sleep modes to choose from */
    private SwitchPreference mDeepSleep;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.details);
        mHeader = findPreference("header");
        mGraph = (GraphPreference) findPreference("graph");
        setUpChargeLimit();
        setUpChargeSpeed();
        if (mLimit == null && mSpeed == null) {
            getPreferenceScreen().removePreference(findPreference("limit_category"));
        }
        setUpDeepSleep();

        mCapacity = (CapacityPreference) findPreference("capacity_slider");
        mCapacityReset = findPreference("capacity_reset");
        if (!Capacity.isSupported()) {
            final PreferenceCategory category =
                    (PreferenceCategory) findPreference("capacity_category");
            category.removePreference(mCapacity);
            category.removePreference(mCapacityReset);
            category.removePreference(findPreference("measure"));
            mCapacity = null;
            mCapacityReset = null;
            return;
        }
        setUpMeasure();
        mCapacity.setOnPreferenceChangeListener((preference, value) -> {
            Capacity.choose((Integer) value);
            showReset((Integer) value);
            return true;
        });
        mCapacityReset.setOnPreferenceClickListener(preference -> {
            final int mah = Capacity.defaultMah();
            Capacity.choose(mah);
            mCapacity.setValue(mah, mah);
            showReset(mah);
            return true;
        });
    }

    private void setUpMeasure() {
        mMeasure = findPreference("measure");
        if (!Measure.isSupported()) {
            ((PreferenceCategory) findPreference("capacity_category")).removePreference(mMeasure);
            mMeasure = null;
            return;
        }
        mMeasure.setOnPreferenceClickListener(preference -> {
            final Context context = getActivity();
            final AlertDialog.Builder dialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.measure_title);
            switch (Measure.state(context)) {
                case Measure.WAITING:
                case Measure.RUNNING:
                    dialog.setMessage(R.string.measure_stop_question)
                            .setPositiveButton(R.string.measure_stop, (d, w) -> {
                                Measure.stop(context);
                                showMeasure();
                            })
                            .setNegativeButton(R.string.measure_keep_going, null);
                    break;
                case Measure.DONE:
                    final int mah = Capacity.clamp(Measure.resultMah(context));
                    dialog.setMessage(getString(R.string.measure_use_question,
                                    Measure.resultMah(context), mah))
                            .setPositiveButton(R.string.measure_use, (d, w) -> {
                                Capacity.choose(mah);
                                Measure.clear(context);
                                mCapacity.setValue(mah, Capacity.defaultMah());
                                showReset(mah);
                                showMeasure();
                            })
                            .setNeutralButton(R.string.measure_discard, (d, w) -> {
                                Measure.clear(context);
                                showMeasure();
                            })
                            .setNegativeButton(android.R.string.cancel, null);
                    break;
                default:
                    if (mLimit != null && ChargeLimit.chosen() != 0) {
                        // The battery has to get full, which the limit stops
                        dialog.setMessage(R.string.measure_limit_on)
                                .setPositiveButton(android.R.string.ok, null);
                        break;
                    }
                    dialog.setMessage(R.string.measure_how)
                            .setPositiveButton(R.string.measure_start, (d, w) -> {
                                Measure.start(context);
                                showMeasure();
                            })
                            .setNegativeButton(android.R.string.cancel, null);
                    break;
            }
            dialog.show();
            return true;
        });
    }

    private void showMeasure() {
        if (mMeasure == null) {
            return;
        }
        final Context context = getActivity();
        switch (Measure.state(context)) {
            case Measure.WAITING:
                mMeasure.setSummary(Measure.fullSeen(context) ? R.string.measure_unplug
                        : R.string.measure_charge);
                break;
            case Measure.RUNNING:
                mMeasure.setSummary(getString(R.string.measure_running,
                        Measure.soFarMah(context)));
                break;
            case Measure.DONE:
                mMeasure.setSummary(getString(R.string.measure_done_text,
                        Measure.resultMah(context)));
                break;
            case Measure.FAILED:
                mMeasure.setSummary(Measure.failureText(context, Measure.failure(context)));
                break;
            default:
                mMeasure.setSummary(R.string.measure_summary);
                break;
        }
    }

    private void setUpChargeLimit() {
        mLimit = (ListPreference) findPreference("charge_limit");
        if (!ChargeLimit.isSupported()) {
            ((PreferenceCategory) findPreference("limit_category")).removePreference(mLimit);
            mLimit = null;
            return;
        }
        final int n = ChargeLimit.CHOICES.length;
        final CharSequence[] entries = new CharSequence[n];
        final CharSequence[] values = new CharSequence[n];
        for (int i = 0; i < n; i++) {
            final int percent = ChargeLimit.CHOICES[i];
            entries[i] = percent == 0 ? getString(R.string.limit_off)
                    : getString(R.string.limit_choice, percent);
            values[i] = Integer.toString(percent);
        }
        mLimit.setEntries(entries);
        mLimit.setEntryValues(values);
        mLimit.setOnPreferenceChangeListener((preference, value) -> {
            final int percent = Integer.parseInt((String) value);
            ChargeLimit.choose(percent);
            showLimit(percent);
            return true;
        });
    }

    private void setUpDeepSleep() {
        mDeepSleep = (SwitchPreference) findPreference("deep_sleep");
        if (!DeepSleep.isSupported()) {
            getPreferenceScreen().removePreference(findPreference("sleep_category"));
            mDeepSleep = null;
            return;
        }
        mDeepSleep.setOnPreferenceChangeListener((preference, value) -> {
            DeepSleep.choose((Boolean) value);
            return true;
        });
    }

    private void showLimit(int percent) {
        mLimit.setValue(Integer.toString(percent));
        mLimit.setSummary(listSummary(percent == 0 ? getString(R.string.limit_summary_off)
                : getString(R.string.limit_summary, percent, percent - ChargeLimit.HYSTERESIS)));
    }

    private void setUpChargeSpeed() {
        mSpeed = (ListPreference) findPreference("charge_speed");
        if (!ChargeSpeed.isSupported()) {
            ((PreferenceCategory) findPreference("limit_category")).removePreference(mSpeed);
            mSpeed = null;
            return;
        }
        final int defaultMa = ChargeSpeed.defaultMa();
        final List<CharSequence> entries = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        for (int ma : ChargeSpeed.CHOICES) {
            if (ma == defaultMa) {
                continue; // the "Default" entry is this one
            }
            entries.add(ma == 0
                    ? getString(R.string.speed_default, defaultMa,
                            ChargeSpeed.batteryWatts(defaultMa))
                    : getString(R.string.speed_choice, ma, ChargeSpeed.batteryWatts(ma)));
            values.add(Integer.toString(ma));
        }
        mSpeed.setEntries(entries.toArray(new CharSequence[0]));
        mSpeed.setEntryValues(values.toArray(new CharSequence[0]));
        mSpeed.setOnPreferenceChangeListener((preference, value) -> {
            final int ma = Integer.parseInt((String) value);
            if (ma > defaultMa) {
                // Faster than the console's default: only after the warning
                new AlertDialog.Builder(getActivity())
                        .setTitle(R.string.speed_warning_title)
                        .setMessage(getString(R.string.speed_warning, ma,
                                ChargeSpeed.batteryWatts(ma), ChargeSpeed.chargerAmps(ma),
                                ChargeSpeed.chargerWatts(ma)))
                        .setPositiveButton(R.string.speed_warning_ok, (dialog, which) -> {
                            ChargeSpeed.choose(ma);
                            showSpeed(ma);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return false;
            }
            ChargeSpeed.choose(ma);
            showSpeed(ma);
            return true;
        });
    }

    private void showSpeed(int ma) {
        final int defaultMa = ChargeSpeed.defaultMa();
        if (ma == defaultMa) {
            ma = 0;
        }
        mSpeed.setValue(Integer.toString(ma));
        if (ma == 0) {
            mSpeed.setSummary(listSummary(getString(R.string.speed_summary_default, defaultMa,
                    ChargeSpeed.batteryWatts(defaultMa))));
        } else if (ma < defaultMa) {
            mSpeed.setSummary(listSummary(getString(R.string.speed_summary_slower, ma,
                    ChargeSpeed.batteryWatts(ma))));
        } else {
            mSpeed.setSummary(listSummary(getString(R.string.speed_summary_faster, ma,
                    ChargeSpeed.batteryWatts(ma), ChargeSpeed.chargerAmps(ma),
                    ChargeSpeed.chargerWatts(ma))));
        }
    }

    /**
     * ListPreference runs its summary through String.format to fill in the chosen entry, so a
     * plain % in it ("Stops charging at 80%") throws and the page can't open: double them.
     */
    private static String listSummary(String summary) {
        return summary.replace("%", "%%");
    }

    private void showReset(int chosenMah) {
        final int defaultMah = Capacity.defaultMah();
        mCapacityReset.setTitle(getString(R.string.capacity_reset, defaultMah));
        mCapacityReset.setEnabled(chosenMah != defaultMah);
    }

    @Override
    public void onResume() {
        super.onResume();
        final Context context = getActivity();
        SampleJob.schedule(context);
        if (System.currentTimeMillis() - History.lastTime(context) > SAMPLE_ON_OPEN_MS) {
            History.record(context, Gauge.read());
        }
        mGraph.reload();
        if (mLimit != null) {
            showLimit(ChargeLimit.chosen());
        }
        if (mSpeed != null) {
            showSpeed(ChargeSpeed.chosen());
        }
        if (mDeepSleep != null) {
            mDeepSleep.setChecked(DeepSleep.chosen());
        }
        if (mCapacity != null) {
            final int mah = Capacity.chosenMah();
            mCapacity.setValue(mah, Capacity.defaultMah());
            showReset(mah);
        }
        mHandler.post(mRefresh);
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mRefresh);
        if (mCapacity != null) {
            mCapacity.commit();
        }
        super.onPause();
    }

    private void refresh() {
        final Gauge g = Gauge.read();
        final Context context = getActivity();

        if (g.percent != Gauge.UNKNOWN) {
            mHeader.setTitle(getString(R.string.percent, g.percent));
        }
        final String status;
        if (g.isFull()) {
            status = getString(R.string.status_full);
        } else if (g.isCharging()) {
            status = getString(R.string.status_charging);
        } else if (plugged(context) != 0) {
            status = getString(mLimit != null && ChargeLimit.isHolding()
                    ? R.string.status_limit_hold : R.string.status_not_charging);
        } else {
            status = getString(R.string.status_discharging);
        }
        final int minutes = g.isFull() ? Gauge.UNKNOWN : g.minutesLeft();
        if (minutes == Gauge.UNKNOWN) {
            mHeader.setSummary(status);
        } else {
            final String time = getString(g.isCharging() ? R.string.time_to_full
                    : R.string.time_left, formatMinutes(minutes));
            mHeader.setSummary(getString(R.string.status_and_time, status, time));
        }

        set("voltage", g.voltage == Gauge.UNKNOWN ? null
                : getString(R.string.value_voltage, g.voltage / 1000f));
        set("current", g.current == Gauge.UNKNOWN ? null
                : getString(g.current >= 0 ? R.string.value_current_in
                        : R.string.value_current_out, Math.abs(g.current)));
        set("power", g.current == Gauge.UNKNOWN || g.voltage == Gauge.UNKNOWN ? null
                : getString(R.string.value_power,
                        Math.abs(g.current / 1000f * g.voltage / 1000f)));
        set("temperature", g.temperature == Gauge.UNKNOWN ? null
                : getString(R.string.value_temperature, g.temperature / 10f));
        final int plugged = plugged(context);
        set("charger", getString(plugged == 0 ? R.string.charger_none
                : plugged == BatteryManager.BATTERY_PLUGGED_AC ? R.string.charger_ac
                : R.string.charger_usb));
        set("capacity", g.capacity == Gauge.UNKNOWN || g.capacity <= 0 ? null
                : getString(R.string.value_capacity, g.capacity));
        showMeasure();
    }

    private void set(String key, String value) {
        findPreference(key).setSummary(value != null ? value : getString(R.string.unknown));
    }

    private String formatMinutes(int minutes) {
        return minutes < 60 ? getString(R.string.duration_min, minutes)
                : getString(R.string.duration_h_min, minutes / 60, minutes % 60);
    }

    /** BatteryManager.BATTERY_PLUGGED_*, 0 when on battery. */
    private static int plugged(Context context) {
        final Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
    }
}
