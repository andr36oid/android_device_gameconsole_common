package org.andr36oid.gamecomfort;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Settings;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** App allowlist rules driven by GameWatcher's task listener, never a timer. */
final class BrightnessRules {
    static final String KEY_ENABLED = "app_brightness_enabled";
    static final String KEY_PREFIX = "app_brightness.";
    private static final String GUEST = "sys.andr36oid.subsystem.";
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs, journal;
    private final int minimum, maximum;
    private final BrightnessState state;
    private boolean observing, invalidJournal;
    private long generation;
    private ExecutorService worker;
    private Future<?> pending;

    private final ContentObserver settings = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            invalidate();
            state.settingsChanged();
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (p, key) -> {
        if (key != null && (key.equals(KEY_ENABLED) || key.startsWith(KEY_PREFIX))) {
            handler.post(() -> {
                if (observing) foreground(topPackage());
            });
        }
    };

    BrightnessRules(Context context) {
        this.context = context.getApplicationContext();
        prefs = Comfort.prefs(this.context);
        journal = this.context.getSharedPreferences("app_brightness_state", Context.MODE_PRIVATE);
        PowerManager power = this.context.getSystemService(PowerManager.class);
        minimum = power == null ? 1 : Math.max(1, power.getMinimumScreenBrightnessSetting());
        maximum = power == null ? 255 : Math.max(minimum, power.getMaximumScreenBrightnessSetting());
        state = new BrightnessState(new BrightnessState.Device() {
            @Override public boolean manual() { return isManual(); }
            @Override public int read() { return readBrightness(); }
            @Override public boolean write(int value) {
                if (!inRange(value) || !isManual()) return false;
                try {
                    return Settings.System.putInt(BrightnessRules.this.context.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, value);
                } catch (RuntimeException e) {
                    return false;
                }
            }
        }, new BrightnessState.Store() {
            @Override public BrightnessState.Record load() {
                try {
                    if (!journal.getBoolean("present", false)) return null;
                    BrightnessState.Record saved = new BrightnessState.Record(
                            journal.getInt("original", -1), journal.getInt("applied", -1),
                            journal.getInt("pending", -1), journal.getBoolean("suppressed", false));
                    if (!inRange(saved.original) || !inRange(saved.applied)
                            || (saved.pending != -1 && !inRange(saved.pending))) {
                        invalidJournal = true;
                        return null;
                    }
                    return saved;
                } catch (RuntimeException e) {
                    invalidJournal = true;
                    return null;
                }
            }
            @Override public boolean save(BrightnessState.Record record) {
                try {
                    SharedPreferences.Editor edit = journal.edit().clear();
                    if (record != null) {
                        if (!inRange(record.original) || !inRange(record.applied)
                                || (record.pending != -1 && !inRange(record.pending))) return false;
                        edit.putBoolean("present", true).putInt("original", record.original)
                                .putInt("applied", record.applied).putInt("pending", record.pending)
                                .putBoolean("suppressed", record.suppressed);
                    }
                    boolean saved = edit.commit();
                    if (saved && record == null) invalidJournal = false;
                    return saved;
                } catch (RuntimeException e) {
                    return false;
                }
            }
        });
    }

    static boolean enabled(Context context) {
        try { return Comfort.prefs(context).getBoolean(KEY_ENABLED, false); }
        catch (RuntimeException e) { return false; }
    }

    static String key(String packageName) { return KEY_PREFIX + packageName; }

    static boolean excluded(Context context, String pkg) {
        return pkg == null || pkg.equals(context.getPackageName())
                || pkg.equals("org.andr36oid.subsystemsetup")
                || pkg.equals("org.andr36oid.subsystem");
    }

    /** Immediate phase: leaving the allowlist restores before the normal 300 ms
     * task settle; every event invalidates any earlier asynchronous dump result. */
    void taskChanged(String pkg) {
        startObserving();
        invalidate();
        if (!eligible(pkg)) state.release(true);
        else state.settingsChanged();
    }

    /** Settled/refresh phase, including service updates without a new task. */
    void foreground(String pkg) {
        startObserving();
        invalidate();
        if (!eligible(pkg)) {
            state.release(true);
            return;
        }
        state.settingsChanged();
        if (invalidJournal || state.suppressed() || !isManual()) {
            if (!isManual()) state.release(false);
            return;
        }
        final int percent = percent(pkg);
        final long requested = generation;
        pending = worker.submit(() -> {
            boolean allowed = WindowBrightness.permitsRules();
            handler.post(() -> {
                if (!observing || requested != generation) return;
                // A queued result must not write brightness for a task/settings
                // snapshot that has since changed, even before its callback runs.
                if (!pkg.equals(topPackage()) || !eligible(pkg) || percent(pkg) != percent) return;
                if (!allowed || !isManual()) {
                    state.release(false);
                    return;
                }
                int value = Math.round(maximum * percent / 100f);
                state.apply(Math.max(minimum, Math.min(maximum, value)));
            });
        });
    }

    void stop() {
        invalidate();
        if (observing) {
            observing = false;
            context.getContentResolver().unregisterContentObserver(settings);
            prefs.unregisterOnSharedPreferenceChangeListener(preferences);
        }
        state.release(true);
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    private void startObserving() {
        if (observing) return;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "AppBrightness");
            thread.setDaemon(true);
            return thread;
        });
        context.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, settings);
        context.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, settings);
        prefs.registerOnSharedPreferenceChangeListener(preferences);
        observing = true;
    }

    private void invalidate() {
        generation++;
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
    }

    private boolean eligible(String pkg) {
        return enabled(context) && !excluded(context, pkg) && percent(pkg) > 0 && !guestBusy();
    }

    private int percent(String pkg) {
        if (pkg == null) return -1;
        try {
            String value = prefs.getString(key(pkg), "off");
            switch (value) {
                case "10": return 10;
                case "25": return 25;
                case "50": return 50;
                case "75": return 75;
                case "100": return 100;
                default: return -1;
            }
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private boolean guestBusy() {
        String session = SystemProperties.get(GUEST + "session", "");
        return "1".equals(SystemProperties.get(GUEST + "boot_pending", ""))
                || "run".equals(SystemProperties.get(GUEST + "request", ""))
                || !(session.isEmpty() || "ended".equals(session));
    }

    private String topPackage() {
        try {
            List<ActivityManager.RunningTaskInfo> tasks = context.getSystemService(ActivityManager.class)
                    .getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return null;
            ComponentName top = tasks.get(0).topActivity;
            return top == null ? null : top.getPackageName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isManual() {
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private int readBrightness() {
        try {
            int value = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            return inRange(value) ? value : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private boolean inRange(int value) { return value >= minimum && value <= maximum; }
}
