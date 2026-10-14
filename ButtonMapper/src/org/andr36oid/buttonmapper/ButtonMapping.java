package org.andr36oid.buttonmapper;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.input.InputManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The button layouts of the console: the profiles, which one is active and what that makes
 * each button do. Everything lives in Settings.Global, the framework applies
 * {@link ButtonRemap#SETTING}, which always holds the layout of the active profile.
 */
final class ButtonMapping {
    static final String SETTING_PROFILES = "hardware_button_profiles";
    static final String SETTING_ACTIVE_PROFILE = "hardware_button_profile";
    // Buttons the console doesn't have (fn,l3,r3,l2,r2,vol), null while not checked yet: then
    // the device tree hint counts. Set by Quick start and Settings > Controls.
    static final String SETTING_MISSING = "andr36oid_missing_buttons";
    // What acts as the Fn button: "" the real one, "vol_both", or "scan:<scan code>" a button.
    static final String SETTING_FN_STANDIN = "andr36oid_fn_standin";
    static final String FN_STANDIN_VOL_BOTH = "vol_both";
    private static final String FN_STANDIN_SCAN = "scan:";
    private static final String MISSING_HINT =
            "/proc/device-tree/odroidgo3-joypad/andr36oid,missing-buttons";
    // Chooser value for "this button is Fn", kept in SETTING_FN_STANDIN, not in the remap.
    static final String FN_ACTION = "FN";
    static final int SCAN_CODE_FN = 172;
    // The built-in pad, the only device with the extra buttons.
    private static final int PAD_VENDOR = 0x484b;
    private static final int PAD_PRODUCT = 0x1100;

    // Taking one of these from a button changes how the menus are used. A, B, Y, Start and L3
    // fall back to the others in the key character maps.
    private static final List<String> NAVIGATION_ACTIONS = Arrays.asList(
            "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "ENTER", "SPACE",
            "BACK", "ESCAPE", "BUTTON_A", "BUTTON_B", "BUTTON_Y", "BUTTON_START",
            "BUTTON_THUMBL");

    final HardwareButton[] buttons = HardwareButton.withExtras();
    final String[] defaults = new String[buttons.length];
    final boolean[] present;
    // Missing buttons and the stand-in can change while the screen is open, see reload().
    private final boolean[] mDetected;
    private boolean mFnMissing;
    // Index of the button that acts as Fn, -1 for none.
    private int mFnStandIn = -1;
    private String mFnStandInSetting;

    private final Context mContext;
    private final ContentResolver mResolver;
    private ButtonProfiles mProfiles;

    ButtonMapping(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
        for (int i = 0; i < buttons.length; i++) {
            defaults[i] = buttons[i].defaultAction;
        }
        mDetected = findPresentButtons(context);
        present = new boolean[buttons.length];
        reload();
    }

    /**
     * Loads the profiles. When the layout in use was changed elsewhere (the volume button
     * reset, adb) the profile with that layout becomes the active one, or a new one is made.
     */
    void reload() {
        loadMissingButtons();
        loadFnStandIn();
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

    /** Returns what a button does with a layout, FN_ACTION for the Fn stand-in. */
    String getAction(ButtonRemap remap, int index) {
        return getAction(remap, index, mFnStandIn);
    }

    private String getAction(ButtonRemap remap, int index, int fnStandIn) {
        if (index == fnStandIn) {
            return FN_ACTION;
        }
        final String action = remap.get(buttons[index].scanCode);
        // The framework ignores changes to locked buttons.
        return action != null && !buttons[index].locked ? action : defaults[index];
    }

    void setAction(ButtonRemap remap, int index, String action) {
        remap.set(buttons[index].scanCode, action.equals(defaults[index]) ? null : action);
    }

    /** Returns what a layout lacks to be usable, see LayoutValidator. */
    List<Integer> findMissing(ButtonRemap remap) {
        return findMissing(remap, mFnStandIn);
    }

    /** The same with another button, or none (-1), as the Fn stand-in. */
    List<Integer> findMissing(ButtonRemap remap, int fnStandIn) {
        final String[] actions = new String[buttons.length];
        for (int i = 0; i < buttons.length; i++) {
            actions[i] = getAction(remap, i, fnStandIn);
        }
        return LayoutValidator.findMissing(actions, defaults, present);
    }

    /** Whether the Fn chooser item makes sense for a button: not for Fn and Power. */
    boolean canBeFn(int index) {
        return buttons[index].scanCode != SCAN_CODE_FN && !buttons[index].locked;
    }

    int getFnStandIn() {
        return mFnStandIn;
    }

    /**
     * Returns whether something else acts as Fn now: another button, or both volume buttons
     * together (also when the console has no Fn button and nothing is set).
     */
    boolean hasOtherFnStandIn(int index) {
        if (mFnStandIn >= 0) {
            return mFnStandIn != index;
        }
        return FN_STANDIN_VOL_BOTH.equals(mFnStandInSetting)
                || (mFnMissing && TextUtils.isEmpty(mFnStandInSetting));
    }

    /** Makes a button act as Fn, or with -1 the real Fn button again. */
    void setFnStandIn(int index) {
        mFnStandIn = index;
        mFnStandInSetting = index >= 0 ? FN_STANDIN_SCAN + buttons[index].scanCode : "";
        Settings.Global.putString(mResolver, SETTING_FN_STANDIN, mFnStandInSetting);
    }

    String getName(int index) {
        return buttons[index].getName(mContext);
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

    /**
     * Takes the buttons the console doesn't have off the screen: Settings.Global
     * andr36oid_missing_buttons, or the device tree hint while that wasn't set.
     */
    private void loadMissingButtons() {
        String missing = Settings.Global.getString(mResolver, SETTING_MISSING);
        if (missing == null) {
            missing = readMissingHint();
        }
        final Set<Integer> scanCodes = new HashSet<>();
        mFnMissing = false;
        for (String name : missing.split(",")) {
            switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "fn":
                    mFnMissing = true;
                    scanCodes.add(SCAN_CODE_FN);
                    break;
                case "l3":
                    scanCodes.add(158);
                    break;
                case "r3":
                    scanCodes.add(125);
                    break;
                case "l2":
                    scanCodes.add(312);
                    break;
                case "r2":
                    scanCodes.add(313);
                    break;
                case "vol":
                    scanCodes.add(HardwareButton.KEY_VOLUMEDOWN);
                    scanCodes.add(HardwareButton.KEY_VOLUMEUP);
                    break;
            }
        }
        for (int i = 0; i < buttons.length; i++) {
            present[i] = mDetected[i] && !scanCodes.contains(buttons[i].scanCode);
        }
    }

    /** The device tree's list of missing buttons, comma separated, "" without one. */
    private static String readMissingHint() {
        try {
            // NUL separated strings
            return new String(Files.readAllBytes(Paths.get(MISSING_HINT))).replace('\0', ',');
        } catch (IOException | SecurityException e) {
            return "";
        }
    }

    private void loadFnStandIn() {
        mFnStandInSetting = Settings.Global.getString(mResolver, SETTING_FN_STANDIN);
        mFnStandIn = -1;
        if (mFnStandInSetting == null || !mFnStandInSetting.startsWith(FN_STANDIN_SCAN)) {
            return;
        }
        try {
            final int scanCode = Integer.parseInt(
                    mFnStandInSetting.substring(FN_STANDIN_SCAN.length()).trim());
            for (int i = 0; i < buttons.length; i++) {
                if (buttons[i].scanCode == scanCode && canBeFn(i)) {
                    mFnStandIn = i;
                }
            }
        } catch (NumberFormatException e) {
            // Not ours to fix, the framework ignores it too.
        }
    }

    /**
     * Returns which buttons a built-in input device has, all of the drawn ones if that can't
     * be told. Extra buttons only count on the built-in pad.
     */
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
            final boolean pad = device.getVendorId() == PAD_VENDOR
                    && device.getProductId() == PAD_PRODUCT;
            final boolean[] hasKeys = device.hasKeys(keyCodes);
            for (int i = 0; i < result.length; i++) {
                if (hasKeys[i] && (pad || buttons[i].extraNumber == 0)) {
                    result[i] = true;
                    found = true;
                }
            }
        }
        if (!found) {
            Arrays.fill(result, 0, HardwareButton.ALL.length, true);
        }
        return result;
    }
}
