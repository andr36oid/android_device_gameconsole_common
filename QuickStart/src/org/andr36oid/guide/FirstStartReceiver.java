package org.andr36oid.guide;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Messages from the first-start launcher (org.andr36oid.firststart). Only apps holding the
 * signature permission org.andr36oid.guide.permission.FIRST_START can send them, see the
 * manifest.
 *
 * <ul>
 * <li>FIRST_START_HOME: the launcher came to the front. That's the end of the setup wizard,
 * a start of the console, or FN pressed during the tutorial. Opens the tutorial or tells it
 * about FN.</li>
 * <li>FIRST_START_OPEN: Back to the tutorial on the launcher's page.</li>
 * <li>FIRST_START_SKIP: Skip tutorial on the launcher's page.</li>
 * </ul>
 */
public class FirstStartReceiver extends BroadcastReceiver {

    private static final String TAG = "FirstStart";

    static final String ACTION_HOME = "org.andr36oid.guide.action.FIRST_START_HOME";
    static final String ACTION_OPEN = "org.andr36oid.guide.action.FIRST_START_OPEN";
    static final String ACTION_SKIP = "org.andr36oid.guide.action.FIRST_START_SKIP";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (!FirstStart.isLauncherInstalled(context) || !FirstBootReceiver.isSetupDone(context)
                || action == null) {
            // During the wizard the wizard is home, the launcher never comes to the front
            return;
        }
        if (FirstBootReceiver.wasShown(context)) {
            // Done: the launcher only came up between the tutorial closing and home being
            // handed back, or it wasn't removed
            FirstStart.finish(context, false);
            return;
        }
        if (FirstStart.crashedTooOften(context)) {
            Log.w(TAG, "The tutorial crashed " + FirstStart.MAX_CRASHES
                    + " times, giving the home screen back");
            FirstStart.finish(context, false);
            return;
        }
        switch (action) {
            case ACTION_HOME:
                TutorialActivity.onHome(context);
                break;
            case ACTION_OPEN:
                TutorialActivity.open(context);
                break;
            case ACTION_SKIP:
                Log.i(TAG, "Tutorial skipped from the first-start launcher");
                FirstStart.finish(context, false);
                break;
        }
    }
}
