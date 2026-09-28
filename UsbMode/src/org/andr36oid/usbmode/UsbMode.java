package org.andr36oid.usbmode;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * The OTG port's role comes from the usb2 phy: the kernel starts it as a host, and writing
 * "peripheral" to otg_mode turns it into a device that a computer can talk to (adb, MTP).
 * Writing "host" switches it back. The kernel does not keep the choice across a restart.
 *
 * What the computer gets (file transfer, photos, or nothing but adb) is the USB function
 * Android sets for an unlocked screen. Android's own default is "no data transfer", which
 * leaves adb alone or MTP without any storage, and the USB notification to change it is hard
 * to reach on the console. So entering device mode also sets the function picked here,
 * file transfer unless changed, and leaving it goes back to Android's default.
 */
final class UsbMode {

    private static final String TAG = "UsbMode";

    private static final String OTG_MODE =
            "/sys/devices/platform/ff2c0000.syscon/ff2c0000.syscon:usb2-phy@100/otg_mode";

    private static final String HOST = "host";
    private static final String DEVICE = "peripheral";

    static final String KEY_FUNCTIONS = "functions";
    static final String FUNCTIONS_MTP = "mtp";
    static final String FUNCTIONS_PTP = "ptp";
    static final String FUNCTIONS_NONE = "none";

    private final Context mContext;

    UsbMode(Context context) {
        mContext = context;
    }

    boolean isSupported() {
        final String mode = read(OTG_MODE);
        return HOST.equals(mode) || DEVICE.equals(mode) || "otg".equals(mode);
    }

    /** True when the port is a device for a computer rather than a host for accessories. */
    boolean isDevice() {
        return DEVICE.equals(read(OTG_MODE));
    }

    boolean setDevice(boolean device) {
        // Pick the functions before the port turns into a device, so the computer sees the
        // right ones straight away instead of adb alone and then a reconnect.
        if (device) {
            applyFunctions(true);
        }
        try {
            write(OTG_MODE, device ? DEVICE : HOST);
        } catch (IOException e) {
            Log.e(TAG, "Couldn't switch the OTG port to " + (device ? DEVICE : HOST), e);
            return false;
        }
        if (!device) {
            applyFunctions(false);
        }
        showTile();
        return isDevice() == device;
    }

    /** What a computer gets in device mode: mtp, ptp or none. */
    String getFunctions() {
        return prefs().getString(KEY_FUNCTIONS, FUNCTIONS_MTP);
    }

    void setFunctions(String functions) {
        prefs().edit().putString(KEY_FUNCTIONS, functions).apply();
        if (isDevice()) {
            applyFunctions(true);
        }
    }

    /**
     * Sets the USB functions Android uses while the screen is unlocked (the same setting as
     * Developer options > Default USB configuration). adb is added on top by Android when USB
     * debugging is on.
     */
    private void applyFunctions(boolean device) {
        long functions = UsbManager.FUNCTION_NONE;
        if (device) {
            final String choice = getFunctions();
            if (FUNCTIONS_MTP.equals(choice)) {
                functions = UsbManager.FUNCTION_MTP;
            } else if (FUNCTIONS_PTP.equals(choice)) {
                functions = UsbManager.FUNCTION_PTP;
            }
        }
        final UsbManager usb = mContext.getSystemService(UsbManager.class);
        if (usb == null) {
            return;
        }
        try {
            if (device || usb.getScreenUnlockedFunctions() != functions) {
                usb.setScreenUnlockedFunctions(functions);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't set the USB functions", e);
        }
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    /** The first time the USB mode is changed, its tile appears in Quick Settings. */
    private void showTile() {
        final PackageManager pm = mContext.getPackageManager();
        final ComponentName tile = new ComponentName(mContext, UsbModeTileService.class);
        if (pm.getComponentEnabledSetting(tile)
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return;
        }
        pm.setComponentEnabledSetting(tile, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        final ContentResolver resolver = mContext.getContentResolver();
        final String spec = "custom(" + tile.flattenToShortString() + ")";
        String tiles = Settings.Secure.getString(resolver, Settings.Secure.QS_TILES);
        if (TextUtils.isEmpty(tiles)) {
            // Unset means the default tiles, which SystemUI expands "default" to.
            tiles = "default";
        }
        if (!Arrays.asList(tiles.split(",")).contains(spec)) {
            Settings.Secure.putString(resolver, Settings.Secure.QS_TILES, tiles + "," + spec);
        }
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(String path, String value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
