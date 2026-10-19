package org.andr36oid.batterydetails;

import android.app.AlertDialog;
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
import android.preference.PreferenceScreen;
import android.util.Log;

/**
 * Charging diagnostics: what each part of the charging path sees, refreshed every second,
 * and a plug test that watches them for 15 seconds while the charger goes in and says which
 * part didn't notice it. Meant for clones that don't charge.
 */
public class ChargeDiagFragment extends PreferenceFragment {

    private static final String TAG = "ChargeDiag";
    private static final long REFRESH_MS = 1000;
    private static final long POLL_MS = 500;
    private static final int TEST_SECONDS = 15;
    /** mA into the battery that counts as charging */
    private static final int CHARGING_MA = 50;

    private static final int[] STATES = {
        R.string.diag_state_off, R.string.diag_state_dead, R.string.diag_state_trickle,
        R.string.diag_state_charging, R.string.diag_state_done, R.string.diag_state_over_voltage,
        R.string.diag_state_temperature, R.string.diag_state_timer,
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            refresh();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    private ChargeDiagramPreference mDiagram;
    private PreferenceCategory mTestCategory;
    private Preference mResult;
    private Test mTest;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context context = getActivity();
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);

        mDiagram = new ChargeDiagramPreference(context);
        screen.addPreference(mDiagram);

        mTestCategory = new PreferenceCategory(context);
        mTestCategory.setTitle(R.string.diag_test_category);
        screen.addPreference(mTestCategory);
        final Preference test = new Preference(context);
        test.setTitle(R.string.diag_test_title);
        test.setSummary(R.string.diag_test_summary);
        test.setOnPreferenceClickListener(p -> {
            askToUnplug();
            return true;
        });
        mTestCategory.addPreference(test);

