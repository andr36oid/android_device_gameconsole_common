package org.andr36oid.guide;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The setup wizard page after the home screen choice (org.lineageos.setupwizard,
 * lineage_wizard_script.xml): which of the bundled apps to install. They are the APKs on
 * the AUTOAPK partition (APPS, mounted by the first start's resizer), each described by
 * the <Name>.yml next to it: the description and a warning in the user's language,
 * "checked: false" for one that starts unticked, "needs: <package>" to tick the app a
 * plugin needs too, and "required: true" for the ones always installed, not shown here.
 * APKs people added themselves without a .yml are shown too, unticked. The names and
 * icons come from the APKs.
 *
 * Next writes the unticked ones (by file name without .apk) to SETTING_SKIP, sets
 * SETTING_CHOSEN and starts the install (sys.andr36oid.apps_chosen, gameconsole-apps).
 * The service keeps the apps back until then, and never installs one that was left out.
 */
public class SetupAppsActivity extends Activity {

    private static final String TAG = "SetupApps";

    private static final File APPS = new File("/mnt/andr36oid-apps");
    /** Settings.Global, read by gameconsole-emu.sh: 1 once this page was left with Next */
    static final String SETTING_CHOSEN = "andr36oid_apps_chosen";
    /** Settings.Global, read by gameconsole-emu.sh: the unticked apps by file name, comma separated */
    static final String SETTING_SKIP = "andr36oid_apps_skip";
    private static final String PROP_CHOSEN = "sys.andr36oid.apps_chosen";

    /** Amber, for a .yml's warning */
    private static final int WARNING = 0xffffb74d;

    private static final class App {
        /** the file name without .apk */
        String name;
        String pkg;
        CharSequence label;
        Drawable icon;
        String description;
        String warning;
        /** the package ticked along with this one */
        String needs;
        /** ticked at first: with a .yml unless it says "checked: false" */
        boolean checked;
        CheckBox box;
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
        final File[] apks = APPS.listFiles((dir, name) -> name.endsWith(".apk"));
        if (apks == null) {
            return apps;
        }
        final PackageManager pm = getPackageManager();
        for (File apk : apks) {
            final String name = apk.getName().substring(0, apk.getName().length() - 4);
            final Map<String, Object> yml = readYml(new File(APPS, name + ".yml"));
            if ("true".equals(yml.get("required"))) {
                continue;
            }
            final PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), 0);
            if (info == null || info.applicationInfo == null) {
                Log.w(TAG, "Can't read " + apk);
                continue;
            }
            final ApplicationInfo ai = info.applicationInfo;
            ai.sourceDir = apk.getPath();
            ai.publicSourceDir = apk.getPath();
            final App app = new App();
            app.name = name;
            app.pkg = info.packageName;
            app.label = ai.loadLabel(pm);
            app.icon = ai.loadIcon(pm);
            app.description = localized(yml.get("description"));
            app.warning = localized(yml.get("warning"));
            app.needs = yml.get("needs") instanceof String ? (String) yml.get("needs") : null;
            app.checked = !yml.isEmpty() && !"false".equals(yml.get("checked"));
            apps.add(app);
        }
        final Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return apps;
    }

    /**
     * The little YAML our .yml files use: "key: value" at the top, and a key with nothing
     * after it starts a map of indented "key: value" lines (the languages). Values may be
     * in double quotes. Empty when there is no file.
     */
    static Map<String, Object> readYml(File file) {
        final Map<String, Object> top = new HashMap<>();
        if (!file.isFile()) {
            return top;
        }
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            Map<String, String> map = null;
            String line;
            while ((line = in.readLine()) != null) {
                final String trimmed = line.trim();
                final int colon = trimmed.indexOf(':');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || colon <= 0) {
                    continue;
                }
                final String key = trimmed.substring(0, colon).trim();
                final String value = unquote(trimmed.substring(colon + 1).trim());
                if (Character.isWhitespace(line.charAt(0))) {
                    if (map != null) {
                        map.put(key, value);
                    }
                } else if (value.isEmpty()) {
                    map = new HashMap<>();
                    top.put(key, map);
                } else {
                    map = null;
                    top.put(key, value);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Can't read " + file, e);
        }
        return top;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"");
        }
        return value;
    }

    /** A .yml text in the user's language: pt-BR, then pt, then English */
    private String localized(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (!(value instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        final Map<String, String> texts = (Map<String, String>) value;
        final Locale locale = getResources().getConfiguration().getLocales().get(0);
        // Java still calls Indonesian "in"; the .yml files may say either
        String lang = locale.getLanguage();
        if (lang.equals("id")) {
            lang = "in";
        }
        for (String key : new String[] {lang + "-" + locale.getCountry(), lang,
                lang.equals("in") ? "id" : lang, "en"}) {
            final String text = texts.get(key);
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        return texts.isEmpty() ? null : texts.values().iterator().next();
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
        final Map<String, App> byPackage = new HashMap<>();
        for (App app : apps) {
            byPackage.put(app.pkg, app);
        }
        final int iconSize = dp(32);
        for (App app : apps) {
            final CheckBox box = (CheckBox) getLayoutInflater().inflate(
                    R.layout.setup_apps_item, mList, false);
            app.box = box;
            box.setText(describe(app));
            box.setTag(app.name);
            // Back on this page: as it was left; otherwise as its .yml says
            box.setChecked(skipped != null ? !skipped.contains(app.name) : app.checked);
            if (app.icon != null) {
                app.icon.setBounds(0, 0, iconSize, iconSize);
                box.setCompoundDrawablesRelative(null, null, app.icon, null);
            }
            mList.addView(box);
            mBoxes.add(box);
        }
        // a plugin ticks the app it needs
        for (App app : apps) {
            final App needed = app.needs == null ? null : byPackage.get(app.needs);
            if (needed != null) {
                app.box.setOnCheckedChangeListener((b, on) -> {
                    if (on) {
                        needed.box.setChecked(true);
                    }
                });
            }
        }
        mBoxes.get(0).requestFocus();
    }

    /** The app's name, and on a second line in small what it does, then its warning */
    private CharSequence describe(App app) {
        if (app.description == null && app.warning == null) {
            return app.label;
        }
        final SpannableStringBuilder text = new SpannableStringBuilder(app.label);
        final int start = text.length();
        if (app.description != null) {
            text.append('\n').append(app.description);
        }
        final int end = text.length();
        if (app.warning != null) {
            text.append('\n').append("\u26a0 ").append(app.warning);
            text.setSpan(new ForegroundColorSpan(WARNING), end, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.setSpan(new RelativeSizeSpan(0.8f), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        final TypedValue color = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.textColorSecondary, color, true)) {
            final int c = color.resourceId != 0
                    ? getColorStateList(color.resourceId).getDefaultColor() : color.data;
            text.setSpan(new ForegroundColorSpan(c), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
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

    /** What was left unticked when this page was left before, null the first time */
    private Set<String> skipped() {
        if (Settings.Global.getInt(getContentResolver(), SETTING_CHOSEN, 0) != 1) {
            return null;
        }
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
