package org.andr36oid.touchmapper;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's picture: the game stays visible underneath, the controls on top, a crosshair,
 * a title strip at the top and a strip at the bottom that says what each button does right now.
 * All state lives in {@link EditorActivity}; this only draws it.
 */
final class EditorView extends View {

    /** A button and what it does, for the bottom strip. */
    static final class Hint {
        final String key;
        final String action;

        Hint(String key, String action) {
            this.key = key;
            this.action = action;
        }
    }

    private final MarkerPainter mPainter;
    private final Paint mBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKey = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKeyText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final int mSurface;
    private final int mOnSurface;
    private final int mAccent;

    private Profile mProfile;
    private int mLayer;
    private Profile.Control mSelected;
    private Profile.Control mDraft;
    private float mCursorX = -1;
    private float mCursorY = -1;
    private boolean mShowCursor = true;
    private String mTitle = "";
    private String mStatus;
    private String mPrompt;
    private String mPromptDetail;
    private final List<Hint> mHints = new ArrayList<>();

    EditorView(Context context) {
        super(context);
        mPainter = new MarkerPainter(context);
        final TypedArray a = context.obtainStyledAttributes(new int[] {
                android.R.attr.colorBackground,
                android.R.attr.textColorPrimary,
                android.R.attr.colorAccent,
        });
        mSurface = a.getColor(0, 0xFF202124);
        mOnSurface = a.getColor(1, 0xFFFFFFFF);
        mAccent = a.getColor(2, 0xFF8AB4F8);
        a.recycle();

        mBar.setColor(MarkerPainter.withAlpha(mSurface, 235));
        mText.setColor(mOnSurface);
        mText.setTextSize(mPainter.dp(13));
        mKey.setColor(mAccent);
        mKeyText.setColor(isDark(mAccent) ? 0xFFFFFFFF : 0xFF000000);
        mKeyText.setTextSize(mPainter.dp(12));
        mKeyText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mKeyText.setTextAlign(Paint.Align.CENTER);
        mCross.setStyle(Paint.Style.STROKE);
        mCross.setStrokeCap(Paint.Cap.ROUND);
        setFocusable(false);
    }

    private static boolean isDark(int color) {
        final int r = (color >> 16) & 0xFF;
        final int g = (color >> 8) & 0xFF;
        final int b = color & 0xFF;
        return r * 299 + g * 587 + b * 114 < 128000;
    }

    int accent() {
        return mAccent;
    }

    void setProfile(Profile profile, int layer) {
        mProfile = profile;
        mLayer = layer;
        invalidate();
    }

    void setSelected(Profile.Control selected) {
        mSelected = selected;
        invalidate();
    }

    /** A control being placed, drawn but not in the profile yet. */
    void setDraft(Profile.Control draft) {
        mDraft = draft;
        invalidate();
    }

    void setCursor(float x, float y, boolean show) {
        mCursorX = x;
        mCursorY = y;
        mShowCursor = show;
        invalidate();
    }

    void setTitle(String title, String status) {
        mTitle = title;
        mStatus = status;
        invalidate();
    }

    void setPrompt(String prompt, String detail) {
        mPrompt = prompt;
        mPromptDetail = detail;
        invalidate();
    }

    void setHints(List<Hint> hints) {
        mHints.clear();
        mHints.addAll(hints);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        // Darken the game a little so the markers stand out, but keep it readable.
        canvas.drawColor(0x40000000);

        if (mProfile != null) {
            final String shift = mProfile.shift == null ? null
                    : Inputs.label(mProfile.shift) + "+";
            for (Profile.Control c : mProfile.controls) {
                if (c == mSelected) {
                    continue;
                }
                mPainter.draw(canvas, c, w, h, shift, 0, c.layer != mLayer);
            }
            if (mSelected != null) {
                mPainter.draw(canvas, mSelected, w, h, shift, mAccent, false);
            }
            if (mDraft != null) {
                mPainter.draw(canvas, mDraft, w, h, shift, mAccent, false);
            }
        }

        if (mShowCursor && mCursorX >= 0) {
            drawCursor(canvas);
        }
        drawTitle(canvas, w);
        drawHints(canvas, w, h);
        if (mPrompt != null) {
            drawPrompt(canvas, w, h);
        }
    }

    private void drawCursor(Canvas canvas) {
        final float r = mPainter.dp(10);
        final float gap = mPainter.dp(4);
        for (int pass = 0; pass < 2; pass++) {
            // A dark outline under a light cross reads on any background
            mCross.setStrokeWidth(mPainter.dp(pass == 0 ? 4 : 2));
            mCross.setColor(pass == 0 ? 0xC0000000 : 0xFFFFFFFF);
            canvas.drawLine(mCursorX - r, mCursorY, mCursorX - gap, mCursorY, mCross);
            canvas.drawLine(mCursorX + gap, mCursorY, mCursorX + r, mCursorY, mCross);
            canvas.drawLine(mCursorX, mCursorY - r, mCursorX, mCursorY - gap, mCross);
            canvas.drawLine(mCursorX, mCursorY + gap, mCursorX, mCursorY + r, mCross);
        }
    }

