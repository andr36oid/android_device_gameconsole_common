package org.andr36oid.gamecomfort;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.andr36oid.gamecomfort.resume.FileAccess;
import org.andr36oid.gamecomfort.resume.Finder;
import org.andr36oid.gamecomfort.resume.Ppsspp;
import org.andr36oid.gamecomfort.resume.RetroArch;
import org.andr36oid.gamecomfort.resume.Target;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Continue playing: remembers the last game app in front, finds out which game it ran
 * (RetroArch's history, PPSSPP's recent list) and starts that game again in one press.
 *
 * <p>A game that is still open is only brought back to the front, never restarted: starting
 * RetroArch or PPSSPP again with a game would end the running one without saving.
 */
final class Continue {

    private static final String TAG = "GameComfort";

    static final String KEY_ON_BOOT = "continue_on_boot";
    /** The game app last in front */
    private static final String KEY_LAST_PACKAGE = "continue_last_package";
    /** A game was in front when this was last written: at power-off, it was still open. */
    private static final String KEY_GAME_OPEN = "continue_game_open";
    /** The last target found, so the tile and the power menu can show it right away */
    private static final String KEY_TARGET = "continue_target";
    private static final String KEY_TILE_ADDED = "continue_tile_added";

    /** Read by SystemUI for the power menu's Continue button (frameworks/base). */
    static final String SETTING_LABEL = "andr36oid_continue_label";
    static final String SETTING_PACKAGE = "andr36oid_continue_package";

    static final String ACTION_CONTINUE = "org.andr36oid.gamecomfort.action.CONTINUE";

    /** One at a time: the root helper takes one request at a time anyway */
    static final Executor BACKGROUND = Executors.newSingleThreadExecutor();

    private Continue() {
    }

    private static SharedPreferences prefs(Context context) {
        return Comfort.prefs(context);
    }

    static boolean isOnBoot(Context context) {
        return prefs(context).getBoolean(KEY_ON_BOOT, false);
    }

    /** The target found last time, null for none. */
    static Target cached(Context context) {
        return Target.parse(prefs(context).getString(KEY_TARGET, null));
    }

    static boolean wasGameOpen(Context context) {
        return prefs(context).getBoolean(KEY_GAME_OPEN, false);
    }

    /**
     * The app in front changed (GameWatcher). Written with commit(), so it is on disk even if
     * the battery runs out a moment later.
     */
    static void onForeground(Context context, String pkg, boolean isGame) {
        final SharedPreferences prefs = prefs(context);
        if (isGame) {
            if (!pkg.equals(prefs.getString(KEY_LAST_PACKAGE, null))
                    || !prefs.getBoolean(KEY_GAME_OPEN, false)) {
                prefs.edit().putString(KEY_LAST_PACKAGE, pkg).putBoolean(KEY_GAME_OPEN, true)
                        .commit();
            }
        } else if (prefs.getBoolean(KEY_GAME_OPEN, false)) {
            prefs.edit().putBoolean(KEY_GAME_OPEN, false).commit();
        }
    }

    /**
     * Finds the game to continue now and remembers it for the tile and the power menu. Reads
     * the emulators' files through the root helper, so call off the main thread.
     */
    static Target refresh(Context context) {
        return refresh(context, new Broker());
    }

    /** {@link #refresh} in the background. */
    static void refreshSoon(Context context) {
        final Context app = context.getApplicationContext();
        BACKGROUND.execute(() -> refresh(app));
    }

    private static Target refresh(Context context, FileAccess files) {
        final String last = prefs(context).getString(KEY_LAST_PACKAGE, null);
        final String label = last != null ? appLabel(context, last) : null;
        Finder.RaGame ra = null;
        Finder.PpGame pp = null;
        if (last == null || last.equals(RetroArch.PACKAGE)) {
            ra = installed(context, RetroArch.PACKAGE) ? Finder.retroArch(files) : null;
        }
        if (last == null || last.equals(Ppsspp.PACKAGE)) {
            pp = installed(context, Ppsspp.PACKAGE) ? Finder.ppsspp(files) : null;
        }
        final Target target = Target.choose(last, label, ra, pp);
        publish(context, target);
        return target;
    }

    /** Remembers the target and tells the tile and the power menu. */
    private static void publish(Context context, Target target) {
        final Target old = cached(context);
        if (target == null ? old == null : target.equals(old)) {
            return;
        }
        prefs(context).edit().putString(KEY_TARGET, target != null ? target.serialize() : "")
                .apply();
        final ContentResolver resolver = context.getContentResolver();
        Settings.Global.putString(resolver, SETTING_LABEL, target != null ? target.label : "");
        Settings.Global.putString(resolver, SETTING_PACKAGE, target != null ? target.pkg : "");
        TileService.requestListeningState(context,
                new ComponentName(context, ContinueTileService.class));
    }

    /** What {@link #start} did, for the user. */
    static final class Result {
        final Target target;
        /** R.string message to show, 0 for none */
        final int message;
        final Finder.StateHint hint;
        /** Started again rather than brought to the front */
        final boolean started;

        Result(Target target, int message, Finder.StateHint hint, boolean started) {
            this.target = target;
            this.message = message;
            this.hint = hint;
            this.started = started;
        }
    }

