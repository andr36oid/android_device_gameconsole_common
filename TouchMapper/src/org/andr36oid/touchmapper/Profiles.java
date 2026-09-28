package org.andr36oid.touchmapper;

import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the profiles live and how the joyMouse daemon hears about them. One JSON file per app in
 * {@link #DIR}; the daemon (root) reads the file of the app named in {@link #PROP_PROFILE} and
 * reads it again whenever {@link #PROP_SERIAL} changes.
 */
final class Profiles {

    private static final String TAG = "TouchControls";

    static final File DIR = new File("/data/system/andr36oid/touchmap");
    private static final String SUFFIX = ".json";

    /** Package whose profile the daemon should use now, empty for none. */
    static final String PROP_PROFILE = "sys.touchmap.profile";
    /** Changes on every save, so the daemon reads the file again. */
    static final String PROP_SERIAL = "sys.touchmap.serial";
    /** "WxH@R": natural display size and current rotation. */
    static final String PROP_DISPLAY = "sys.touchmap.display";

    private Profiles() {
    }

    static File file(String packageName) {
        return new File(DIR, packageName + SUFFIX);
    }

    /** Packages that have a profile, sorted. */
    static List<String> packages() {
        final List<String> out = new ArrayList<>();
        final String[] names = DIR.list();
        if (names != null) {
            for (String name : names) {
                if (name.endsWith(SUFFIX) && name.length() > SUFFIX.length()) {
                    out.add(name.substring(0, name.length() - SUFFIX.length()));
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    static boolean exists(String packageName) {
        return file(packageName).exists();
    }

    /** The saved profile, or null if there is none or it can't be read. */
    static Profile load(String packageName) {
        final AtomicFile file = new AtomicFile(file(packageName));
        if (!file.exists()) {
            return null;
        }
        try {
            // AtomicFile.readFully: InputStream.readAllBytes only came with Android 13
            final byte[] data = file.readFully();
            return Profile.fromJson(packageName, new String(data, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Couldn't read the profile of " + packageName, e);
            return null;
        }
    }

    /** Loads the profile, or makes a new, empty one. */
    static Profile loadOrCreate(String packageName) {
        final Profile p = load(packageName);
        return p != null ? p : new Profile(packageName);
    }

    static boolean save(Context context, Profile profile) {
        if (!DIR.isDirectory() && !DIR.mkdirs()) {
            Log.e(TAG, "Couldn't make " + DIR);
            return false;
        }
        final AtomicFile file = new AtomicFile(file(profile.packageName));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(profile.toJson().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Couldn't save the profile of " + profile.packageName, e);
            if (out != null) {
                file.failWrite(out);
            }
            return false;
        }
        changed(context);
        return true;
    }

    static void delete(Context context, String packageName) {
        new AtomicFile(file(packageName)).delete();
        changed(context);
    }

    /** Tells the daemon to read the files again and the watcher to look again. */
    private static void changed(Context context) {
        setProperty(PROP_SERIAL, Long.toString(System.currentTimeMillis()));
        WatcherService.refresh(context);
    }

    static void setActive(String packageName) {
        final String value = packageName == null ? "" : packageName;
        if (!value.equals(SystemProperties.get(PROP_PROFILE, ""))) {
            setProperty(PROP_PROFILE, value);
        }
    }

    static void setProperty(String name, String value) {
        try {
            SystemProperties.set(name, value);
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't set " + name, e);
        }
    }

    /** Whether any profile is switched on, so the watcher has something to do. */
    static boolean anyEnabled() {
        for (String pkg : packages()) {
            final Profile p = load(pkg);
            if (p != null && p.enabled && !p.controls.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static Intent editIntent(Context context, String packageName) {
        return new Intent(context, EditorActivity.class)
                .putExtra(EditorActivity.EXTRA_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }
}
