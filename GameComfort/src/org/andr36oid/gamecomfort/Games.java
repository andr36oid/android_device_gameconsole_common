package org.andr36oid.gamecomfort;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Whether an app is a game: it says so itself (category "game"), or it is one of the
 * emulators, which usually don't.
 */
final class Games {

    private final PackageManager mPm;
    private final Set<String> mEmulators;

    Games(Context context) {
        mPm = context.getPackageManager();
        mEmulators = new HashSet<>(Arrays.asList(
                context.getResources().getStringArray(R.array.emulator_packages)));
    }

    boolean isGame(String pkg) {
        if (pkg == null) {
            return false;
        }
        if (mEmulators.contains(pkg)) {
            return true;
        }
        try {
            final ApplicationInfo info = mPm.getApplicationInfo(pkg, 0);
            return info.category == ApplicationInfo.CATEGORY_GAME
                    || (info.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
