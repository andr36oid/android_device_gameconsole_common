package org.andr36oid.playtime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;

import java.util.List;

public class PlayTimeFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    /** How many apps the most played list shows. */
    private static final int TOP_APPS = 8;

    private ChartPreference mChart;
    private PreferenceCategory mMostPlayed;
    private Thread mLoader;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.play_time_settings);
        mChart = (ChartPreference) findPreference("chart");
        mMostPlayed = (PreferenceCategory) findPreference("most_played");
        findPreference(Breaks.KEY_MINUTES).setOnPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reading a week of usage events takes a moment on this CPU, so off the main thread.
        final Context context = getActivity().getApplicationContext();
        mLoader = new Thread(() -> {
            final Usage usage = Usage.load(context);
            final Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> show(usage));
            }
        }, "PlayTimeLoader");
        mLoader.start();
    }

    @Override
    public void onPause() {
        mLoader = null;
        super.onPause();
    }

    private void show(Usage usage) {
        if (!isResumed() || mLoader == null) {
            return;
        }
        mChart.setUsage(usage);
        mMostPlayed.removeAll();
        final Context context = getActivity();
        final PackageManager pm = context.getPackageManager();
        final List<String> apps = usage.topApps();
        if (apps.isEmpty()) {
            final Preference empty = new Preference(context);
            empty.setSelectable(false);
            empty.setSummary(R.string.nothing_played);
            mMostPlayed.addPreference(empty);
            return;
        }
        for (int i = 0; i < apps.size() && i < TOP_APPS; i++) {
            final String pkg = apps.get(i);
            final long ms = usage.appMs.get(pkg);
            final Preference app = new Preference(context);
            app.setKey("app_" + pkg);
            try {
                final ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                app.setTitle(info.loadLabel(pm));
                app.setIcon(info.loadIcon(pm));
            } catch (PackageManager.NameNotFoundException e) {
                // Uninstalled since, still counts.
                app.setTitle(pkg);
            }
            app.setSummary(getString(R.string.app_time, Usage.format(context, ms),
                    usage.weekMs > 0 ? Math.round(100f * ms / usage.weekMs) : 0));
            // A click starts the game, handy as a "continue playing" list.
            final Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                app.setIntent(launch);
            } else {
                app.setSelectable(false);
            }
            mMostPlayed.addPreference(app);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        Breaks.schedule(getActivity(), Integer.parseInt((String) newValue));
        return true;
    }
}
