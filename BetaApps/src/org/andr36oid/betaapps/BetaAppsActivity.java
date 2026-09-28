package org.andr36oid.betaapps;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The Beta apps screen: a notice that this isn't a release build, the apps from the beta list
 * with a tick box each, and Install selected / Not now. All on the buttons: the D-pad moves,
 * A ticks an app or presses a button, B is Not now, L1 jumps to the list, R1 to the buttons.
 *
 * <p>The ticked apps are installed one after the other through a PackageInstaller session, as
 * normal apps. The system uid holds INSTALL_PACKAGES, so no confirmation dialog shows up.
 */
public class BetaAppsActivity extends Activity {

    private static final String ACTION_INSTALL_RESULT =
            "org.andr36oid.betaapps.action.INSTALL_RESULT";
    private static final String EXTRA_ROW = "row";

    private enum State { IDLE, QUEUED, COPYING, INSTALLING, DONE, FAILED }

    private final class Row {
        final BetaList.Entry entry;
        String label;
        String versionName;
        long apkVersion;
        Drawable icon;
        long installed;
        State state = State.IDLE;
        String message;

        View view;
        CheckBox check;
        TextView status;

        Row(BetaList.Entry entry) {
            this.entry = entry;
        }

        boolean apkPresent() {
            return label != null;
        }

        /** Whether ticking it makes sense: the APK is there and isn't installed yet. */
        boolean offerable() {
            return apkPresent() && installed < apkVersion && state != State.DONE;
        }
    }

    private final List<Row> mRows = new ArrayList<>();
    private final ArrayDeque<Row> mQueue = new ArrayDeque<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private TextView mInstallButton;
    private TextView mCloseButton;
    private TextView mResult;
    private LinearLayout mHints;
    private ScrollView mScroll;
    private boolean mBusy;
    private boolean mInstalledSome;
    private int mOk;
    private int mFailed;

