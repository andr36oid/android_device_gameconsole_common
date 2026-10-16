package org.andr36oid.guide;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The setup wizard page after the home screen choice (org.lineageos.setupwizard,
 * lineage_wizard_script.xml): which of the bundled apps to install. They are the ones in
 * the gameconsole-emu service's z-foss-*.list files; all are ticked at first. The names and
 * icons come from the APKs themselves.
 *
 * Next writes the unticked packages to SETTING_SKIP, sets SETTING_CHOSEN and starts the
 * install (sys.andr36oid.apps_chosen, gameconsole-apps). The service keeps the bundled
 * apps back until then, and never installs one that was left out.
 */
public class SetupAppsActivity extends Activity {

    private static final String TAG = "SetupApps";

    private static final File PREINSTALL = new File("/system/etc/gameconsole/preinstall");
    /** Settings.Global, read by gameconsole-emu.sh: 1 once this page was left with Next */
    static final String SETTING_CHOSEN = "andr36oid_apps_chosen";
    /** Settings.Global, read by gameconsole-emu.sh: the unticked packages, comma separated */
    static final String SETTING_SKIP = "andr36oid_apps_skip";
    private static final String PROP_CHOSEN = "sys.andr36oid.apps_chosen";

    private static final class App {
        String pkg;
        CharSequence label;
        Drawable icon;
    }

    private LinearLayout mList;
    private final List<CheckBox> mBoxes = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.setup_apps);
        mList = findViewById(R.id.apps);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.all).setOnClickListener(v -> tickAll());
        findViewById(R.id.next).setOnClickListener(v -> done());
        findViewById(R.id.next).requestFocus();
        // Reading 20 APKs takes a moment: off the main thread
        new Thread(() -> {
            final List<App> apps = readApps();
            runOnUiThread(() -> show(apps));
        }, "SetupApps").start();
    }

    private List<App> readApps() {
        final List<App> apps = new ArrayList<>();
        final File[] lists = PREINSTALL.listFiles(
                (dir, name) -> name.startsWith("z-foss-") && name.endsWith(".list"));
        if (lists == null) {
            return apps;
        }
        final PackageManager pm = getPackageManager();
        for (File list : lists) {
            try (BufferedReader in = new BufferedReader(new FileReader(list))) {
                String line;
                while ((line = in.readLine()) != null) {
                    final String[] f = line.trim().split("\\s+");
                    if (f.length < 3 || f[0].startsWith("#")) {
                        continue;
                    }
                    final File apk = new File(PREINSTALL, f[2]);
                    final PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), 0);
                    if (info == null || info.applicationInfo == null) {
                        Log.w(TAG, "Can't read " + apk);
                        continue;
                    }
                    final ApplicationInfo ai = info.applicationInfo;
                    ai.sourceDir = apk.getPath();
                    ai.publicSourceDir = apk.getPath();
                    final App app = new App();
                    app.pkg = f[0];
                    app.label = ai.loadLabel(pm);
                    app.icon = ai.loadIcon(pm);
                    apps.add(app);
                }
            } catch (IOException e) {
                Log.w(TAG, "Can't read " + list, e);
            }
        }
        final Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return apps;
    }

    private void show(List<App> apps) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (apps.isEmpty()) {
            // A build without bundled apps: nothing to choose
            Log.i(TAG, "No bundled apps");
            done();
            return;
        }
        final Set<String> skipped = skipped();
        final int iconSize = dp(32);
        for (App app : apps) {
            final CheckBox box = (CheckBox) getLayoutInflater().inflate(
                    R.layout.setup_apps_item, mList, false);
            box.setText(app.label);
            box.setTag(app.pkg);
            // Ticked unless it was left out before (back on this page)
            box.setChecked(!skipped.contains(app.pkg));
            if (app.icon != null) {
                app.icon.setBounds(0, 0, iconSize, iconSize);
                box.setCompoundDrawablesRelative(null, null, app.icon, null);
            }
            mList.addView(box);
            mBoxes.add(box);
        }
        mBoxes.get(0).requestFocus();
    }

    private void tickAll() {
        // Everything ticked: untick all; otherwise tick all
        boolean all = true;
        for (CheckBox box : mBoxes) {
            all &= box.isChecked();
        }
        for (CheckBox box : mBoxes) {
            box.setChecked(!all);
        }
    }

    private Set<String> skipped() {
        final String value = Settings.Global.getString(getContentResolver(), SETTING_SKIP);
        return value == null ? new HashSet<>() : new HashSet<>(Arrays.asList(value.split(",")));
    }

    private void done() {
        final List<String> skip = new ArrayList<>();
        for (CheckBox box : mBoxes) {
            if (!box.isChecked()) {
                skip.add((String) box.getTag());
            }
        }
        Log.i(TAG, "Left out: " + skip);
        Settings.Global.putString(getContentResolver(), SETTING_SKIP, TextUtils.join(",", skip));
        Settings.Global.putInt(getContentResolver(), SETTING_CHOSEN, 1);
        try {
            SystemProperties.set(PROP_CHOSEN, "1");
        } catch (RuntimeException e) {
            // They are installed at the next start then
            Log.w(TAG, "Couldn't start the install", e);
        }
        SetupPages.next(this);
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }
}
