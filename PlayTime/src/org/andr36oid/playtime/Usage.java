package org.andr36oid.playtime;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Play time from the usage events Android keeps anyway: an app counts from the moment one
 * of its activities is resumed until it is paused or the screen goes off. The home screen,
 * Settings and things without a launcher icon don't count.
 */
final class Usage {

    static final int DAYS = 7;

    /** Play time per day in ms, oldest first, today last. */
    final long[] dayMs = new long[DAYS];
    /** Local midnight at the start of each day, same order. */
    final long[] dayStart = new long[DAYS];
    /** Play time per app over the whole week in ms. */
    final Map<String, Long> appMs = new HashMap<>();
    long weekMs;

    private final Set<String> mSkipped = new HashSet<>();
    private final Map<String, Boolean> mCounted = new HashMap<>();
    private final PackageManager mPm;
    private long mWeekStart;
    private long mNow;

    private Usage(Context context) {
        mPm = context.getPackageManager();
        mSkipped.add(context.getPackageName());
        mSkipped.add("android");
        mSkipped.add("com.android.settings");
        mSkipped.add("com.android.systemui");
        final Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo info : mPm.queryIntentActivities(home, 0)) {
            mSkipped.add(info.activityInfo.packageName);
        }
    }

    /** Reads the last seven days. Takes a moment, don't call it on the main thread. */
    static Usage load(Context context) {
        final Usage usage = new Usage(context);
        usage.compute(context.getSystemService(UsageStatsManager.class));
        return usage;
    }

    /** The apps with any play time, most played first. */
    List<String> topApps() {
        final List<String> apps = new ArrayList<>(appMs.keySet());
        apps.sort((a, b) -> Long.compare(appMs.get(b), appMs.get(a)));
        return apps;
    }

    long todayMs() {
        return dayMs[DAYS - 1];
    }

    private void compute(UsageStatsManager usm) {
        mNow = System.currentTimeMillis();
        final Calendar day = Calendar.getInstance();
        day.setTimeInMillis(mNow);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        day.add(Calendar.DAY_OF_MONTH, -(DAYS - 1));
        for (int i = 0; i < DAYS; i++) {
            dayStart[i] = day.getTimeInMillis();
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
        mWeekStart = dayStart[0];

        // Start a day early to catch an app that was already open when the week began.
        final UsageEvents events = usm.queryEvents(mWeekStart - 24 * 3600 * 1000L, mNow);
        if (events == null) {
            return;
        }
        final UsageEvents.Event event = new UsageEvents.Event();
        String resumedPackage = null;
        String resumedClass = null;
        boolean screenOn = true;
        long since = 0;
        while (events.getNextEvent(event)) {
            final long time = event.getTimeStamp();
            switch (event.getEventType()) {
                case UsageEvents.Event.ACTIVITY_RESUMED:
                    count(resumedPackage, screenOn, since, time);
                    resumedPackage = event.getPackageName();
                    resumedClass = event.getClassName();
                    since = time;
                    break;
                case UsageEvents.Event.ACTIVITY_PAUSED:
                case UsageEvents.Event.ACTIVITY_STOPPED:
                    // Only the activity that is in front; when switching activities the next
                    // one may be resumed before the last one is paused.
                    if (event.getPackageName().equals(resumedPackage)
                            && (resumedClass == null
                                    || resumedClass.equals(event.getClassName()))) {
                        count(resumedPackage, screenOn, since, time);
                        resumedPackage = null;
                        since = time;
                    }
                    break;
                case UsageEvents.Event.SCREEN_INTERACTIVE:
                    count(resumedPackage, screenOn, since, time);
                    screenOn = true;
                    since = time;
                    break;
                case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
                    count(resumedPackage, screenOn, since, time);
                    screenOn = false;
                    since = time;
                    break;
                case UsageEvents.Event.DEVICE_SHUTDOWN:
                case UsageEvents.Event.DEVICE_STARTUP:
                    count(resumedPackage, screenOn, since, time);
                    resumedPackage = null;
                    screenOn = true;
                    since = time;
                    break;
                default:
                    break;
            }
        }
        count(resumedPackage, screenOn, since, mNow);
    }

    /** Adds [from, to) to the app, split over the days it covers. */
    private void count(String pkg, boolean screenOn, long from, long to) {
        if (pkg == null || !screenOn || !isCounted(pkg)) {
            return;
        }
        from = Math.max(from, mWeekStart);
        to = Math.min(to, mNow);
        if (to <= from) {
            return;
        }
        for (int i = 0; i < DAYS; i++) {
            final long end = i + 1 < DAYS ? dayStart[i + 1] : Long.MAX_VALUE;
            final long overlap = Math.min(to, end) - Math.max(from, dayStart[i]);
            if (overlap > 0) {
                dayMs[i] += overlap;
            }
        }
        appMs.merge(pkg, to - from, Long::sum);
        weekMs += to - from;
    }

    private boolean isCounted(String pkg) {
        if (mSkipped.contains(pkg)) {
            return false;
        }
        return mCounted.computeIfAbsent(pkg, p -> mPm.getLaunchIntentForPackage(p) != null
                || mPm.getLeanbackLaunchIntentForPackage(p) != null);
    }

    /** "45 min", "2 h", "1 h 12 min". Under a minute rounds up to 1 min once there's any. */
    static String format(Context context, long ms) {
        long minutes = ms / 60000;
        if (minutes == 0 && ms > 0) {
            minutes = 1;
        }
        final long hours = minutes / 60;
        minutes %= 60;
        if (hours == 0) {
            return context.getString(R.string.duration_min, (int) minutes);
        }
        if (minutes == 0) {
            return context.getString(R.string.duration_h, (int) hours);
        }
        return context.getString(R.string.duration_h_min, (int) hours, (int) minutes);
    }
}
