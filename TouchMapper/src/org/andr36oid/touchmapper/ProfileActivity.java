package org.andr36oid.touchmapper;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.widget.Toast;

/** One app's touch controls: on or off, hints, edit, delete. */
public class ProfileActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String pkg = getIntent().getStringExtra(EditorActivity.EXTRA_PACKAGE);
        if (pkg == null) {
            finish();
            return;
        }
        setTitle(ProfilesFragment.label(getPackageManager(), pkg));
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        if (savedInstanceState == null) {
            final ProfileFragment f = new ProfileFragment();
            final Bundle args = new Bundle();
            args.putString(EditorActivity.EXTRA_PACKAGE, pkg);
            f.setArguments(args);
            getFragmentManager().beginTransaction().replace(android.R.id.content, f).commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    /** Starts the app; the editor comes up on top once it is showing. */
    static void editInApp(Context context, String pkg) {
        final Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Toast.makeText(context, R.string.profile_cant_start, Toast.LENGTH_LONG).show();
            return;
        }
        WatcherService.editWhenOpen(context, pkg);
        Toast.makeText(context, R.string.profile_edit_starting, Toast.LENGTH_LONG).show();
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static class ProfileFragment extends PreferenceFragment {

        private String mPackage;
        private SwitchPreference mEnabled;
        private SwitchPreference mHints;
        private Preference mControls;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mPackage = getArguments().getString(EditorActivity.EXTRA_PACKAGE);
            final Context context = getActivity();
            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
            setPreferenceScreen(screen);

            mEnabled = new SwitchPreference(context);
            mEnabled.setPersistent(false);
            mEnabled.setTitle(R.string.profile_enabled);
            mEnabled.setSummary(R.string.profile_enabled_summary);
            mEnabled.setOnPreferenceChangeListener((p, value) -> update(profile -> {
                profile.enabled = (Boolean) value;
            }));
            screen.addPreference(mEnabled);

            mHints = new SwitchPreference(context);
            mHints.setPersistent(false);
            mHints.setTitle(R.string.profile_hints);
            mHints.setSummary(R.string.profile_hints_summary);
            mHints.setOnPreferenceChangeListener((p, value) -> update(profile -> {
                profile.hints = (Boolean) value;
            }));
            screen.addPreference(mHints);

            final Preference edit = new Preference(context);
            edit.setTitle(R.string.profile_edit);
            edit.setSummary(getString(R.string.profile_edit_summary,
                    ProfilesFragment.label(context.getPackageManager(), mPackage)));
            edit.setOnPreferenceClickListener(p -> {
                editInApp(context, mPackage);
                return true;
            });
            screen.addPreference(edit);

            final PreferenceCategory controls = new PreferenceCategory(context);
            controls.setTitle(R.string.profile_controls);
            screen.addPreference(controls);
            mControls = new Preference(context);
            mControls.setSelectable(false);
            controls.addPreference(mControls);

            final Preference delete = new Preference(context);
            delete.setTitle(R.string.profile_delete);
            delete.setOnPreferenceClickListener(p -> {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.profile_delete)
                        .setMessage(R.string.profile_delete_message)
                        .setPositiveButton(R.string.profile_delete_confirm, (d, w) -> {
                            Profiles.delete(context, mPackage);
                            getActivity().finish();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });
            screen.addPreference(delete);
        }

        @Override
        public void onResume() {
            super.onResume();
            final Profile profile = Profiles.load(mPackage);
            if (profile == null) {
                getActivity().finish();
                return;
            }
            mEnabled.setChecked(profile.enabled);
            mHints.setChecked(profile.hints);
            mControls.setSummary(describe(getActivity(), profile));
        }

        private interface Change {
            void apply(Profile profile);
        }

        private boolean update(Change change) {
            final Profile profile = Profiles.load(mPackage);
            if (profile == null) {
                return false;
            }
            change.apply(profile);
            return Profiles.save(getActivity(), profile);
        }

        /** One line per control, e.g. "A: tap" or "L2 + A: swipe up". */
        static CharSequence describe(Context context, Profile profile) {
            if (profile.controls.isEmpty()) {
                return context.getString(R.string.profile_empty);
            }
            final StringBuilder sb = new StringBuilder();
            for (Profile.Control c : profile.controls) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                if (c.layer == 1 && profile.shift != null) {
                    sb.append(Inputs.longLabel(profile.shift)).append(" + ");
                }
                sb.append(Inputs.longLabel(c.input())).append(": ");
                sb.append(typeName(context, c.type));
            }
            return sb;
        }

        private static String typeName(Context context, String type) {
            switch (type) {
                case Profile.HOLD: return context.getString(R.string.type_hold);
                case Profile.JOYSTICK: return context.getString(R.string.type_joystick);
                case Profile.CAMERA: return context.getString(R.string.type_camera);
                case Profile.SWIPE: return context.getString(R.string.type_swipe);
                default: return context.getString(R.string.type_tap);
            }
        }
    }
}
