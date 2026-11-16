package org.andr36oid.cpuoverclock;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Temporary foreground clock ownership. Uses only stock min/max controls;
 * user per-app choices and manual profile changes take precedence. */
final class GameTurbo {
    static final String ENABLED = "andr36oid_game_turbo";
    static final String AFFINITY = "andr36oid_game_affinity";
    static final String FOREGROUND = "foreground_game";
    static final String PACKAGE = "game_package";
    static final int NOT_HANDLED = -1, OK = 1, FAILED = 0;
    private static final String PREFS = "game_turbo_session";
    private static final String AFFINITY_PROP = "sys.andr36oid.gameaffinity.package";
    private GameTurbo() { }

    static boolean enabled(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), ENABLED, 1) != 0;
    }

    static synchronized void foreground(Context c, String pkg) {
        SharedPreferences p = prefs(c);
        String previousBoot = p.getString("owner_boot", "");
        if (!previousBoot.isEmpty() && !previousBoot.equals(boot())) p.edit().clear().commit();
        String before = p.getString("pkg", "");
        String requested = pkg == null ? "" : pkg;
        SharedPreferences.Editor edit = p.edit().putString("pkg", requested);
        int enabled = enabled(c) ? 1 : 0;
        if (!before.equals(requested) || p.getInt("enabled", -1) != enabled) edit.remove("veto");
        edit.putInt("enabled", enabled);
        if (!edit.commit()) return;
        // Persist the recovery receiver even when the normal profile is Balanced.
        BootStart.set(c, true);
        boolean wasActive = p.getBoolean("active", false);
        int result = intercept(c);
        if (result == NOT_HANDLED && (wasActive || AppProfiles.hasExplicitProfile(c, requested))) {
            new PerformanceProfiles(c).apply();
        }
        affinity(c, requested);
        PerformanceProfiles.updateTile(c);
    }

    /** Called before every normal profile write, including AppProfileService. */
    static synchronized int intercept(Context c) {
        SharedPreferences p = prefs(c);
        String boot = boot();
        String oldBoot = p.getString("owner_boot", "");
        if (!oldBoot.isEmpty() && !oldBoot.equals(boot)) p.edit().clear().commit();
        StockClockSession session = session(c, boot);
        String pkg = p.getString("pkg", "");
        String top = top(c);
        boolean eligible = enabled(c) && !pkg.isEmpty() && pkg.equals(top)
                && !guestBusy() && !AppProfiles.hasExplicitProfile(c, pkg)
                && !pkg.equals(p.getString("veto", ""));
        if (eligible) {
            if (!session.active()) {
                if (!p.edit().putString("stamp", stamp(c)).putString("owner_boot", boot)
                        .remove("restored").commit()) return FAILED;
            }
            if (session.start()) return OK;
            // Failed/foreign readback is not a reason to repeatedly fight another writer.
            session.stop();
            p.edit().putString("veto", pkg).commit();
            Log.w("GameTurbo", "Stock clock admission unavailable; leaving normal ownership");
            return FAILED;
        }
        if (session.active()) {
            if (!session.stop()) return FAILED;
            p.edit().putBoolean("restored", true).commit();
        }
        // An app-profile callback queued before exit must not overwrite an exact
        // min/max snapshot with a synthesized version of the same global profile.
        if (enabled(c) && p.getBoolean("restored", false) && stamp(c).equals(p.getString("stamp", ""))
                && !AppProfiles.hasEffectiveAppProfile(c, top)) return OK;
        p.edit().remove("restored").commit();
        return NOT_HANDLED;
    }

    /** A deliberate tile/settings command owns the new state until this game is left. */
    static synchronized boolean userChange(Context c) {
        SharedPreferences p = prefs(c);
        StockClockSession session = session(c, boot());
        if (!session.stop()) return false;
        return p.edit().putString("veto", p.getString("pkg", "")).remove("restored").commit();
    }

    static void affinity(Context c, String pkg) {
        boolean on = Settings.Global.getInt(c.getContentResolver(), AFFINITY, 0) != 0;
        String requested = on && !guestBusy() && pkg.equals(top(c))
                && (pkg.equals("com.retroarch") || pkg.equals("org.ppsspp.ppsspp")) ? pkg : "";
        try {
            SystemProperties.set(AFFINITY_PROP, requested);
            SystemProperties.set("ctl.start", "andr36oid-game-affinity");
        } catch (RuntimeException e) { Log.w("GameTurbo", "Affinity helper unavailable", e); }
    }

    private static StockClockSession session(Context c, String boot) {
        SharedPreferences p = prefs(c);
        return new StockClockSession(new StockClockSession.Io() {
            public String read(String path) throws IOException {
                return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
            }
            public void write(String path, String value) throws IOException {
                Files.write(Paths.get(path), value.getBytes(StandardCharsets.UTF_8));
            }
        }, new StockClockSession.Journal() {
            public StockClockSession.Snapshot load() {
                if (!p.getBoolean("active", false)) return null;
                long[] min = new long[3], max = new long[3], target = new long[3];
                String[] governor = new String[3];
                for (int i = 0; i < 3; i++) {
                    min[i] = p.getLong("min" + i, -1); max[i] = p.getLong("max" + i, -1);
                    target[i] = p.getLong("target" + i, -1); governor[i] = p.getString("gov" + i, "");
                }
                return new StockClockSession.Snapshot(p.getString("owner_boot", ""), min, max, target, governor);
            }
            public boolean save(StockClockSession.Snapshot snapshot) {
                SharedPreferences.Editor e = p.edit().putBoolean("active", snapshot != null);
                if (snapshot != null) {
                    e.putString("owner_boot", snapshot.boot);
                    for (int i = 0; i < 3; i++) e.putLong("min" + i, snapshot.min[i])
                            .putLong("max" + i, snapshot.max[i]).putLong("target" + i, snapshot.target[i])
                            .putString("gov" + i, snapshot.governor[i]);
                }
                return e.commit();
            }
        }, boot);
    }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static String stamp(Context c) {
        SharedPreferences p = c.getSharedPreferences("profiles", Context.MODE_PRIVATE);
        return p.getInt("profile", PerformanceProfiles.BALANCED) + ":" + p.getInt("cpu_cap_khz", 0)
                + ":" + ProfileCommandReceiver.isMemoryHeld(c);
    }
    private static String boot() {
        try { return new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")), StandardCharsets.UTF_8).trim(); }
        catch (IOException e) { return "unknown"; }
    }
    private static boolean guestBusy() {
        String p = "sys.andr36oid.subsystem.";
        String state = SystemProperties.get(p + "session", "");
        return "1".equals(SystemProperties.get(p + "boot_pending", ""))
                || "run".equals(SystemProperties.get(p + "request", ""))
                || !(state.isEmpty() || state.equals("ended"));
    }
    private static String top(Context c) {
        try {
            List<ActivityManager.RunningTaskInfo> tasks = c.getSystemService(ActivityManager.class).getRunningTasks(1);
            return tasks.isEmpty() || tasks.get(0).topActivity == null ? "" : tasks.get(0).topActivity.getPackageName();
        } catch (RuntimeException e) { return ""; }
    }
}
