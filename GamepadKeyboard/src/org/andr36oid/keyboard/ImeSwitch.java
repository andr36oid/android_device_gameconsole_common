package org.andr36oid.keyboard;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Switching between this keyboard and the Android TV keyboard (LeanbackIME) by writing the
 * same secure settings the Settings app writes. The app is platform signed, which grants
 * WRITE_SECURE_SETTINGS.
 */
public final class ImeSwitch {
    public static final String SELF = "org.andr36oid.keyboard/.GamepadIme";
    public static final String LEANBACK_PKG = "com.android.inputmethod.leanback";
    public static final String LEANBACK =
            LEANBACK_PKG + "/.service.LeanbackImeService";

    private ImeSwitch() {}

    public static boolean leanbackInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(LEANBACK_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static String current(Context ctx) {
        return Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
    }

    public static boolean selfIsCurrent(Context ctx) {
        String cur = current(ctx);
        return cur == null || SELF.equals(cur)
                || "org.andr36oid.keyboard/org.andr36oid.keyboard.GamepadIme".equals(cur);
    }

    /** Adds the keyboard to the enabled list if it is missing (keeps its subtypes if not). */
    public static void ensureEnabled(Context ctx, String id) {
        ContentResolver cr = ctx.getContentResolver();
        String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS);
        if (list != null) {
            for (String entry : list.split(":")) {
                int semi = entry.indexOf(';');
                String entryId = semi >= 0 ? entry.substring(0, semi) : entry;
                if (entryId.equals(id)) return;
            }
        }
        String updated = TextUtils.isEmpty(list) ? id : list + ":" + id;
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS, updated);
    }

    /** Makes a keyboard the one that opens from now on. */
    public static void makeDefault(Context ctx, String id) {
        ensureEnabled(ctx, id);
        Settings.Secure.putString(ctx.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD, id);
    }
}
