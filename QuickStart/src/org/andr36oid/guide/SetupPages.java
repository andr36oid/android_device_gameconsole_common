package org.andr36oid.guide;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

/**
 * Our pages in the setup wizard (org.lineageos.setupwizard SetupWizardApp): a page goes on by
 * sending NEXT with the extras it was started with and its result code.
 */
final class SetupPages {

    private static final String TAG = "SetupPages";

    private static final String SETUP_WIZARD_PACKAGE = "org.lineageos.setupwizard";
    private static final String ACTION_NEXT = "com.android.wizard.NEXT";
    static final String EXTRA_SCRIPT_URI = "scriptUri";
    private static final String EXTRA_RESULT_CODE = "com.android.setupwizard.ResultCode";
    private static final int REQUEST_NEXT = 10000;

    private SetupPages() {
    }

    /** Whether the activity was started as a page of the setup wizard. */
    static boolean isPage(Activity page) {
        return page.getIntent().hasExtra(EXTRA_SCRIPT_URI);
    }

    /**
     * On to the wizard's next page. The page stays below it, so B there comes back; the
     * wizard closes its whole task when it's done.
     */
    static void next(Activity page) {
        final Intent next = new Intent(ACTION_NEXT).setPackage(SETUP_WIZARD_PACKAGE);
        if (page.getIntent().getExtras() != null) {
            next.putExtras(page.getIntent().getExtras());
        }
        next.putExtra(EXTRA_RESULT_CODE, Activity.RESULT_OK);
        try {
            page.startActivityForResult(next, REQUEST_NEXT);
        } catch (RuntimeException e) {
            // Never leave the wizard stuck on this page
            Log.w(TAG, "Couldn't go on with the setup", e);
            page.finish();
        }
    }
}
