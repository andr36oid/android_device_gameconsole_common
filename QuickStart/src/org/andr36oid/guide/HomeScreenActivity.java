package org.andr36oid.guide;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.BooleanSupplier;

/**
 * Settings > Apps &amp; notifications > Home screen: which of our two home screens is home,
 * and removing or bringing back the other one. Only enabled when the build ships both (see
 * FirstBootReceiver). The work is in {@link HomeChoice}.
 *
 * <p>One block per home screen: name, what it is, whether it's home, installed or removed,
 * and its buttons. The home app has no buttons: it can't be removed, and switching is done
 * on the other one.
 */
public class HomeScreenActivity extends Activity {

    private LinearLayout mList;
    private boolean mBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int pad = dp(16);
        final ScrollView scroll = new ScrollView(this);
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(pad, pad / 2, pad, pad);
        scroll.addView(mList);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        mList.removeAllViews();
        final TextView intro = text(getString(R.string.home_screen_intro),
                android.R.attr.textAppearanceSmall);
        mList.addView(intro);
        final String home = HomeChoice.currentHome(this);
        Button first = null;
        for (ComponentName activity : HomeChoice.ourHomes()) {
            final Button b = addBlock(activity.getPackageName(), home);
            if (first == null) {
                first = b;
            }
        }
        if (first != null) {
            first.requestFocus();
        }
    }

    /** One home screen's block. Returns its first button, or null. */
    private Button addBlock(String pkg, String home) {
        final boolean daijishou = HomeChoice.DAIJISHOU.equals(pkg);
        final String name = getString(daijishou ? R.string.home_screen_daijishou
                : R.string.home_screen_launcher);
        final boolean installed = HomeChoice.isInstalled(this, pkg);
        final boolean isHome = pkg.equals(home);

        final TextView title = text(name, android.R.attr.textAppearanceMedium);
        title.setPadding(0, dp(16), 0, 0);
        mList.addView(title);
        mList.addView(text(getString(daijishou ? R.string.home_screen_daijishou_description
                : R.string.home_screen_launcher_description), android.R.attr.textAppearanceSmall));
        mList.addView(text(getString(isHome ? R.string.home_screen_state_home
                : installed ? R.string.home_screen_state_installed
                : R.string.home_screen_state_removed), android.R.attr.textAppearanceSmall));

        if (isHome || !HomeChoice.isShipped(this, pkg)) {
            return null;
        }
        final LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.START);
        mList.addView(buttons);
        if (!installed) {
            final Button restore = button(buttons, R.string.home_screen_restore);
            restore.setOnClickListener(v -> restore(pkg, name));
            return restore;
        }
        final Button use = button(buttons, R.string.home_screen_use);
        use.setOnClickListener(v -> makeHome(pkg, name));
        // Removing is only offered while the other one of ours is home
        if (home != null && HomeChoice.homeOf(home) != null) {
            button(buttons, R.string.home_screen_remove).setOnClickListener(
                    v -> confirmRemove(pkg, name));
        }
        return use;
    }

    private void makeHome(String pkg, String name) {
        if (mBusy) {
            return;
        }
        mBusy = true;
        final ComponentName activity = HomeChoice.homeOf(pkg);
        final String home = HomeChoice.currentHome(this);
        final ComponentName homeActivity = home != null ? HomeChoice.homeOf(home) : null;
        // Normally on already; if not, switch it on without Android asking
        HomeChoice.enableHome(this, activity, homeActivity);
        HomeChoice.makeHome(this, pkg, ok -> runOnUiThread(() -> {
            mBusy = false;
            Toast.makeText(this, ok ? getString(R.string.home_screen_now_home, name)
                    : getString(R.string.home_screen_failed), Toast.LENGTH_SHORT).show();
            refresh();
        }));
    }

    private void confirmRemove(String pkg, String name) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.home_screen_remove_confirm, name))
                .setPositiveButton(R.string.home_screen_remove, (d, w) -> remove(pkg))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void remove(String pkg) {
        inBackground(() -> HomeChoice.remove(this, pkg), null);
    }

    private void restore(String pkg, String name) {
        inBackground(() -> HomeChoice.restore(this, pkg),
                getString(R.string.home_screen_restored, name));
    }

    /** Runs a package change off the main thread, then shows the result. */
    private void inBackground(BooleanSupplier work, String done) {
        if (mBusy) {
            return;
        }
        mBusy = true;
        new Thread(() -> {
            final boolean ok = work.getAsBoolean();
            runOnUiThread(() -> {
                mBusy = false;
                if (!ok) {
                    Toast.makeText(this, R.string.home_screen_failed, Toast.LENGTH_SHORT)
                            .show();
                } else if (done != null) {
                    Toast.makeText(this, done, Toast.LENGTH_LONG).show();
                }
                if (!isDestroyed()) {
                    refresh();
                }
            });
        }, "HomeScreen").start();
    }

    private TextView text(CharSequence s, int appearance) {
        final TextView t = new TextView(this);
        final TypedValue value = new TypedValue();
        getTheme().resolveAttribute(appearance, value, true);
        t.setTextAppearance(value.resourceId);
        t.setText(s);
        return t;
    }

    private Button button(LinearLayout parent, int text) {
        final Button b = new Button(this);
        b.setText(text);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        parent.addView(b, lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
