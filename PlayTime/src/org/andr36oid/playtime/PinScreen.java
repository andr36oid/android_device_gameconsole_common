package org.andr36oid.playtime;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The layout of the PIN pages: what it is about on the left, the pad on the right, so it
 * fits a 640 x 480 screen without scrolling.
 */
final class PinScreen {

    final TextView title;
    final TextView text;

    PinScreen(Activity activity, PinPad pad) {
        final float density = activity.getResources().getDisplayMetrics().density;
        final int pad16 = Math.round(16 * density);
        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad16 * 2, pad16, pad16 * 2, pad16);

        final LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(activity);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        text = new TextView(activity);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setPadding(0, pad16, 0, 0);
        left.addView(title);
        left.addView(text);
        root.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        activity.setContentView(root);
    }
}
