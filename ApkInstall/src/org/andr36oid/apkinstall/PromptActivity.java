package org.andr36oid.apkinstall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Button;

import java.util.ArrayList;
import java.util.List;

/**
 * "Install apps from EASYROMS?" with every APK ticked, then the install's progress and
 * what happened. Install leaves the unticked ones alone for good (Handled); Not now asks
 * again at the next start.
 */
public class PromptActivity extends Activity {

    static final String EXTRA_NAMES = "names";
    static final String EXTRA_SIZES = "sizes";
    static final String EXTRA_MTIMES = "mtimes";

    private final List<Helper.Apk> mApks = new ArrayList<>();
    private boolean[] mTicked;
    private AlertDialog mDialog;
    private boolean mStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ArrayList<String> names = getIntent().getStringArrayListExtra(EXTRA_NAMES);
        final long[] sizes = getIntent().getLongArrayExtra(EXTRA_SIZES);
        final long[] mtimes = getIntent().getLongArrayExtra(EXTRA_MTIMES);
        if (names == null || sizes == null || mtimes == null || names.isEmpty()
                || sizes.length != names.size() || mtimes.length != names.size()) {
            finish();
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            mApks.add(new Helper.Apk(names.get(i), sizes[i], mtimes[i]));
        }
        mTicked = new boolean[mApks.size()];
        java.util.Arrays.fill(mTicked, true);
        ask();
    }

    private void ask() {
        final CharSequence[] items = new CharSequence[mApks.size()];
        for (int i = 0; i < items.length; i++) {
            final Helper.Apk a = mApks.get(i);
            items[i] = getString(R.string.apk_item, a.name,
                    Formatter.formatShortFileSize(this, a.size));
        }
        mDialog = new AlertDialog.Builder(this)
                .setTitle(getResources().getQuantityString(R.plurals.ask_title,
                        items.length, items.length))
                .setMultiChoiceItems(items, mTicked, (d, which, checked) -> {
                    mTicked[which] = checked;
                    updateInstallButton();
                })
                .setPositiveButton(R.string.install, (d, w) -> install())
                .setNegativeButton(R.string.not_now, null)
                .setOnDismissListener(d -> {
                    if (!mStarted) finish();
                })
                .create();
        mDialog.show();
        updateInstallButton();
    }

    private void updateInstallButton() {
        final Button b = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (b == null) return;
        int n = 0;
        for (boolean t : mTicked) if (t) n++;
        b.setEnabled(n > 0);
    }

    private void install() {
        mStarted = true;
        final List<Helper.Apk> picked = new ArrayList<>();
        final List<Helper.Apk> skipped = new ArrayList<>();
        for (int i = 0; i < mApks.size(); i++) {
            (mTicked[i] ? picked : skipped).add(mApks.get(i));
        }
        Handled.add(this, skipped);

        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(R.string.installing_title)
                .setMessage(getString(R.string.installing, 1, picked.size(),
                        picked.get(0).name))
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            final List<Helper.Result> results = Helper.install(picked,
                    (n, total, name) -> runOnUiThread(() -> progress.setMessage(
                            getString(R.string.installing, n, total, name))));
            final List<Helper.Apk> installed = new ArrayList<>();
            for (Helper.Result r : results) {
                if (!r.ok) continue;
                for (Helper.Apk a : picked) {
                    if (a.name.equals(r.name)) installed.add(a);
                }
            }
            // failed ones stay unanswered, so they're offered again next time
            Handled.add(this, installed);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                progress.dismiss();
                showResults(results);
            });
        }, "apk-install").start();
    }

    private void showResults(List<Helper.Result> results) {
        int ok = 0;
        final StringBuilder failed = new StringBuilder();
        for (Helper.Result r : results) {
            if (r.ok) {
                ok++;
            } else {
                failed.append('\n').append(getString(R.string.failed_item, r.name, r.reason));
            }
        }
        String message = getResources().getQuantityString(R.plurals.installed, ok, ok);
        if (failed.length() > 0) {
            message += "\n\n" + getString(R.string.failed) + failed;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.done_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> finish())
                .show();
    }
}