    private void drawTitle(Canvas canvas, int w) {
        final float pad = mPainter.dp(8);
        final Paint.FontMetrics fm = mText.getFontMetrics();
        final float height = fm.descent - fm.ascent + pad;
        canvas.drawRect(0, 0, w, height, mBar);
        final float baseline = pad / 2 - fm.ascent;
        mText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(mTitle, pad, baseline, mText);
        mText.setTypeface(Typeface.DEFAULT);
        if (mStatus != null) {
            final float tw = mText.measureText(mStatus);
            canvas.drawText(mStatus, w - pad - tw, baseline, mText);
        }
    }

    /** Key badges and what they do, wrapped onto as many lines as it takes. */
    private void drawHints(Canvas canvas, int w, int h) {
        if (mHints.isEmpty()) {
            return;
        }
        final float pad = mPainter.dp(8);
        final float gap = mPainter.dp(12);
        final float keyPad = mPainter.dp(5);
        final Paint.FontMetrics fm = mText.getFontMetrics();
        final float lineHeight = fm.descent - fm.ascent + mPainter.dp(8);

        // First pass: where each item goes
        final List<float[]> spots = new ArrayList<>();
        float x = pad;
        int lines = 1;
        for (Hint hint : mHints) {
            final float itemWidth = keyWidth(hint.key, keyPad) + mPainter.dp(4)
                    + mText.measureText(hint.action);
            if (x > pad && x + itemWidth > w - pad) {
                x = pad;
                lines++;
            }
            spots.add(new float[] {x, lines - 1});
            x += itemWidth + gap;
        }
        final float top = h - lines * lineHeight - pad / 2;
        canvas.drawRect(0, top - pad / 2, w, h, mBar);
        for (int i = 0; i < mHints.size(); i++) {
            final Hint hint = mHints.get(i);
            final float lineTop = top + spots.get(i)[1] * lineHeight;
            final float baseline = lineTop + (lineHeight - (fm.descent - fm.ascent)) / 2 - fm.ascent;
            float cx = spots.get(i)[0];
            final float kw = keyWidth(hint.key, keyPad);
            mRect.set(cx, lineTop + mPainter.dp(3), cx + kw, lineTop + lineHeight - mPainter.dp(3));
            canvas.drawRoundRect(mRect, mPainter.dp(4), mPainter.dp(4), mKey);
            final Paint.FontMetrics km = mKeyText.getFontMetrics();
            canvas.drawText(hint.key, mRect.centerX(), mRect.centerY() - (km.ascent + km.descent) / 2,
                    mKeyText);
            cx += kw + mPainter.dp(4);
            canvas.drawText(hint.action, cx, baseline, mText);
        }
    }

    private float keyWidth(String key, float keyPad) {
        return Math.max(mKeyText.measureText(key) + 2 * keyPad, mPainter.dp(20));
    }

    private void drawPrompt(Canvas canvas, int w, int h) {
        final float pad = mPainter.dp(16);
        final Paint big = new Paint(mText);
        big.setTextSize(mPainter.dp(17));
        big.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        big.setTextAlign(Paint.Align.CENTER);
        final Paint small = new Paint(mText);
        small.setTextAlign(Paint.Align.CENTER);
        final float width = Math.min(w - 2 * pad,
                Math.max(big.measureText(mPrompt),
                        mPromptDetail == null ? 0 : small.measureText(mPromptDetail)) + 2 * pad);
        final Paint.FontMetrics bm = big.getFontMetrics();
        final Paint.FontMetrics sm = small.getFontMetrics();
        final float height = (bm.descent - bm.ascent) + (mPromptDetail == null ? 0
                : sm.descent - sm.ascent + mPainter.dp(8)) + 2 * pad;
        mRect.set((w - width) / 2, (h - height) / 2, (w + width) / 2, (h + height) / 2);
        canvas.drawRoundRect(mRect, mPainter.dp(12), mPainter.dp(12), mBar);
        final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(mPainter.dp(2));
        border.setColor(mAccent);
        canvas.drawRoundRect(mRect, mPainter.dp(12), mPainter.dp(12), border);
        float y = mRect.top + pad - bm.ascent;
        canvas.drawText(mPrompt, w / 2f, y, big);
        if (mPromptDetail != null) {
            y += bm.descent + mPainter.dp(8) - sm.ascent;
            canvas.drawText(mPromptDetail, w / 2f, y, small);
        }
    }
}
