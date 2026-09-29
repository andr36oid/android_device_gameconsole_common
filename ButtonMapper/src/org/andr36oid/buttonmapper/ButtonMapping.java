package org.andr36oid.buttonmapper;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.input.InputManager;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.Arrays;
import java.util.List;

/**
 * The button layouts of the console: the profiles, which one is active and what that makes
 * each button do. Everything lives in Settings.Global, the framework applies
 * {@link ButtonRemap#SETTING}, which always holds the layout of the active profile.
 */
final class ButtonMapping {
    static final String SETTING_PROFILES = "hardware_button_profiles";
    static final String SETTING_ACTIVE_PROFILE = "hardware_button_profile";

    // Taking one of these from a button changes how the menus are used. A, B, Y, Start and L3
    // fall back to the others in the key character maps.
    private static final List<String> NAVIGATION_ACTIONS = Arrays.asList(
            "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "ENTER", "SPACE",
            "BACK", "ESCAPE", "BUTTON_A", "BUTTON_B", "BUTTON_Y", "BUTTON_START",
            "BUTTON_THUMBL");

    final HardwareButton[] buttons = HardwareButton.ALL;
    final String[] defaults = HardwareButton.defaultActions();
    final boolean[] present;

    private final Context mContext;
    private final ContentResolver mResolver;
    private ButtonProfiles mProfiles;

    ButtonMapping(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
        present = findPresentButtons(context);
        reload();
    }

    /**
     * Loads the profiles. When the layout in use was changed elsewhere (the volume button
     * reset, adb) the profile with that layout becomes the active one, or a new one is made.
     */
    void reload() {
        mProfiles = ButtonProfiles.parse(
                Settings.Global.getString(mResolver, SETTING_PROFILES),
                Settings.Global.getString(mResolver, SETTING_ACTIVE_PROFILE));
        final String inUse = ButtonRemap.load(mResolver).toString();
        if (normalize(mProfiles.getActive().remap).equals(inUse)) {
            return;
        }
        ButtonProfiles.Profile match = null;
        for (ButtonProfiles.Profile profile : mProfiles.getAll()) {
            if (normalize(profile.remap).equals(inUse)) {
                match = profile;
                break;
            }
        }
        if (match == null) {
            match = mProfiles.create(nextProfileName(), inUse);
        }
        mProfiles.setActive(match.id);
        saveProfiles();
    }

    List<ButtonProfiles.Profile> getProfiles() {
        return mProfiles.getAll();
    }

    ButtonProfiles.Profile getActiveProfile() {
        return mProfiles.getActive();
    }

    boolean canCreateProfile() {
        return !mProfiles.isFull();
    }

    String getName(ButtonProfiles.Profile profile) {
        return profile.isStandard() ? mContext.getString(R.string.profile_standard)
                : profile.name;
    }

    ButtonRemap getRemap(ButtonProfiles.Profile profile) {
        return ButtonRemap.parse(profile.remap);
    }

    ButtonRemap getActiveRemap() {
        return getRemap(getActiveProfile());
    }

    /** Returns what a button does with a layout. */
    String getAction(ButtonRemap remap, int index) {
        final String action = remap.get(buttons[index].scanCode);
        // The framework ignores changes to locked buttons.
        return action != null && !buttons[index].locked ? action : defaults[index];
    }

    void setAction(ButtonRemap remap, int index, String action) {
        remap.set(buttons[index].scanCode, action.equals(defaults[index]) ? null : action);
    }

    /** Returns what a layout lacks to be usable, see LayoutValidator. */
    List<Integer> findMissing(ButtonRemap remap) {
        final String[] actions = new String[buttons.length];
        for (int i = 0; i < buttons.length; i++) {
            actions[i] = getAction(remap, i);
        }
        return LayoutValidator.findMissing(actions, defaults, present);
    }

    /** Returns whether a button stops doing something the menus are used with. */
    boolean changesNavigation(ButtonRemap from, ButtonRemap to) {
        for (int i = 0; i < buttons.length; i++) {
            final String action = getAction(from, i);
            if (present[i] && NAVIGATION_ACTIONS.contains(action)
                    && !action.equals(getAction(to, i))) {
                return true;
            }
        }
        return false;
    }