    private final BroadcastReceiver mResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int index = intent.getIntExtra(EXTRA_ROW, -1);
            if (index < 0 || index >= mRows.size()) {
                return;
            }
            final Row row = mRows.get(index);
            final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Not expected for the system uid, but let the tester confirm if it happens
                final Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) {
                finishRow(row, State.DONE, null);
            } else {
                final String message = intent.getStringExtra(
                        PackageInstaller.EXTRA_STATUS_MESSAGE);
                finishRow(row, State.FAILED, message != null ? message : "error " + status);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Opened now, by itself or by hand: no need to open by itself any more this boot
        WhenReadyJobService.cancel(this);

        for (BetaList.Entry entry : BetaList.read()) {
            final Row row = new Row(entry);
            loadApk(row);
            mRows.add(row);
        }

        registerReceiver(mResultReceiver, new IntentFilter(ACTION_INSTALL_RESULT));

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.addView(buildHeader(), matchWrap());

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(buildNotice(), matchWrap());
        if (mRows.isEmpty()) {
            final TextView empty = text(getString(R.string.beta_empty), 15, R.color.beta_detail);
            empty.setPadding(dp(4), dp(12), dp(4), dp(12));
            content.addView(empty);
        }
        for (int i = 0; i < mRows.size(); i++) {
            final LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(6);
            content.addView(buildRow(i), params);
        }
        mScroll = new ScrollView(this);
        mScroll.setVerticalFadingEdgeEnabled(true);
        mScroll.setFadingEdgeLength(dp(16));
        mScroll.addView(content);
        root.addView(mScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildButtons(), matchWrap());

        mHints = new LinearLayout(this);
        mHints.setOrientation(LinearLayout.HORIZONTAL);
        mHints.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        mHints.setPadding(0, dp(6), 0, 0);
        root.addView(mHints, matchWrap());

        setContentView(root);
        updateButtons();
        updateHints();
        initialFocus().requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The tester may have removed or installed an app in the meantime
        if (!mBusy) {
            for (Row row : mRows) {
                if (row.state == State.IDLE || row.state == State.DONE) {
                    row.installed = BetaList.installedVersion(this, row.entry.packageName);
                    if (row.installed < row.apkVersion) {
                        // Uninstalled again
                        if (row.state == State.DONE) {
                            row.check.setChecked(false);
                        }
                        row.state = State.IDLE;
                    }
                    refreshRow(row);
                }
            }
            updateButtons();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mResultReceiver);
        super.onDestroy();
    }

    private void loadApk(Row row) {
        final PackageManager pm = getPackageManager();
        final String path = row.entry.apk.getAbsolutePath();
        final PackageInfo info = row.entry.apk.isFile() ? pm.getPackageArchiveInfo(path, 0) : null;
        if (info == null || info.applicationInfo == null) {
            Log.w(BetaList.TAG, "Can't read " + path);
            row.installed = BetaList.installedVersion(this, row.entry.packageName);
            return;
        }
        final ApplicationInfo app = info.applicationInfo;
        // The label and icon come from the APK's own resources
        app.sourceDir = path;
        app.publicSourceDir = path;
        row.label = app.loadLabel(pm).toString();
        row.icon = app.loadIcon(pm);
        row.versionName = info.versionName;
        row.apkVersion = info.getLongVersionCode();
        row.installed = BetaList.installedVersion(this, row.entry.packageName);
    }

    private View buildHeader() {
        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(4), dp(8));

        final TextView title = text(getString(R.string.beta_title), 22, R.color.beta_text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final String version = SystemProperties.get("ro.andr36oid.version", "");
        header.addView(text(version.isEmpty() ? "andr36oid" : "andr36oid " + version, 13,
                R.color.beta_dim));
        return header;
    }

    private View buildNotice() {
        final String version = SystemProperties.get("ro.andr36oid.version", "");
        // v<date>-<kind>, e.g. v2026-09-28-beta
        final int dash = version.lastIndexOf('-');
        final String kind = dash >= 0 && dash < version.length() - 1
                ? version.substring(dash + 1) : getString(R.string.beta_kind_unknown);

        final LinearLayout notice = new LinearLayout(this);
        notice.setOrientation(LinearLayout.VERTICAL);
        notice.setBackgroundResource(R.drawable.notice_background);
        notice.setPadding(dp(14), dp(10), dp(14), dp(10));

        final TextView main = text(getString(R.string.beta_notice, kind,
                version.isEmpty() ? getString(R.string.beta_version_unknown) : version),
                15, R.color.beta_text);
        main.setLineSpacing(dp(2), 1f);
        notice.addView(main);
        final TextView more = text(getString(R.string.beta_notice_more), 13, R.color.beta_detail);
        more.setPadding(0, dp(4), 0, 0);
        notice.addView(more);
        return notice;
    }

    private View buildRow(int index) {
        final Row row = mRows.get(index);

        final LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setBackgroundResource(R.drawable.row_background);
        view.setPadding(dp(8), dp(6), dp(12), dp(6));
        view.setFocusable(true);
        // No touchscreen, but a joystick mouse click puts the window in touch mode
        view.setFocusableInTouchMode(true);
        view.setOnClickListener(v -> toggle(row));
        view.setOnFocusChangeListener((v, hasFocus) -> updateHints());

        final CheckBox check = new CheckBox(this);
        check.setFocusable(false);
        check.setClickable(false);
        view.addView(check);

        final ImageView icon = new ImageView(this);
        if (row.icon != null) {
            icon.setImageDrawable(row.icon);
        }
        final LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36),
                dp(36));
        iconParams.setMarginEnd(dp(10));
        view.addView(icon, iconParams);

        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.BOTTOM);
        final TextView label = text(row.apkPresent() ? row.label : row.entry.packageName, 16,
                R.color.beta_text);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        titleLine.addView(label);
        if (row.versionName != null) {
            final TextView version = text(getString(R.string.beta_version, row.versionName), 13,
                    R.color.beta_dim);
            version.setPadding(dp(8), 0, 0, dp(1));
            version.setSingleLine(true);
            titleLine.addView(version);
        }
        texts.addView(titleLine);

        if (!row.entry.description.isEmpty()) {
            final TextView description = text(row.entry.description, 13, R.color.beta_detail);
            description.setPadding(0, dp(1), 0, 0);
            texts.addView(description);
        }

        final TextView status = text("", 13, R.color.beta_dim);
        status.setPadding(0, dp(2), 0, 0);
        texts.addView(status);

        view.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.view = view;
        row.check = check;
        row.status = status;
        refreshRow(row);
        return view;
    }

    private View buildButtons() {
        final LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(8), 0, 0);

        mResult = text("", 13, R.color.beta_detail);
        buttons.addView(mResult, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mInstallButton = button(getString(R.string.beta_install));
        mInstallButton.setOnClickListener(v -> installSelected());
        buttons.addView(mInstallButton);

        mCloseButton = button(getString(R.string.beta_not_now));
        mCloseButton.setOnClickListener(v -> close());
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(8));
        buttons.addView(mCloseButton, params);
        return buttons;
    }

    private TextView button(String label) {
        final TextView button = text(label, 15, R.color.beta_text);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setBackgroundResource(R.drawable.row_background);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setSingleLine(true);
        button.setOnFocusChangeListener((v, hasFocus) -> updateHints());
        return button;
    }

    private View initialFocus() {
        for (Row row : mRows) {
            if (row.offerable()) {
                return row.view;
            }
        }
        return mCloseButton;
    }

    private void toggle(Row row) {
        if (mBusy || !row.offerable()) {
            return;
        }
        final boolean checked = !row.check.isChecked();
        row.check.setChecked(checked);
        if (checked && row.entry.needs != null) {
            // A plugin: tick the app it plugs into too, unless that is installed already
            for (Row other : mRows) {
                if (other.entry.packageName.equals(row.entry.needs) && other.offerable()
                        && other.installed < 0) {
                    other.check.setChecked(true);
                }
            }
        }
        updateButtons();
    }

    private int checkedCount() {
        int count = 0;
        for (Row row : mRows) {
            if (row.offerable() && row.check.isChecked()) {
                count++;
            }
        }
        return count;
    }

    private void refreshRow(Row row) {
        if (row.check == null) {
            return;
        }
        final boolean offerable = row.offerable();
        row.check.setEnabled(offerable && !mBusy);
        if (!offerable) {
            row.check.setChecked(row.state == State.DONE || row.installed >= row.apkVersion
                    && row.apkPresent());
        }
        int color = R.color.beta_dim;
        final String status;
        switch (row.state) {
            case QUEUED:
                status = getString(R.string.state_waiting);
                break;
            case COPYING:
                status = getString(R.string.state_copying, 0);
                color = R.color.beta_accent;
                break;
            case INSTALLING:
                status = getString(R.string.state_installing);
                color = R.color.beta_accent;
                break;
            case DONE:
                status = getString(R.string.state_install_ok);
                color = R.color.beta_ok;
                break;
            case FAILED:
                status = getString(R.string.state_install_failed, row.message);
                color = R.color.beta_error;
                break;
            default:
                if (!row.apkPresent()) {
                    status = getString(R.string.state_missing);
                    color = R.color.beta_error;
                } else if (row.installed < 0) {
                    status = getString(R.string.state_not_installed);
                } else if (row.installed < row.apkVersion) {
                    status = getString(R.string.state_update, installedVersionName(row));
                    color = R.color.beta_accent;
                } else if (row.installed == row.apkVersion) {
                    status = getString(R.string.state_installed);
                    color = R.color.beta_ok;
                } else {
                    status = getString(R.string.state_newer_installed);
                    color = R.color.beta_ok;
                }
        }
        row.status.setText(status);
        row.status.setTextColor(getColor(color));
    }

    private String installedVersionName(Row row) {
        try {
            final String name = getPackageManager().getPackageInfo(row.entry.packageName, 0)
                    .versionName;
            return name != null ? name : String.valueOf(row.installed);
        } catch (PackageManager.NameNotFoundException e) {
            return String.valueOf(row.installed);
        }
    }

    private void updateButtons() {
        if (mInstallButton == null) {
            return;
        }
        final int count = checkedCount();
        mInstallButton.setText(count > 0 ? getString(R.string.beta_install_count, count)
                : getString(R.string.beta_install));
        final boolean canInstall = count > 0 && !mBusy;
        mInstallButton.setEnabled(canInstall);
        mInstallButton.setAlpha(canInstall ? 1f : 0.45f);
        mCloseButton.setText(mInstalledSome ? R.string.beta_done : R.string.beta_not_now);
        mCloseButton.setEnabled(!mBusy);
        mCloseButton.setAlpha(mBusy ? 0.45f : 1f);
    }

    /** The bottom line lists what the buttons do. */
    private void updateHints() {
        if (mHints == null) {
            return;
        }
        mHints.removeAllViews();
        hint(getString(R.string.key_dpad), R.string.hint_choose);
        hint("A", R.string.hint_tick);
        hint("L1 / R1", R.string.hint_jump);
        hint("B", mInstalledSome ? R.string.hint_close : R.string.hint_not_now);
    }

    private void hint(String keys, int label) {
        final String[] alternatives = keys.split(" / ");
        for (int i = 0; i < alternatives.length; i++) {
            if (i > 0) {
                final TextView slash = text("/", 13, R.color.beta_dim);
                slash.setPadding(dp(4), 0, dp(4), 0);
                mHints.addView(slash);
            }
            final TextView badge = text(alternatives[i], 13, R.color.beta_text);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            badge.setBackgroundResource(R.drawable.key_badge);
            badge.setPadding(dp(8), dp(2), dp(8), dp(3));
            badge.setSingleLine(true);
            mHints.addView(badge);
        }
        final TextView text = text(getString(label), 13, R.color.beta_dim);
        text.setPadding(dp(6), 0, dp(14), 0);
        mHints.addView(text);
    }

    private void close() {
        if (mBusy) {
            return;
        }
        // Not now counts as asked: a later build only asks about apps that are new then
        BetaList.markAsked(this, entries());
        finish();
    }

    private List<BetaList.Entry> entries() {
        final List<BetaList.Entry> entries = new ArrayList<>();
        for (Row row : mRows) {
            entries.add(row.entry);
        }
        return entries;
    }

    // Installing

    private void installSelected() {
        if (mBusy) {
            return;
        }
        for (Row row : mRows) {
            if (row.offerable() && row.check.isChecked()) {
                row.state = State.QUEUED;
                mQueue.add(row);
            }
        }
        if (mQueue.isEmpty()) {
            return;
        }
        BetaList.markAsked(this, entries());
        // Move focus off the buttons first, they are disabled while installing
        mQueue.peek().view.requestFocus();
        mBusy = true;
        mOk = 0;
        mFailed = 0;
        mResult.setText("");
        for (Row row : mRows) {
            refreshRow(row);
        }
        updateButtons();
        installNext();
    }

    private void installNext() {
        final Row row = mQueue.poll();
        if (row == null) {
            mBusy = false;
            mResult.setText(getString(R.string.result_summary, mOk, mFailed));
            for (Row r : mRows) {
                refreshRow(r);
            }
            updateButtons();
            updateHints();
            mCloseButton.requestFocus();
            return;
        }
        row.state = State.COPYING;
        refreshRow(row);
        // Follow the install down the list
        row.view.requestFocus();
        final int index = mRows.indexOf(row);
        new Thread(() -> copyAndCommit(row, index), "BetaAppsInstall").start();
    }

    /** Runs off the main thread: streams the APK into a session and commits it. */
    private void copyAndCommit(Row row, int index) {
        final PackageInstaller installer = getPackageManager().getPackageInstaller();
        final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(row.entry.packageName);
        params.setSize(row.entry.apk.length());
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        // Like the emulator preinstaller's "pm install -g": grant what the app asks for, so
        // testers don't get a row of permission prompts on a console without a touchscreen
        params.setGrantedRuntimePermissions(null);
        params.setWhitelistedRestrictedPermissions(
                PackageInstaller.SessionParams.RESTRICTED_PERMISSIONS_ALL);

        int sessionId = -1;
        try {
            sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                final long total = Math.max(1, row.entry.apk.length());
                try (InputStream in = new FileInputStream(row.entry.apk);
                     OutputStream out = session.openWrite("base.apk", 0, total)) {
                    final byte[] buffer = new byte[256 * 1024];
                    long written = 0;
                    int lastPercent = -1;
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        written += n;
                        final int percent = (int) (written * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            mHandler.post(() -> showCopyProgress(row, percent));
                        }
                    }
                    session.fsync(out);
                }
                mHandler.post(() -> {
                    row.state = State.INSTALLING;
                    refreshRow(row);
                });
                final Intent result = new Intent(ACTION_INSTALL_RESULT)
                        .setPackage(getPackageName())
                        .putExtra(EXTRA_ROW, index);
                final PendingIntent pending = PendingIntent.getBroadcast(this, index, result,
                        PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(pending.getIntentSender());
            }
        } catch (IOException | RuntimeException e) {
            Log.w(BetaList.TAG, "Installing " + row.entry.packageName + " failed", e);
            if (sessionId >= 0) {
                try {
                    installer.abandonSession(sessionId);
                } catch (RuntimeException ignored) {
                    // Already gone
                }
            }
            final String message = e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName();
            mHandler.post(() -> finishRow(row, State.FAILED, message));
        }
    }

    private void showCopyProgress(Row row, int percent) {
        if (row.state == State.COPYING) {
            row.status.setText(getString(R.string.state_copying, percent));
        }
    }

    private void finishRow(Row row, State state, String message) {
        if (row.state != State.COPYING && row.state != State.INSTALLING) {
            return;
        }
        row.state = state;
        row.message = message;
        if (state == State.DONE) {
            mOk++;
            mInstalledSome = true;
            row.installed = BetaList.installedVersion(this, row.entry.packageName);
        } else {
            mFailed++;
            Log.w(BetaList.TAG, "Installing " + row.entry.packageName + " failed: " + message);
        }
        refreshRow(row);
        installNext();
    }

    // Buttons

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        // Handled on both down and up, so their fallback keys (B is Back, A is center)
        // never get generated.
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (!down && !event.isCanceled()) {
                    close();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && event.getRepeatCount() == 0) {
                    final View focused = getCurrentFocus();
                    if (focused != null && focused.isEnabled()) {
                        focused.performClick();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                if (down && !mRows.isEmpty()) {
                    mRows.get(0).view.requestFocus();
                    mScroll.smoothScrollTo(0, 0);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down) {
                    (mInstallButton.isEnabled() ? mInstallButton : mCloseButton).requestFocus();
                }
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // Helpers

    private TextView text(CharSequence content, float sp, int color) {
        final TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(getColor(color));
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
