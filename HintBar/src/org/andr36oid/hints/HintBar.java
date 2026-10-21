package org.andr36oid.hints;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The button hints along the bottom of a screen, "(A) Select  (B) Back", in the launcher's
 * look: 24 dp tall, each button a light pill with its name in dark bold letters, then what it
 * does in bold. The same in every andr36oid app.
 *
 * <p>{@link #attach} puts it under an activity's content, whatever fills it later (fragments
 * too); or add {@link #HintBar(Context)} to a layout yourself.
 */
public class HintBar extends LinearLayout {

    /** The bar's height. */
    public static final int HEIGHT_DP = 24;

    private final TextView mLeading;
    private final LinearLayout mKeys;

    public HintBar(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(12), 0, dp(12), 0);
        setBackgroundColor(backgroundOf(context));
        mLeading = new TextView(context);
        mLeading.setTextSize(14);
        mLeading.setTypeface(Typeface.DEFAULT_BOLD);
        mLeading.setTextColor(0xffe6e6e6);
        mLeading.setSingleLine(true);
        mLeading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(mLeading, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        mKeys = new LinearLayout(context);
        mKeys.setOrientation(HORIZONTAL);
        mKeys.setGravity(Gravity.CENTER_VERTICAL);
        addView(mKeys, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    /**
     * Puts a bar under the activity's content and returns it. Call it in onCreate; the
     * content keeps all of the screen but the bar's 24 dp.
     *
     * @param hints pairs of button and what it does: "A", "Select", "B", "Back"
     */
    public static HintBar attach(Activity activity, CharSequence... hints) {
        final FrameLayout content = activity.findViewById(android.R.id.content);
        final HintBar bar = new HintBar(activity);
        final int h = bar.dp(HEIGHT_DP);
        content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                content.getPaddingRight(), content.getPaddingBottom() + h);
        content.setClipToPadding(false);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM);
        lp.bottomMargin = -h;
        content.addView(bar, lp);
        bar.set(hints);
        return bar;
    }

    /** "A Select, B Back": the hints for a list or a settings page. */
    public static HintBar attachDefault(Activity activity) {
        return attach(activity, "A", activity.getString(R.string.andr36oid_hint_select),
                "B", activity.getString(R.string.andr36oid_hint_back));
    }

    /** Shows these hints instead: pairs of button and what it does. */
    public void set(CharSequence... hints) {
        mKeys.removeAllViews();
        for (int i = 0; i + 1 < hints.length; i += 2) {
            add(hints[i], hints[i + 1]);
        }
    }

    /**
     * Shows hints written as one line, "A  Calibrate     B  Back": hints three or more
     * spaces apart, each a button and two spaces then what it does.
     */
    public void setLine(String line) {
        mKeys.removeAllViews();
        for (String hint : line.trim().split("\\s{3,}")) {
            final int gap = hint.indexOf("  ");
            if (gap > 0) add(hint.substring(0, gap).trim(), hint.substring(gap).trim());
        }
    }

    /** One more hint at the end. */
    public void add(CharSequence key, CharSequence label) {
        mKeys.addView(key(getContext(), key));
        final TextView text = label(getContext(), label);
        mKeys.addView(text);
    }

    /** Text on the left, such as the name of what is selected; null for none. */
    public void setLeading(CharSequence text) {
        mLeading.setText(text);
    }

    /** A button as the bar shows it, for screens that lay out their own hints. */
    public static TextView key(Context context, CharSequence key) {
        final TextView badge = new TextView(context);
        badge.setText(key);
        badge.setTextSize(11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(0xff2e2e2e);
        badge.setIncludeFontPadding(false);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setMinWidth(dp(context, 18));
        badge.setPadding(dp(context, 4), 0, dp(context, 4), 0);
        badge.setBackgroundResource(R.drawable.andr36oid_hint_key);
        final LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(context, 18));
        lp.setMarginStart(dp(context, 12));
        badge.setLayoutParams(lp);
        return badge;
    }

    /** What a button does, as the bar shows it. */
    public static TextView label(Context context, CharSequence label) {
        final TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(12);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextColor(0xffe6e6e6);
        text.setSingleLine(true);
        text.setPadding(dp(context, 4), 0, 0, 0);
        return text;
    }

    private static int backgroundOf(Context context) {
        final TypedValue v = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.colorBackground, v, true)
                && v.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && v.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return v.data;
        }
        return 0xff101010;
    }

    private int dp(int v) {
        return dp(getContext(), v);
    }

    private static int dp(Context context, int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
