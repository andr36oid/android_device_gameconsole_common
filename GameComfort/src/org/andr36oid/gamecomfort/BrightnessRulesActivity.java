package org.andr36oid.gamecomfort;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import org.andr36oid.hints.HintBar;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native, scrollable/controller-focusable Display settings for the allowlist. */
public final class BrightnessRulesActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        HintBar.attachDefault(this);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        if (saved == null) getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new RulesFragment()).commit();
    }

    @Override public boolean onNavigateUp() {
        finish();
        return true;
    }

    public static final class RulesFragment extends PreferenceFragment {
        @Override public void onCreate(Bundle saved) {
            super.onCreate(saved);
            Activity activity = getActivity();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(activity);
            setPreferenceScreen(screen);
            SwitchPreference enabled = new SwitchPreference(activity);
            enabled.setKey(BrightnessRules.KEY_ENABLED);
            enabled.setTitle(R.string.app_brightness_enabled);
            enabled.setDefaultValue(false);
            enabled.setOnPreferenceChangeListener((preference, value) -> {
                boolean on = Boolean.TRUE.equals(value);
                if (Comfort.prefs(activity).edit().putBoolean(BrightnessRules.KEY_ENABLED, on).commit()) {
                    enabled.setChecked(on);
                    Comfort.update(activity);
                }
                return false;
            });
            screen.addPreference(enabled);
            Preference about = new Preference(activity);
            about.setSummary(R.string.app_brightness_about);
            about.setSelectable(false);
            screen.addPreference(about);
            PackageManager pm = activity.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            Map<String, ApplicationInfo> unique = new LinkedHashMap<>();
            for (ResolveInfo result : pm.queryIntentActivities(launcher, 0)) {
                if (result.activityInfo == null) continue;
                ApplicationInfo app = result.activityInfo.applicationInfo;
                if (app != null && app.enabled && result.activityInfo.enabled
                        && !BrightnessRules.excluded(activity, app.packageName)) {
                    unique.put(app.packageName, app);
                }
            }
            List<ApplicationInfo> apps = new ArrayList<>(unique.values());
            Collator collator = Collator.getInstance();
            apps.sort((a, b) -> {
                int compared = collator.compare(a.loadLabel(pm).toString(), b.loadLabel(pm).toString());
                return compared == 0 ? a.packageName.compareTo(b.packageName) : compared;
            });
            for (ApplicationInfo app : apps) {
                ListPreference rule = new ListPreference(activity);
                rule.setKey(BrightnessRules.key(app.packageName));
                rule.setTitle(app.loadLabel(pm));
                rule.setDialogTitle(app.loadLabel(pm));
                rule.setEntries(R.array.app_brightness_entries);
                rule.setEntryValues(R.array.app_brightness_values);
                rule.setDefaultValue("off");
                rule.setSummary("%s");
                rule.setOnPreferenceChangeListener((preference, value) -> {
                    String chosen = String.valueOf(value);
                    if (rule.findIndexOfValue(chosen) < 0) return false;
                    if (Comfort.prefs(activity).edit().putString(rule.getKey(), chosen).commit()) {
                        rule.setValue(chosen);
                        Comfort.update(activity);
                    }
                    return false;
                });
                screen.addPreference(rule);
                rule.setDependency(BrightnessRules.KEY_ENABLED);
            }
        }
    }
}