    /** Tells the user what happened, if there is anything to tell. Main thread. */
    static void show(Context context, Result r) {
        final String text;
        if (r.message != 0) {
            text = r.target != null ? context.getString(r.message, r.target.label)
                    : context.getString(r.message);
        } else if (r.hint != null) {
            text = context.getString(R.string.continue_state_hint, r.target.label,
                    slotName(context, r.hint.slot));
        } else if (r.started) {
            text = context.getString(R.string.continue_starting, r.target.label);
        } else {
            return;
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }

    private static String slotName(Context context, int slot) {
        return slot < 0 ? context.getString(R.string.continue_slot_auto)
                : context.getString(R.string.continue_slot, slot);
    }

    /**
     * Brings the last game back: to the front if it is still open, else started again with
     * the same game file (and core). Call off the main thread.
     */
    static Result start(Context context) {
        final Broker files = new Broker();
        final Target target = refresh(context, files);
        if (target == null) {
            return new Result(null, R.string.continue_nothing, null, false);
        }
        final int task = findTask(context, target);
        if (task >= 0) {
            context.getSystemService(ActivityManager.class).moveTaskToFront(task, 0);
            return new Result(target, 0, null, false);
        }
        final Intent intent;
        Finder.StateHint hint = null;
        switch (target.kind) {
            case Target.RETROARCH:
                if (Boolean.FALSE.equals(Finder.exists(files, target.game))) {
                    return new Result(target, R.string.continue_game_missing, null, false);
                }
                if (Boolean.FALSE.equals(Finder.exists(files, target.core))) {
                    return new Result(target, R.string.continue_core_missing, null, false);
                }
                intent = retroArchIntent(context, target);
                final Finder.RaGame ra = Finder.retroArch(files);
                if (ra != null && ra.entry.path.equals(target.game)) {
                    hint = Finder.stateHint(files, ra);
                }
                break;
            case Target.PPSSPP:
                if (Boolean.FALSE.equals(Finder.exists(files, target.game))) {
                    return new Result(target, R.string.continue_game_missing, null, false);
                }
                intent = new Intent(Intent.ACTION_MAIN)
                        .setClassName(Ppsspp.PACKAGE, Ppsspp.GAME_ACTIVITY)
                        .putExtra(Ppsspp.EXTRA_GAME, target.game);
                break;
            default:
                intent = context.getPackageManager().getLaunchIntentForPackage(target.pkg);
                if (intent == null) {
                    return new Result(target, R.string.continue_nothing, null, false);
                }
                break;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "Can't start " + intent, e);
            return new Result(target, R.string.continue_failed, null, false);
        }
        return new Result(target, 0, hint, true);
    }

    /**
     * RetroArch's game activity with the extras its own launcher passes
     * (MainMenuActivity.startRetroActivity in RetroArch 1.22), so paths and folders come out
     * the same as when RetroArch starts itself.
     */
    private static Intent retroArchIntent(Context context, Target target) {
        final Intent intent = new Intent(Intent.ACTION_MAIN)
                .setClassName(RetroArch.PACKAGE, RetroArch.GAME_ACTIVITY)
                .putExtra("ROM", target.game)
                .putExtra("LIBRETRO", target.core)
                .putExtra("CONFIGFILE", !TextUtils.isEmpty(target.config) ? target.config
                        : RetroArch.FILES + "/retroarch.cfg")
                .putExtra("SDCARD", "/storage/emulated/0")
                .putExtra("EXTERNAL", RetroArch.FILES);
        final String ime = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null) {
            intent.putExtra("IME", ime);
        }
        try {
            final ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(RetroArch.PACKAGE, 0);
            intent.putExtra("DATADIR", info.dataDir).putExtra("APK", info.sourceDir);
        } catch (PackageManager.NameNotFoundException e) {
            // startActivity fails on its own
        }
        return intent;
    }

    /** The task that still holds the game, -1 for none. */
    private static int findTask(Context context, Target target) {
        final List<ActivityManager.RunningTaskInfo> tasks =
                context.getSystemService(ActivityManager.class).getRunningTasks(50);
        if (tasks == null) {
            return -1;
        }
        final String gameActivity = target.kind.equals(Target.RETROARCH) ? RetroArch.GAME_ACTIVITY
                : target.kind.equals(Target.PPSSPP) ? Ppsspp.GAME_ACTIVITY : null;
        for (ActivityManager.RunningTaskInfo task : tasks) {
            for (ComponentName c : Arrays.asList(task.topActivity, task.baseActivity)) {
                if (c == null || !c.getPackageName().equals(target.pkg)) {
                    continue;
                }
                // RetroArch's menu-only screen is a task of its own; it doesn't hold a game
                if (gameActivity == null || c.getClassName().equals(gameActivity)) {
                    return task.taskId;
                }
            }
        }
        return -1;
    }

    /** The app in front, null if unknown. */
    static String foregroundPackage(Context context) {
        final List<ActivityManager.RunningTaskInfo> tasks =
                context.getSystemService(ActivityManager.class).getRunningTasks(1);
        if (tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) {
            return null;
        }
        return tasks.get(0).topActivity.getPackageName();
    }

    /** Whether the home screen (Daijishou) is in front. */
    static boolean isHomeInFront(Context context) {
        final String top = foregroundPackage(context);
        final Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        final android.content.pm.ResolveInfo info = context.getPackageManager()
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return top != null && info != null && info.activityInfo != null
                && top.equals(info.activityInfo.packageName);
    }

    private static String appLabel(Context context, String pkg) {
        final PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static boolean installed(Context context, String pkg) {
        return appLabel(context, pkg) != null;
    }

    /** Puts the tile into Quick Settings once, the user may remove it afterwards. */
    static void addTileOnce(Context context) {
        final SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(KEY_TILE_ADDED, false)) {
            return;
        }
        GameMode.addTile(context, new ComponentName(context, ContinueTileService.class));
        prefs.edit().putBoolean(KEY_TILE_ADDED, true).apply();
    }
}
