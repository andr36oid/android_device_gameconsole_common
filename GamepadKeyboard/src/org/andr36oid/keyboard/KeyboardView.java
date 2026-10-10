package org.andr36oid.keyboard;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.ContextThemeWrapper;
import android.view.View;

import org.andr36oid.keyboard.core.Key;
import org.andr36oid.keyboard.core.KeyboardLogic;
import org.andr36oid.keyboard.core.Page;
import org.andr36oid.keyboard.core.Radial;

import java.util.List;

/**
 * Draws the keyboard: a line of button hints (or the accent choices) on top, then either the
 * key grid or the two stick circles. One plain View, no key views, so it stays cheap.
 */
public class KeyboardView extends View {
    private static final float HINT_DP = 20;
    private static final float KEY_DP = 29;
    private static final int ROWS = 5;

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final int colBg;
    private final int colKey;
    private final int colSpecial;
    private final int colText;
    private final int colDim;
    private final int colAccent;
    private final int colOnAccent;

    private KeyboardLogic logic;
    private String enterLabel = "↵";
    private boolean showHints = true;

    public KeyboardView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        Context themed = new ContextThemeWrapper(context,
                android.R.style.Theme_DeviceDefault_DayNight);
        TypedArray ta = themed.obtainStyledAttributes(new int[] {android.R.attr.colorAccent});
        colAccent = ta.getColor(0, night ? 0xff8ab4f8 : 0xff1a73e8);
        ta.recycle();
        if (night) {
            colBg = 0xff202124;
            colKey = 0xff3c4043;
            colSpecial = 0xff2d2f31;
            colText = 0xffe8eaed;
            colDim = 0xff9aa0a6;
            colOnAccent = 0xff202124;
        } else {
            colBg = 0xffdadce0;
            colKey = 0xffffffff;
            colSpecial = 0xffc4c7c5;
            colText = 0xff202124;
            colDim = 0xff5f6368;
            colOnAccent = 0xffffffff;
        }
        text.setTextAlign(Paint.Align.CENTER);
        badge.setTextAlign(Paint.Align.RIGHT);
        badge.setTextSize(8 * dp);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public void setLogic(KeyboardLogic logic) {
        this.logic = logic;
        invalidate();
    }

    public void setEnterLabel(String label) {
        enterLabel = label;
        invalidate();
    }

    /** Hides the hint line text and the button names on keys (the line stays for accents). */
    public void setShowHints(boolean show) {
        showHints = show;
        invalidate();
    }

    private float hintHeight() {
        return HINT_DP * dp;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = Math.round(hintHeight() + ROWS * KEY_DP * dp);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(colBg);
        if (logic == null) return;
        float top = hintHeight();
        if (logic.variants() != null) {
            drawVariants(c, top);
        } else if (showHints) {
            drawHints(c, top);
        }
        if (logic.mode() == KeyboardLogic.MODE_STICKS) {
            drawSticks(c, top);
        } else {
            drawGrid(c, top);
        }
    }

    // ---- hint line ----

    private void drawHints(Canvas c, float h) {
        String s = getContext().getString(logic.mode() == KeyboardLogic.MODE_STICKS
                ? R.string.hint_sticks : R.string.hint_grid);
        text.setColor(colDim);
        text.setTypeface(Typeface.DEFAULT);
        fitText(s, getWidth() - 8 * dp, 12 * dp, 8 * dp);
        c.drawText(s, getWidth() / 2f, baseline(h / 2f), text);
    }

    private void drawVariants(Canvas c, float h) {
        List<String> v = logic.variants();
        float cell = Math.min(40 * dp, (getWidth() - 8 * dp) / Math.max(1, v.size() + 1));
        float x0 = (getWidth() - cell * v.size()) / 2f;
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(Math.min(h * 0.62f, 16 * dp));
        for (int i = 0; i < v.size(); i++) {
            boolean sel = i == logic.variantIndex();
            rect.set(x0 + i * cell + 1.5f * dp, 1.5f * dp, x0 + (i + 1) * cell - 1.5f * dp, h - 1.5f * dp);
            fill.setColor(sel ? colAccent : colKey);
            c.drawRoundRect(rect, 4 * dp, 4 * dp, fill);
            text.setColor(sel ? colOnAccent : colText);
            c.drawText(v.get(i), rect.centerX(), baseline(rect.centerY()), text);
        }
    }

    // ---- grid ----

