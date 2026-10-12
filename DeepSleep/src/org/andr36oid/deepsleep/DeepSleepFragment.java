package org.andr36oid.deepsleep;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * One switch per power-saving step the firmware (BL31) takes in sleep, and one for waking on
 * USB. A change is saved in persist.andr36oid.sleep_mode / sleep_wakeup; init writes it to
 * the kernel (rockchip_pm_config's sleep_mode / sleep_wakeup parameters), which hands it to
 * the firmware. "default" goes back to the DTB's value.
 */
public class DeepSleepFragment extends PreferenceFragment {

    private static final String PARAMS = "/sys/module/rockchip_pm_config/parameters/";
    private static final String MODE_PROP = "persist.andr36oid.sleep_mode";
    private static final String WAKEUP_PROP = "persist.andr36oid.sleep_wakeup";
    /** init writes the property to the kernel a moment after it is set */
    private static final long REFRESH_DELAY_MS = 500;

    /** bit, title, summary: include/dt-bindings/suspend/rockchip-px30.h */
    private static final int[][] MODE_STEPS = {
        {1, R.string.step_armoff, R.string.step_armoff_summary},
        {3, R.string.step_logoff, R.string.step_logoff_summary},
        {2, R.string.step_ddrpd, R.string.step_ddrpd_summary},
        {8, R.string.step_plls, R.string.step_plls_summary},
        {9, R.string.step_32k, R.string.step_32k_summary},
        {10, R.string.step_osc, R.string.step_osc_summary},
        {16, R.string.step_clk_gt, R.string.step_clk_gt_summary},
        {17, R.string.step_pmic_lp, R.string.step_pmic_lp_summary},
    };
    private static final int USB_WAKEUP_BIT = 7;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SwitchPreference[] mSteps = new SwitchPreference[MODE_STEPS.length];
    private SwitchPreference mUsbWake;
    private Preference mState;
    /** current and DTB values, {cur, dt}; null when the kernel can't change them */
    private long[] mMode;
    private long[] mWakeup;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getActivity());
        setPreferenceScreen(screen);

        final Preference intro = new Preference(getActivity());
        intro.setSummary(R.string.deep_sleep_intro);
        intro.setSelectable(false);
        screen.addPreference(intro);

        mState = new Preference(getActivity());
        mState.setTitle(R.string.deep_sleep_reset);
        mState.setOnPreferenceClickListener(p -> {
            SystemProperties.set(MODE_PROP, "default");
            SystemProperties.set(WAKEUP_PROP, "default");
            refreshSoon();
            return true;
        });
        screen.addPreference(mState);

        final PreferenceCategory steps = new PreferenceCategory(getActivity());
        steps.setTitle(R.string.deep_sleep_steps);
        screen.addPreference(steps);
        for (int i = 0; i < MODE_STEPS.length; i++) {
            final int bit = MODE_STEPS[i][0];
            final SwitchPreference sw = new SwitchPreference(getActivity());
            sw.setPersistent(false);
            sw.setTitle(MODE_STEPS[i][1]);
            sw.setSummary(MODE_STEPS[i][2]);
            sw.setOnPreferenceChangeListener((p, value) -> {
                change(MODE_PROP, mMode, bit, (Boolean) value);
                return true;
            });
            steps.addPreference(sw);
            mSteps[i] = sw;
        }

        final PreferenceCategory wake = new PreferenceCategory(getActivity());
        wake.setTitle(R.string.deep_sleep_wake);
        screen.addPreference(wake);
        mUsbWake = new SwitchPreference(getActivity());
        mUsbWake.setPersistent(false);
        mUsbWake.setTitle(R.string.wake_usb);
        mUsbWake.setSummary(R.string.wake_usb_summary);
        mUsbWake.setOnPreferenceChangeListener((p, value) -> {
            change(WAKEUP_PROP, mWakeup, USB_WAKEUP_BIT, (Boolean) value);
            return true;
        });
        wake.addPreference(mUsbWake);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
    }

    private void change(String prop, long[] value, int bit, boolean on) {
        if (value == null) return;
        final long v = on ? value[0] | (1L << bit) : value[0] & ~(1L << bit);
        value[0] = v;
        SystemProperties.set(prop, "0x" + Long.toHexString(v));
        refreshSoon();
    }

    private void refreshSoon() {
        mHandler.removeCallbacksAndMessages(null);
        mHandler.postDelayed(this::refresh, REFRESH_DELAY_MS);
    }

    private void refresh() {
        mMode = read("sleep_mode");
        mWakeup = read("sleep_wakeup");
        for (int i = 0; i < MODE_STEPS.length; i++) {
            mSteps[i].setEnabled(mMode != null);
            mSteps[i].setChecked(mMode != null && (mMode[0] & (1L << MODE_STEPS[i][0])) != 0);
        }
        mUsbWake.setEnabled(mWakeup != null);
        mUsbWake.setChecked(mWakeup != null && (mWakeup[0] & (1L << USB_WAKEUP_BIT)) != 0);
        if (mMode == null || mWakeup == null) {
            mState.setEnabled(false);
            mState.setSummary(R.string.deep_sleep_unsupported);
            return;
        }
        mState.setEnabled(mMode[0] != mMode[1] || mWakeup[0] != mWakeup[1]);
        mState.setSummary(getString(R.string.deep_sleep_state,
                hex(mMode[0]) + " / " + hex(mWakeup[0]),
                hex(mMode[1]) + " / " + hex(mWakeup[1])));
    }

    private static String hex(long v) {
        return "0x" + Long.toHexString(v);
    }

    /** "0x20102 default 0x20702" from the kernel, as {current, default} */
    private static long[] read(String name) {
        try (BufferedReader r = new BufferedReader(new FileReader(PARAMS + name))) {
            final String[] f = r.readLine().trim().split("\\s+");
            if (f.length < 3) return null;
            return new long[] {Long.decode(f[0]), Long.decode(f[2])};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
