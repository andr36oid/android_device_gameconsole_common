package org.andr36oid.touchmapper;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The list of apps with touch controls, "Add an app", and how to open the editor. Each app
 * opens its own page ({@link ProfileActivity}).
 */
public class ProfilesFragment extends PreferenceFragment {

    private PreferenceCategory mApps;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context context = getActivity();
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);

        final Preference intro = new Preference(context);
        intro.setSummary(R.string.settings_intro);
        intro.setSelectable(false);
        screen.addPreference(intro);

        mApps = new PreferenceCategory(context);
        mApps.setTitle(R.string.settings_apps);
        screen.addPreference(mApps);

        final PreferenceCategory how = new PreferenceCategory(context);
        how.setTitle(R.string.settings_how);
        screen.addPreference(how);
        final Preference shortcut = new Preference(context);
        shortcut.setTitle(R.string.settings_shortcut_title);
        shortcut.setSummary(R.string.settings_shortcut_summary);
        shortcut.setSelectable(false);
        how.addPreference(shortcut);
        final Preference help = new Preference(context);
        help.setTitle(R.string.help_title);
        help.setSummary(R.string.settings_help_summary);
        help.setOnPreferenceClickListener(p -> {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.help_title)
                    .setMessage(R.string.help_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        });
        how.addPreference(help);
    }

    @Override
    public void onResume() {
        super.onResume();
        fillApps();
    }

    private void fillApps() {
        final Context context = getActivity();
        final PackageManager pm = context.getPackageManager();
        mApps.removeAll();
        int order = 0;
        for (String pkg : Profiles.packages()) {
            final Profile profile = Profiles.load(pkg);
            final Preference p = new Preference(context);
            p.setOrder(order++);
            p.setTitle(label(pm, pkg));
            p.setIcon(icon(pm, pkg));
            p.setSummary(summary(context, profile));
            p.setOnPreferenceClickListener(pref -> {
                startActivity(new Intent(context, ProfileActivity.class)
                        .putExtra(EditorActivity.EXTRA_PACKAGE, pkg));
                return true;
            });
            mApps.addPreference(p);
        }
        final Preference add = new Preference(context);
        add.setOrder(order);
        add.setTitle(R.string.settings_add);
        add.setSummary(R.string.settings_add_summary);
        add.setIcon(R.drawable.ic_add);
        add.setOnPreferenceClickListener(p -> {
            pickApp();
            return true;
        });
        mApps.addPreference(add);
    }

    static CharSequence summary(Context context, Profile profile) {
        if (profile == null) {
            return context.getString(R.string.profile_unreadable);
        }
        if (profile.controls.isEmpty()) {
            return context.getString(R.string.profile_empty);
        }
        final String count = context.getResources().getQuantityString(R.plurals.control_count,
                profile.controls.size(), profile.controls.size());
        return context.getString(profile.enabled ? R.string.profile_on : R.string.profile_off,
                count);
    }

    static CharSequence label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm);
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static android.graphics.drawable.Drawable icon(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return pm.getDefaultActivityIcon();
        }
    }

    /** Apps with a launcher icon that don't have touch controls yet. */
    private void pickApp() {
        final Context context = getActivity();
        final PackageManager pm = context.getPackageManager();
        final Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final Set<String> seen = new HashSet<>(Profiles.packages());
        final List<ApplicationInfo> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            final String pkg = ri.activityInfo.packageName;
            if (seen.add(pkg) && EditReceiver.canHaveControls(context, pkg)) {
                apps.add(ri.activityInfo.applicationInfo);
            }
        }
        if (apps.isEmpty()) {
            Toast.makeText(context, R.string.settings_add_none, Toast.LENGTH_LONG).show();
            return;
        }
        final Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.loadLabel(pm).toString(),
                b.loadLabel(pm).toString()));
        final CharSequence[] labels = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).loadLabel(pm);
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_add)
                .setItems(labels, (d, which) -> {
                    final String pkg = apps.get(which).packageName;
                    Profiles.save(context, new Profile(pkg));
                    ProfileActivity.editInApp(context, pkg);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
