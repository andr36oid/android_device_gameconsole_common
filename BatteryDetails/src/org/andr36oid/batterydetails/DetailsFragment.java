package org.andr36oid.batterydetails;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;

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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.details);
        mHeader = findPreference("header");
        mGraph = (GraphPreference) findPreference("graph");

        mCapacity = (CapacityPreference) findPreference("capacity_slider");
        mCapacityReset = findPreference("capacity_reset");
        if (!Capacity.isSupported()) {
            final PreferenceCategory category =
                    (PreferenceCategory) findPreference("capacity_category");
            category.removePreference(mCapacity);
            category.removePreference(mCapacityReset);
            mCapacity = null;
            mCapacityReset = null;
            return;
        }
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
            status = getString(R.string.status_not_charging);
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