        final PreferenceCategory now = new PreferenceCategory(context);
        now.setTitle(R.string.diag_now_category);
        screen.addPreference(now);
        addReading(now, "current", R.string.current);
        addReading(now, "android", R.string.diag_android);
        addReading(now, "driver", R.string.diag_driver);
        addReading(now, "pmic", R.string.diag_pmic);
        addReading(now, "pin", R.string.diag_pin);
        addReading(now, "state", R.string.diag_state);
        addReading(now, "otg", R.string.diag_otg);
        addReading(now, "irqs", R.string.diag_irqs);
        addReading(now, "limits", R.string.diag_limits);
        addReading(now, "regs", R.string.diag_regs);
    }

    private void addReading(PreferenceCategory category, String key, int title) {
        final Preference preference = new Preference(getActivity());
        preference.setKey(key);
        preference.setTitle(title);
        preference.setSelectable(false);
        category.addPreference(preference);
    }

    @Override
    public void onResume() {
        super.onResume();
        mHandler.post(mRefresh);
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mRefresh);
        if (mTest != null) mTest.cancel();
        super.onPause();
    }

    private void refresh() {
        final ChargeDiag d = ChargeDiag.read();
        final int current = d.currentMa();
        set("current", current == Gauge.UNKNOWN ? null
                : getString(current >= 0 ? R.string.value_current_in
                        : R.string.value_current_out, Math.abs(current)));
        final int plugged = plugged(getActivity());
        mDiagram.update(d, plugged);
        set("android", getString(plugged == 0 ? R.string.charger_none
                : plugged == BatteryManager.BATTERY_PLUGGED_AC ? R.string.charger_ac
                : R.string.charger_usb));
        set("driver", d.get("dc_in") == Gauge.UNKNOWN ? null
                : getString(d.is("dc_in") ? R.string.diag_driver_dc
                        : d.is("usb_in") ? R.string.diag_driver_usb : R.string.diag_driver_none));
        set("pmic", d.get("plug_in") == Gauge.UNKNOWN ? null
                : getString(d.is("plug_in") ? R.string.diag_pmic_yes : R.string.diag_pmic_no));
        final int pin = d.get("dc_det_pin");
        set("pin", pin == Gauge.UNKNOWN ? null : pin < 0 ? getString(R.string.diag_pin_none)
                : getString(R.string.diag_pin_value, pin, d.raw("dc_det_mode")));
        set("state", stateName(d.get("chg_sts")));
        set("otg", d.get("otg5v") == Gauge.UNKNOWN ? null
                : getString(d.is("otg5v") ? R.string.diag_otg_on
                        : d.boostOn() ? R.string.diag_otg_boost_only : R.string.diag_otg_off));
        set("irqs", d.get("irq_plug_in") == Gauge.UNKNOWN ? null
                : getString(R.string.diag_irqs_value, d.get("irq_plug_in"),
                        d.get("irq_plug_out"), d.get("irq_dc_det")));
        set("limits", d.get("charge_ma") == Gauge.UNKNOWN ? null
                : getString(R.string.diag_limits_value, d.get("charge_ma"), d.get("input_ma")));
        final String regs = d.registers();
        set("regs", regs.isEmpty() ? null : regs);
    }

    private String stateName(int state) {
        return state >= 0 && state < STATES.length ? getString(STATES[state]) : null;
    }

    private void set(String key, String value) {
        findPreference(key).setSummary(value != null ? value : getString(R.string.unknown));
    }

    private void askToUnplug() {
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.diag_test_title)
                .setMessage(R.string.diag_test_unplug)
                .setPositiveButton(R.string.diag_test_start, (d, w) -> {
                    mTest = new Test();
                    mTest.start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Watches every reading while the charger goes in, then says what it saw. */
    private final class Test implements Runnable {
        private final ChargeDiag mBefore = ChargeDiag.read();
        private final long mEnd = System.currentTimeMillis() + TEST_SECONDS * 1000L;
        private AlertDialog mDialog;
        private boolean mDriver, mPmic, mPin, mCharging;
        private int mMaxCurrent = Gauge.UNKNOWN;
        private int mChargingPolls;
        private int mLastState = Gauge.UNKNOWN;

        void start() {
            mDialog = new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.diag_test_title)
                    .setMessage(getString(R.string.diag_test_plug, TEST_SECONDS))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> cancel())
                    .setCancelable(false)
                    .show();
            mHandler.postDelayed(this, POLL_MS);
        }

        void cancel() {
            mHandler.removeCallbacks(this);
            if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
            mTest = null;
        }

        @Override
        public void run() {
            final ChargeDiag d = ChargeDiag.read();
            mDriver |= d.driverSeesCharger();
            mPmic |= d.is("plug_in");
            mPin |= d.get("dc_det_pin") >= 0 && d.get("dc_det_pin") != mBefore.get("dc_det_pin");
            final int current = d.currentMa();
            if (current != Gauge.UNKNOWN && (mMaxCurrent == Gauge.UNKNOWN || current > mMaxCurrent)) {
                mMaxCurrent = current;
            }
            mChargingPolls = current != Gauge.UNKNOWN && current >= CHARGING_MA
                    ? mChargingPolls + 1 : 0;
            mCharging |= mChargingPolls >= 4;
            mLastState = d.get("chg_sts");
            final long left = mEnd - System.currentTimeMillis();
            if (left <= 0 || mCharging) {
                finish(d);
                return;
            }
            mDialog.setMessage(getString(R.string.diag_test_plug,
                    (int) Math.ceil(left / 1000.0)));
            mHandler.postDelayed(this, POLL_MS);
        }

        private void finish(ChargeDiag after) {
            cancel();
            final boolean irqIn = delta(after, "irq_plug_in") > 0;
            final boolean irqPin = delta(after, "irq_dc_det") > 0;
            final StringBuilder text = new StringBuilder();
            if (mCharging) {
                text.append(getString(R.string.diag_verdict_charging, mMaxCurrent));
            } else if (mDriver) {
                final String state = stateName(mLastState);
                text.append(getString(R.string.diag_verdict_no_current,
                        state != null ? state : getString(R.string.unknown)));
            } else if (mPmic || irqIn) {
                text.append(getString(R.string.diag_verdict_driver_missed));
            } else if (mPin || irqPin) {
                text.append(getString(R.string.diag_verdict_pin_only));
            } else {
                text.append(getString(R.string.diag_verdict_nothing));
            }
            if (!mCharging && !mBefore.is("otg5v") && mBefore.boostOn()) {
                text.append("\n\n").append(getString(R.string.diag_verdict_boost));
            }
            if (!mCharging) {
                text.append("\n\n").append(getString(R.string.diag_verdict_report));
            }
            text.append("\n\n").append(getString(R.string.diag_verdict_seen,
                    yesNo(mPin || irqPin), yesNo(mPmic || irqIn), yesNo(mDriver),
                    mMaxCurrent == Gauge.UNKNOWN ? getString(R.string.unknown)
                            : Integer.toString(mMaxCurrent)));
            // The problem report keeps the log, so this goes along with it
            Log.i(TAG, "plug test: charging=" + mCharging + " driver=" + mDriver
                    + " pmic=" + mPmic + " irq_in=" + irqIn + " pin=" + mPin
                    + " irq_pin=" + irqPin + " max_current=" + mMaxCurrent
                    + " boost_before=" + mBefore.boostOn()
                    + "\n  before: " + mBefore + "\n  after: " + after);

            if (getActivity() == null) return;
            final String result = text.toString();
            if (mResult == null) {
                mResult = new Preference(getActivity());
                mResult.setTitle(R.string.diag_test_last);
                mResult.setSelectable(false);
                mTestCategory.addPreference(mResult);
            }
            mResult.setSummary(result);
            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.diag_test_result)
                    .setMessage(result)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }

        private int delta(ChargeDiag after, String key) {
            final int a = after.get(key), b = mBefore.get(key);
            return a == Gauge.UNKNOWN || b == Gauge.UNKNOWN ? 0 : a - b;
        }

        private String yesNo(boolean value) {
            return getString(value ? R.string.diag_yes : R.string.diag_no);
        }
    }

    /** BatteryManager.BATTERY_PLUGGED_*, 0 when on battery. */
    private static int plugged(Context context) {
        final Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
    }
}
