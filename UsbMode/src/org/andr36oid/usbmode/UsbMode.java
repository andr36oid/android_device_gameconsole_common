package org.andr36oid.usbmode;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
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
 */
final class UsbMode {

    private static final String TAG = "UsbMode";

    private static final String OTG_MODE =
            "/sys/devices/platform/ff2c0000.syscon/ff2c0000.syscon:usb2-phy@100/otg_mode";

    private static final String HOST = "host";
    private static final String DEVICE = "peripheral";

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
        try {
            write(OTG_MODE, device ? DEVICE : HOST);
        } catch (IOException e) {
            Log.e(TAG, "Couldn't switch the OTG port to " + (device ? DEVICE : HOST), e);
            return false;
        }
        showTile();
        return isDevice() == device;
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