    private void drawGrid(Canvas c, float top) {
        Page p = logic.page();
        float unit = getWidth() / 10f;
        float keyH = (getHeight() - top) / p.rowCount();
        float pad = 1.5f * dp;
        for (int r = 0; r < p.rowCount(); r++) {
            List<Key> row = p.row(r);
            for (int i = 0; i < row.size(); i++) {
                Key k = row.get(i);
                boolean sel = r == logic.nav().row() && i == logic.nav().col();
                rect.set(k.left() * unit + pad, top + r * keyH + pad,
                        (k.left() + k.width) * unit - pad, top + (r + 1) * keyH - pad);
                fill.setColor(sel ? colAccent : (k.code == Key.CHAR ? colKey : colSpecial));
                c.drawRoundRect(rect, 4 * dp, 4 * dp, fill);
                if (k.code == Key.SHIFT && logic.shifted() && !sel) {
                    fill.setColor(colAccent);
                    c.drawRect(rect.left + 6 * dp, rect.bottom - 3 * dp, rect.right - 6 * dp,
                            rect.bottom - 1.5f * dp, fill);
                }
                String label = labelFor(k);
                text.setColor(sel ? colOnAccent : colText);
                text.setTypeface(k.code == Key.CHAR ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
                fitText(label, rect.width() - 4 * dp, Math.min(keyH * 0.55f, 17 * dp), 8 * dp);
                c.drawText(label, rect.centerX(), baseline(rect.centerY()), text);
                String b = badgeFor(k);
                if (b != null && showHints) {
                    badge.setColor(sel ? colOnAccent : colDim);
                    c.drawText(b, rect.right - 3 * dp, rect.top + 9 * dp, badge);
                }
            }
        }
    }

    private String labelFor(Key k) {
        switch (k.code) {
            case Key.CHAR:
                return k.output(logic.shifted());
            case Key.SHIFT:
                return logic.shift() == KeyboardLogic.SHIFT_LOCK ? "⇪" : "⇧";
            case Key.ENTER:
                return enterLabel;
            case Key.SPACE:
                return getContext().getString(R.string.key_space);
            default:
                return k.label;
        }
    }

    private static String badgeFor(Key k) {
        switch (k.code) {
            case Key.SHIFT: return "Y";
            case Key.DELETE: return "B";
            case Key.SPACE: return "X";
            case Key.ENTER: return "START";
            case Key.PAGE: return "SELECT";
            case Key.SWITCH_IME: return "L3";
            default: return null;
        }
    }

    // ---- stick mode ----

    private void drawSticks(Canvas c, float top) {
        float h = getHeight() - top;
        float cy = top + h / 2f;
        Radial rad = logic.radial();
        String[] groups = rad.groups();

        // Left: the 8 groups around a circle.
        float lcx = getWidth() * 0.27f;
        float ring = Math.min(h / 2f - 16 * dp, getWidth() * 0.2f);
        float cellW = 44 * dp;
        float cellH = 22 * dp;
        text.setTypeface(Typeface.DEFAULT);
        for (int g = 0; g < 8; g++) {
            double a = Math.toRadians(g * 45.0);
            float x = lcx + (float) Math.sin(a) * ring;
            float y = cy - (float) Math.cos(a) * ring * 0.88f;
            boolean sel = g == rad.group();
            rect.set(x - cellW / 2, y - cellH / 2, x + cellW / 2, y + cellH / 2);
            fill.setColor(sel ? colAccent : colKey);
            c.drawRoundRect(rect, 6 * dp, 6 * dp, fill);
            text.setColor(sel ? colOnAccent : colText);
            String s = shown(groups[g]);
            fitText(s, cellW - 4 * dp, 14 * dp, 8 * dp);
            c.drawText(s, x, baseline(y), text);
        }
        text.setColor(colDim);
        text.setTextSize(9 * dp);
        c.drawText(getContext().getString(R.string.stick_left), lcx, baseline(cy), text);

        // Right: the picked group's 4 characters as a diamond.
        float rcx = getWidth() * 0.73f;
        float arm = Math.min(h / 2f - 16 * dp, 52 * dp);
        float cell = 30 * dp;
        String grp = groups[rad.group()];
        for (int s = 0; s < 4; s++) {
            double a = Math.toRadians(s * 90.0);
            float x = rcx + (float) Math.sin(a) * arm * 1.3f;
            float y = cy - (float) Math.cos(a) * arm;
            boolean sel = s == rad.slot();
            rect.set(x - cell / 2, y - cell / 2, x + cell / 2, y + cell / 2);
            fill.setColor(sel ? colAccent : colKey);
            c.drawRoundRect(rect, 6 * dp, 6 * dp, fill);
            text.setColor(sel ? colOnAccent : colText);
            text.setTextSize(18 * dp);
            String ch = s < grp.length() ? shown(String.valueOf(grp.charAt(s))) : "";
            c.drawText(ch, x, baseline(y), text);
        }
        text.setColor(colDim);
        text.setTextSize(9 * dp);
        c.drawText(getContext().getString(R.string.stick_right), rcx, baseline(cy), text);

        // Shift and page state, bottom left and right.
        text.setTextSize(10 * dp);
        text.setColor(logic.shifted() ? colAccent : colDim);
        String sh = logic.shift() == KeyboardLogic.SHIFT_LOCK ? "⇪ Y" : "⇧ Y";
        c.drawText(sh, 24 * dp, getHeight() - 6 * dp, text);
        text.setColor(colDim);
        c.drawText((logic.onSymbols() ? "ABC" : "?123") + "  SELECT", getWidth() - 44 * dp,
                getHeight() - 6 * dp, text);
    }

    private String shown(String s) {
        return logic.shifted() ? s.toUpperCase(java.util.Locale.ROOT) : s;
    }

    // ---- text helpers ----

    private void fitText(String s, float maxW, float size, float min) {
        text.setTextSize(size);
        float w = text.measureText(s);
        if (w > maxW && w > 0) text.setTextSize(Math.max(min, size * maxW / w));
    }

    private float baseline(float centerY) {
        Paint.FontMetrics fm = text.getFontMetrics();
        return centerY - (fm.ascent + fm.descent) / 2f;
    }
}
