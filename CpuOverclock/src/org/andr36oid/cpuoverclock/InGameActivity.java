package org.andr36oid.cpuoverclock;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.andr36oid.hints.HintBar;

/**
 * In-game (Game mode) options, reached from the Performance dashboard. Close background apps is
 * a Settings.Global flag the game-mode watcher reads, so it is a switch right here. The richer
 * game-mode screens (brightness rules, low-battery warning, continue) keep their own detailed
 * UI, so those rows open them directly.
 */
public class InGameActivity extends Activity {

    private static final String SETTING_CLOSE_APPS = "andr36oid_close_apps_on_game";
    private static final String GAMECOMFORT = "org.andr36oid.gamecomfort";

    private LinearLayout mContent;
    private LayoutInflater mInflater;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.perf_subpage);
        HintBar.attachDefault(this);
        ((TextView) findViewById(R.id.perf_subpage_title)).setText(R.string.perf_ingame_title);
        mContent = findViewById(R.id.perf_subpage_content);
        mInflater = LayoutInflater.from(this);

        addToggle(R.string.close_apps_on_game_title, R.string.close_apps_on_game_summary,
                SETTING_CLOSE_APPS, 0);
        addLink(R.string.perf_ingame_brightness_title, R.string.perf_ingame_brightness_summary,
                GAMECOMFORT + ".BrightnessRulesActivity");
        addLink(R.string.perf_ingame_lowbatt_title, R.string.perf_ingame_lowbatt_summary,
                GAMECOMFORT + ".WarningActivity");
        addLink(R.string.perf_ingame_continue_title, R.string.perf_ingame_continue_summary,
                GAMECOMFORT + ".ContinueActivity");

        // Land the highlight on the first row, not on the scroll container. Posted so focus is
        // assigned after the first layout pass.
        mContent.post(() -> {
            if (mContent.getChildCount() > 0) mContent.getChildAt(0).requestFocus();
        });
    }

    private void addToggle(int titleRes, int summaryRes, final String key, final int def) {
        final View row = mInflater.inflate(R.layout.perf_toggle_row, mContent, false);
        space(row);
        ((TextView) row.findViewById(R.id.row_title)).setText(titleRes);
        final TextView summary = row.findViewById(R.id.row_summary);
        summary.setText(summaryRes);
        summary.setVisibility(View.VISIBLE);
        final Switch toggle = row.findViewById(R.id.toggle_switch);
        final ContentResolver cr = getContentResolver();
        toggle.setChecked(Settings.Global.getInt(cr, key, def) != 0);
        row.setOnClickListener(v -> {
            final boolean on = !toggle.isChecked();
            Settings.Global.putInt(cr, key, on ? 1 : 0);
            toggle.setChecked(on);
        });
        mContent.addView(row);
    }

    private void addLink(int titleRes, int summaryRes, final String fqcn) {
        final View row = mInflater.inflate(R.layout.perf_row, mContent, false);
        space(row);
        ((TextView) row.findViewById(R.id.row_title)).setText(titleRes);
        final TextView summary = row.findViewById(R.id.row_summary);
        summary.setText(summaryRes);
        summary.setVisibility(View.VISIBLE);
        row.findViewById(R.id.row_value).setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            try {
                final Intent intent = new Intent();
                intent.setClassName(GAMECOMFORT, fqcn);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
                // The game-mode app isn't present; leave the row inert rather than crash.
            }
        });
        mContent.addView(row);
    }

    private void space(View row) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (mContent.getChildCount() > 0) lp.topMargin = dp(10);
        row.setLayoutParams(lp);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
