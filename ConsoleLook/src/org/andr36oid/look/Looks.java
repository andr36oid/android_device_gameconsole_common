package org.andr36oid.look;

import android.content.ContentResolver;
import android.content.Context;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads and applies the three looks. Everything here is optional and off by default. */
final class Looks {

    private static final String TAG = "ConsoleLook";

    // ---- accent colour: same setting Styles & wallpapers writes, applied by SystemUI
    static final String ACCENT_CATEGORY = "android.theme.customization.accent_color";
    private static final String OUR_ACCENTS = "org.andr36oid.overlay.accent.";

    // ---- UI sounds: the audio service reads its sound list from
    // /product/etc/andr36oid/audio_assets_retro.xml while this is "retro"
    static final String SOUNDS_PROP = "persist.andr36oid.ui_sounds";
    private static final String RETRO_ASSETS = "/product/etc/andr36oid/audio_assets_retro.xml";
    private static final String RETRO_CHARGING = "/product/media/audio/ui/andr36oid/charging.ogg";
    private static final String STOCK_CHARGING = "/product/media/audio/ui/ChargingStarted.ogg";
    private static final String PREF_OLD_CHARGING = "old_charging_sound";

    // ---- boot animation: themes/bootanimation/andr36oid-bootanim.rc
    static final String BOOTANIM_PROP = "persist.andr36oid.bootanimation";
    static final String BOOTANIM_RETRO = "retro";
    static final String BOOTANIM_LINEAGE = "lineage";
    static final String BOOTANIM_ORBIT = "orbit";
    static final String BOOTANIM_SIGNAL = "signal";
    static final String BOOTANIM_CARTRIDGE = "cartridge";
    static final String BOOTANIM_XP = "xp";

    // ---- boot progress bar and boot details: andr36oid-bootanim.rc keeps them on /cache
    // for the boot animation, the system server sets the phases it follows while one is on
    static final String BOOT_PROGRESS_PROP = "persist.andr36oid.bootprogress";
    static final String BOOT_DETAILS_PROP = "persist.andr36oid.bootdetails";

    private Looks() {}

    /** An installed accent overlay. */
    static final class Accent implements Comparable<Accent> {
        final String pkg;
        final String label;

        Accent(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }

        boolean ours() {
            return pkg.startsWith(OUR_ACCENTS);
        }

        @Override
        public int compareTo(Accent o) {
            // The R36S shell colours first, then LineageOS and AOSP ones
            if (ours() != o.ours()) return ours() ? -1 : 1;
            return label.compareToIgnoreCase(o.label);
        }
    }

    static List<Accent> accents(Context context) {
        final List<Accent> out = new ArrayList<>();
        final OverlayManager om = context.getSystemService(OverlayManager.class);
        final PackageManager pm = context.getPackageManager();
        for (OverlayInfo info : om.getOverlayInfosForTarget("android", Process.myUserHandle())) {
            if (!ACCENT_CATEGORY.equals(info.getCategory())) continue;
            String label = info.getPackageName();
            try {
                label = pm.getApplicationInfo(label, 0).loadLabel(pm).toString();
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            out.add(new Accent(info.getPackageName(), label));
        }
        Collections.sort(out);
        return out;
    }

    /** The chosen accent overlay package, or "" for the default. */
    static String currentAccent(Context context) {
        try {
            return new JSONObject(themeJson(context.getContentResolver()))
                    .optString(ACCENT_CATEGORY, "");
        } catch (JSONException e) {
            return "";
        }
    }

    /** Stores the accent the way ThemePicker does; SystemUI enables the overlay. */
    static void setAccent(Context context, String pkg) {
        final ContentResolver cr = context.getContentResolver();
        JSONObject json;
        try {
            json = new JSONObject(themeJson(cr));
        } catch (JSONException e) {
            json = new JSONObject();
        }
        try {
            if (TextUtils.isEmpty(pkg)) {
                json.remove(ACCENT_CATEGORY);
            } else {
                json.put(ACCENT_CATEGORY, pkg);
            }
        } catch (JSONException e) {
            Log.w(TAG, "accent", e);
            return;
        }
        Settings.Secure.putString(cr, Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                json.toString());
    }

    private static String themeJson(ContentResolver cr) {
        final String s = Settings.Secure.getString(cr,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES);
        return TextUtils.isEmpty(s) ? "{}" : s;
    }

    // ------------------------------------------------------------------ sounds

    static boolean retroSoundsOn() {
        return "retro".equals(SystemProperties.get(SOUNDS_PROP, ""));
    }

    static boolean retroSoundsInstalled() {
        return new java.io.File(RETRO_ASSETS).isFile();
    }

    /**
     * Switches the sound set. The audio service reads its sound list again when it
     * reloads the sounds, so the new set plays at once; so does the charger sound.
     */
    static void setRetroSounds(Context context, boolean on) {
        SystemProperties.set(SOUNDS_PROP, on ? "retro" : "android");
        final AudioManager audio = context.getSystemService(AudioManager.class);
        audio.unloadSoundEffects();
        audio.loadSoundEffects();
        final ContentResolver cr = context.getContentResolver();
        final android.content.SharedPreferences prefs = prefs(context);
        if (on) {
            final String old = Settings.Global.getString(cr, Settings.Global.CHARGING_STARTED_SOUND);
            if (!RETRO_CHARGING.equals(old)) {
                prefs.edit().putString(PREF_OLD_CHARGING, old).apply();
            }
            Settings.Global.putString(cr, Settings.Global.CHARGING_STARTED_SOUND, RETRO_CHARGING);
            // Asking for retro sounds means wanting to hear them
            if (Settings.System.getInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) {
                Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 1);
            }
        } else {
            final String old = prefs.getString(PREF_OLD_CHARGING, null);
            Settings.Global.putString(cr, Settings.Global.CHARGING_STARTED_SOUND,
                    TextUtils.isEmpty(old) ? STOCK_CHARGING : old);
        }
    }

    // ------------------------------------------------------------------ boot animation

    static String bootAnimation() {
        final String value = SystemProperties.get(BOOTANIM_PROP, BOOTANIM_LINEAGE);
        switch (value) {
            case BOOTANIM_RETRO:
            case BOOTANIM_ORBIT:
            case BOOTANIM_SIGNAL:
            case BOOTANIM_CARTRIDGE:
            case BOOTANIM_XP:
                return value;
            default:
                return BOOTANIM_LINEAGE;
        }
    }

    static boolean bootFlag(String prop) {
        return SystemProperties.getBoolean(prop, false);
    }

    static void setBootFlag(String prop, boolean on) {
        SystemProperties.set(prop, on ? "1" : "0");
    }

    static void setBootAnimation(String value) {
        SystemProperties.set(BOOTANIM_PROP, value);
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("look", Context.MODE_PRIVATE);
    }
}
