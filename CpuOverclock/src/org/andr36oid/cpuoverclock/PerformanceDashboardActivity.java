package org.andr36oid.cpuoverclock;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.andr36oid.hints.HintBar;

/**
 * The Performance dashboard home. Three mode cards (Battery saver / Balanced / Performance) are
 * the one control a player needs: picking one applies the matching clock profile and folds in
 * the in-game clock boost and core pinning, so there are no separate expert switches here. The
 * deeper, rarer options live on their own themed pages reached from the rows below.
 *
 * Game Turbo and core affinity are stored as the same Settings.Global flags the game-mode
 * watcher reads, so setting them here configures what happens the next time a game opens.
 */
public class PerformanceDashboardActivity extends Activity {

    private static final int[] MODES = {
            PerformanceProfiles.BATTERY_SAVER,
            PerformanceProfiles.BALANCED,
            PerformanceProfiles.PERFORMANCE,
    };

    private static final String CPU_MAX = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq";
    // Resolve the SoC thermal zone by type rather than assuming thermal_zone0:
    // on several clones zone0 is a GPU or battery zone, so a fixed index would
    // label the wrong sensor as the console temperature. Matches PerfOverlay
    // Stats and the KT helper, which both scan for the "soc" zone.
    private String mSocTempPath;
    private boolean mSocTempResolved;

    private final View[] mCards = new View[MODES.length];
    private boolean mFocusedOnce;

    private TextView mStatus;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    // The SoC temperature drifts while you sit on this screen, so refresh it on a light timer;
    // the CPU ceiling also updates here the moment a mode is picked.
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            mHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.perf_dashboard);
        HintBar.attachDefault(this);
        mStatus = findViewById(R.id.perf_status);
        buildCards();
        bindRow(R.id.row_ingame, getString(R.string.perf_row_ingame_title),
                getString(R.string.perf_row_ingame_value), InGameActivity.class);
        bindRow(R.id.row_perapp, getString(R.string.perf_row_perapp_title),
                null, AppProfilesActivity.class);
        bindRow(R.id.row_advanced, getString(R.string.perf_row_advanced_title),
                getString(R.string.perf_row_advanced_value), AdvancedActivity.class);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    /** "Running now: CPU up to 1.30 GHz · 52°C", read straight from the kernel so it is real. */
    private void updateStatus() {
        final long maxKhz = readLong(CPU_MAX);
        if (maxKhz <= 0) {
            mStatus.setVisibility(View.GONE);
            return;
        }
        final long maxMhz = maxKhz / 1000;
        final String speed = maxMhz >= 1000
                ? String.format(java.util.Locale.US, "%.2f GHz", maxMhz / 1000.0)
                : maxMhz + " MHz";
        final String socTemp = socTempPath();
        final long milliC = socTemp == null ? -1 : readLong(socTemp);
        if (milliC > 0) {
            mStatus.setText(getString(R.string.perf_status, speed, Math.round(milliC / 1000.0)));
        } else {
            // No readable temperature: still show the clock, just drop the degrees.
            mStatus.setText(getString(R.string.perf_status_no_temp, speed));
        }
        mStatus.setVisibility(View.VISIBLE);
    }

    private static long readLong(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            final String line = r.readLine();
            return line == null ? -1 : Long.parseLong(line.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String readTrim(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            final String line = r.readLine();
            return line == null ? null : line.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** The SoC zone's temp node, resolved once by type (fallback: first zone). */
    private String socTempPath() {
        if (mSocTempResolved) {
            return mSocTempPath;
        }
        mSocTempResolved = true;
        final java.io.File[] zones = new java.io.File("/sys/class/thermal").listFiles(
                (dir, name) -> name.startsWith("thermal_zone"));
        if (zones != null && zones.length > 0) {
            for (java.io.File zone : zones) {
                final String type = readTrim(zone.getPath() + "/type");
                if (type != null && type.contains("soc")) {
                    mSocTempPath = zone.getPath() + "/temp";
                    return mSocTempPath;
                }
            }
            mSocTempPath = zones[0].getPath() + "/temp";
        }
        return mSocTempPath;
    }

    private void buildCards() {
        final LinearLayout container = findViewById(R.id.perf_cards);
        final LayoutInflater inflater = LayoutInflater.from(this);
        final int[] tags = {
                R.string.perf_mode_saver_tag,
                R.string.perf_mode_balanced_tag,
                R.string.perf_mode_performance_tag,
        };
        final PerformanceProfiles profiles = new PerformanceProfiles(this);
        for (int i = 0; i < MODES.length; i++) {
            final int mode = MODES[i];
            final View card = inflater.inflate(R.layout.perf_card, container, false);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(10);
            card.setLayoutParams(lp);
            ((TextView) card.findViewById(R.id.card_title)).setText(profiles.getName(mode));
            ((TextView) card.findViewById(R.id.card_tag)).setText(tags[i]);
            card.setOnClickListener(v -> selectMode(mode));
            container.addView(card);
            mCards[i] = card;
        }
    }

    private void selectMode(int mode) {
        // Fold the in-game boost and core pinning into the mode. The watcher reads these flags.
        final ContentResolver cr = getContentResolver();
        Settings.Global.putInt(cr, GameTurbo.ENABLED,
                mode == PerformanceProfiles.BATTERY_SAVER ? 0 : 1);
        Settings.Global.putInt(cr, GameTurbo.AFFINITY,
                mode == PerformanceProfiles.PERFORMANCE ? 1 : 0);

        final PerformanceProfiles profiles = new PerformanceProfiles(this);
        final boolean ok = profiles.set(mode);
        Toast.makeText(this, ok ? profiles.describeForToast(mode)
                : getString(R.string.profile_failed), Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void refresh() {
        final int current = new PerformanceProfiles(this).get();
        int chosenIndex = 0;
        for (int i = 0; i < MODES.length; i++) {
            final boolean selected = MODES[i] == current;
            mCards[i].setSelected(selected);
            mCards[i].findViewById(R.id.card_selected)
                    .setVisibility(selected ? View.VISIBLE : View.GONE);
            if (selected) chosenIndex = i;
        }

        final View perApp = findViewById(R.id.row_perapp);
        final TextView perAppValue = perApp.findViewById(R.id.row_value);
        if (AppProfiles.isEnabled(this)) {
            final int count = AppProfiles.countSet(this);
            perAppValue.setText(getResources()
                    .getQuantityString(R.plurals.app_profiles_on, count, count));
        } else {
            perAppValue.setText(R.string.app_profiles_off);
        }

        if (!mFocusedOnce) {
            mCards[chosenIndex].requestFocus();
            mFocusedOnce = true;
        }
    }

    private void bindRow(int rowId, String title, String value, Class<?> target) {
        final View row = findViewById(rowId);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        if (value != null) {
            ((TextView) row.findViewById(R.id.row_value)).setText(value);
        }
        row.setOnClickListener(v -> startActivity(new Intent(this, target)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
