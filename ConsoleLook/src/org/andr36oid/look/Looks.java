package org.andr36oid.look;

import android.app.WallpaperManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads and applies the four looks. Everything here is optional and off by default. */
final class Looks {

    private static final String TAG = "ConsoleLook";

    // ---- accent colour: same setting Styles & wallpapers writes, applied by SystemUI
    static final String ACCENT_CATEGORY = "android.theme.customization.accent_color";
    private static final String OUR_ACCENTS = "org.andr36oid.overlay.accent.";

    // ---- wallpapers: the partner package from themes/wallpapers
    static final String WALLPAPER_PACKAGE = "org.andr36oid.wallpapers";
    private static final String PREF_WALLPAPER = "wallpaper";
    private static final String PREF_WALLPAPER_ID = "wallpaper_id";

    // ---- UI sounds: runtime overlay for the framework's audio_assets.xml
    static final String RETRO_SOUNDS = "org.andr36oid.overlay.sounds.retro";
    private static final String RETRO_CHARGING = "/product/media/audio/ui/andr36oid/charging.ogg";
    private static final String STOCK_CHARGING = "/product/media/audio/ui/ChargingStarted.ogg";
    private static final String PREF_OLD_CHARGING = "old_charging_sound";

    // ---- boot animation: themes/bootanimation/andr36oid-bootanim.rc
    static final String BOOTANIM_PROP = "persist.andr36oid.bootanimation";
    static final String BOOTANIM_RETRO = "retro";
    static final String BOOTANIM_LINEAGE = "lineage";

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

    // ------------------------------------------------------------------ wallpaper

    static Resources wallpaperResources(Context context) {
        try {
            return context.getPackageManager().getResourcesForApplication(WALLPAPER_PACKAGE);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Names (drawable resource names) of the bundled wallpapers. */
    static String[] wallpaperNames(Resources res) {
        final int id = res.getIdentifier("wallpapers", "array", WALLPAPER_PACKAGE);
        return id == 0 ? new String[0] : res.getStringArray(id);
    }

    static String[] wallpaperTitles(Resources res, String[] names) {
        final int id = res.getIdentifier("wallpaper_titles", "array", WALLPAPER_PACKAGE);
        final String[] titles = id == 0 ? new String[0] : res.getStringArray(id);
        final String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = i < titles.length ? titles[i] : names[i];
        }
        return out;
    }

    /**
     * The bundled wallpaper that is set, "" for the default, or null when something else
     * set a wallpaper since (another app or Styles & wallpapers).
     */
    static String currentWallpaper(Context context) {
        final WallpaperManager wm = context.getSystemService(WallpaperManager.class);
        final int id = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        final android.content.SharedPreferences prefs = prefs(context);
        final String name = prefs.getString(PREF_WALLPAPER, "");
        if (id <= 0) return "";                         // never set: the black default
        if (id == prefs.getInt(PREF_WALLPAPER_ID, -1)) return name;
        return null;
    }

    static boolean setWallpaper(Context context, String name) {
        final WallpaperManager wm = context.getSystemService(WallpaperManager.class);
        final int which = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
        try {
            if (TextUtils.isEmpty(name)) {
                wm.clear(which);
            } else {
                final Resources res = wallpaperResources(context);
                if (res == null) return false;
                final int id = res.getIdentifier(name, "drawable", WALLPAPER_PACKAGE);
                if (id == 0) return false;
                try (InputStream in = res.openRawResource(id)) {
                    wm.setStream(in, null, true, which);
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "wallpaper " + name, e);
            return false;
        }
        prefs(context).edit()
                .putString(PREF_WALLPAPER, name)
                .putInt(PREF_WALLPAPER_ID, wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM))
                .apply();
        return true;
    }

    // ------------------------------------------------------------------ sounds

    static boolean retroSoundsOn(Context context) {
        final OverlayManager om = context.getSystemService(OverlayManager.class);
        final OverlayInfo info = om.getOverlayInfo(RETRO_SOUNDS, Process.myUserHandle());
        return info != null && info.isEnabled();
    }

    static boolean retroSoundsInstalled(Context context) {
        final OverlayManager om = context.getSystemService(OverlayManager.class);
        return om.getOverlayInfo(RETRO_SOUNDS, Process.myUserHandle()) != null;
    }

    /**
     * Switches the sound set. The d-pad/click/keyboard sounds need a restart (the audio
     * service reads its list once); the charger sound is a setting and changes at once.
     */
    static void setRetroSounds(Context context, boolean on) {
        final OverlayManager om = context.getSystemService(OverlayManager.class);
        om.setEnabled(RETRO_SOUNDS, on, Process.myUserHandle());
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
                context.getSystemService(AudioManager.class).loadSoundEffects();
            }
        } else {
            final String old = prefs.getString(PREF_OLD_CHARGING, null);
            Settings.Global.putString(cr, Settings.Global.CHARGING_STARTED_SOUND,
                    TextUtils.isEmpty(old) ? STOCK_CHARGING : old);
        }
    }

    // ------------------------------------------------------------------ boot animation

    static boolean retroBootAnimation() {
        return BOOTANIM_RETRO.equals(SystemProperties.get(BOOTANIM_PROP, ""));
    }

    static void setRetroBootAnimation(boolean on) {
        SystemProperties.set(BOOTANIM_PROP, on ? BOOTANIM_RETRO : BOOTANIM_LINEAGE);
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("look", Context.MODE_PRIVATE);
    }
}
