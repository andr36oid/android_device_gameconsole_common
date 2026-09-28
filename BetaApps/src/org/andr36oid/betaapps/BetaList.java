package org.andr36oid.betaapps;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The apps in /system/etc/gameconsole/beta/beta.list, one per line:
 * {@code package versionCode file [needs=package] description...}. Also remembers which
 * versions the tester was already asked about.
 */
final class BetaList {

    static final String TAG = "BetaApps";
    static final File DIR = new File("/system/etc/gameconsole/beta");
    private static final File LIST = new File(DIR, "beta.list");

    private static final String PREFS = "offered";

    static final class Entry {
        final String packageName;
        final long versionCode;
        final File apk;
        /** Package of another app in the list this one is useless without (a plugin's app) */
        final String needs;
        final String description;

        Entry(String packageName, long versionCode, File apk, String needs,
                String description) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.apk = apk;
            this.needs = needs;
            this.description = description;
        }
    }

    private BetaList() {
    }

    static List<Entry> read() {
        final List<Entry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(LIST))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final String[] fields = line.split("\\s+", 4);
                if (fields.length < 3) {
                    Log.w(TAG, "Skipping bad line: " + line);
                    continue;
                }
                final long versionCode;
                try {
                    versionCode = Long.parseLong(fields[1]);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Skipping line with a bad versionCode: " + line);
                    continue;
                }
                String rest = fields.length > 3 ? fields[3] : "";
                String needs = null;
                if (rest.startsWith("needs=")) {
                    final String[] split = rest.split("\\s+", 2);
                    needs = split[0].substring("needs=".length());
                    rest = split.length > 1 ? split[1] : "";
                }
                entries.add(new Entry(fields[0], versionCode, new File(DIR, fields[2]), needs,
                        rest));
            }
        } catch (IOException e) {
            // No list: a release build, or the module is missing
            Log.i(TAG, "No beta list: " + e.getMessage());
        }
        return entries;
    }

    /** The installed versionCode of a package, or -1. */
    static long installedVersion(Context context, String packageName) {
        try {
            final PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /** Whether the list has an app this build could install that the tester wasn't asked about. */
    static boolean hasUnasked(Context context) {
        final SharedPreferences prefs = prefs(context);
        for (Entry entry : read()) {
            if (entry.apk.isFile()
                    && installedVersion(context, entry.packageName) < entry.versionCode
                    && prefs.getLong(entry.packageName, -1) < entry.versionCode) {
                return true;
            }
        }
        return false;
    }

    /** Remembers that the tester has seen these versions, so they aren't asked again. */
    static void markAsked(Context context, List<Entry> entries) {
        final SharedPreferences prefs = prefs(context);
        final SharedPreferences.Editor editor = prefs.edit();
        for (Entry entry : entries) {
            if (prefs.getLong(entry.packageName, -1) < entry.versionCode) {
                editor.putLong(entry.packageName, entry.versionCode);
            }
        }
        editor.apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