    /** Returns the first present button doing one of the actions, in their order, or -1. */
    int findButtonFor(ButtonRemap remap, List<String> actions) {
        for (String action : actions) {
            for (int i = 0; i < buttons.length; i++) {
                if (present[i] && action.equals(getAction(remap, i))) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Saves a layout in the active profile and puts it to use. The standard profile can't be
     * changed, a new profile is made for the layout then and returned.
     */
    ButtonProfiles.Profile saveLayout(ButtonRemap remap) {
        ButtonProfiles.Profile created = null;
        ButtonProfiles.Profile profile = mProfiles.getActive();
        if (profile.isStandard()) {
            created = mProfiles.create(nextProfileName(), "");
            mProfiles.setActive(created.id);
            profile = created;
        }
        profile.remap = remap.toString();
        save();
        return created;
    }

    void activate(String id) {
        mProfiles.setActive(id);
        save();
    }

    /** Makes a new profile with the layout in use and activates it. */
    ButtonProfiles.Profile createProfile() {
        final ButtonProfiles.Profile profile =
                mProfiles.create(nextProfileName(), getActiveProfile().remap);
        mProfiles.setActive(profile.id);
        save();
        return profile;
    }

    boolean renameProfile(String id, String name) {
        if (!mProfiles.rename(id, name)) {
            return false;
        }
        saveProfiles();
        return true;
    }

    /** Deletes a profile, the standard layout is used if it was the active one. */
    void deleteProfile(String id) {
        mProfiles.delete(id);
        save();
    }

    /** Returns the next profile after the active one that is safe to use, or null. */
    ButtonProfiles.Profile findNextUsableProfile() {
        final List<ButtonProfiles.Profile> profiles = mProfiles.getAll();
        final int active = profiles.indexOf(getActiveProfile());
        for (int i = 1; i < profiles.size(); i++) {
            final ButtonProfiles.Profile profile = profiles.get((active + i) % profiles.size());
            if (findMissing(getRemap(profile)).isEmpty()) {
                return profile;
            }
        }
        return null;
    }

    ButtonProfiles snapshot() {
        return mProfiles.copy();
    }

    void restore(ButtonProfiles snapshot) {
        mProfiles = snapshot.copy();
        save();
    }

    private String nextProfileName() {
        return mProfiles.nextName(mContext.getString(R.string.profile_default_name));
    }

    private void saveProfiles() {
        Settings.Global.putString(mResolver, SETTING_PROFILES, mProfiles.serialize());
        Settings.Global.putString(mResolver, SETTING_ACTIVE_PROFILE, mProfiles.getActive().id);
    }

    /** Saves the profiles and puts the layout of the active one to use. */
    private void save() {
        saveProfiles();
        getActiveRemap().save(mResolver);
    }

    private static String normalize(String remap) {
        return ButtonRemap.parse(remap).toString();
    }

    /** Returns which buttons a built-in input device has, all of them if that can't be told. */
    private boolean[] findPresentButtons(Context context) {
        final int[] keyCodes = new int[buttons.length];
        for (int i = 0; i < buttons.length; i++) {
            keyCodes[i] = KeyEvent.keyCodeFromString(defaults[i]);
        }

        final boolean[] result = new boolean[buttons.length];
        boolean found = false;
        final InputManager inputManager = context.getSystemService(InputManager.class);
        for (int deviceId : inputManager.getInputDeviceIds()) {
            final InputDevice device = inputManager.getInputDevice(deviceId);
            if (device == null || device.isVirtual() || device.isExternal()) {
                continue;
            }
            final boolean[] hasKeys = device.hasKeys(keyCodes);
            for (int i = 0; i < result.length; i++) {
                if (hasKeys[i]) {
                    result[i] = true;
                    found = true;
                }
            }
        }
        if (!found) {
            Arrays.fill(result, true);
        }
        return result;
    }
}
