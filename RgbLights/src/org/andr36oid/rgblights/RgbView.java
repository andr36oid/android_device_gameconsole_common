package org.andr36oid.rgblights;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;

/**
 * The stick-lights settings screen content: two fixed preview rings showing the
 * chosen preset, the preset name, and a strip of preset swatches. The action bar
 * owns the title and the shared HintBar owns the key hints, so this view draws
 * only the middle. Colours come from the DayNight theme attributes and sizes from
 * {@code sp()}, the same way StickCalibration's CalibrationView does.
 *
 * <p>The rings are a FIXED size - they never zoom or pulse. A 33 ms tick runs only
 * while an animated preset is selected: a rainbow advances a rotation {@code phase}
 * so the hue wheel turns, and a breathing preset crossfades through the steady
 * colours. Only colour/alpha ever changes, never the ring radius; a steady-colour
 * or off preset is completely still and the ticker is not armed at all.
 */
class RgbView extends View {

    /* Effect kinds, 1:1 with the keros chip's built-in behaviours. */
    private static final int STEADY = 0;
    private static final int BREATHING = 1;
    private static final int RAINBOW = 2;

    /** A selectable lighting preset. Array index == keros brightness value 0..9. */
    private static final class Preset {
        final String name;
        final int color;    // 0xAARRGGBB swatch/preview colour; 0 == unlit (Off only)
        final int effect;   // STEADY / BREATHING / RAINBOW

        Preset(String name, int color, int effect) {
            this.name = name;
            this.color = color;
            this.effect = effect;
        }

        boolean lit() {
            return effect != STEADY || (color & 0xffffff) != 0;
        }

        boolean animated() {
            return effect == BREATHING || effect == RAINBOW;
        }
    }

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mRotate = new Matrix();
    private final RectF mRect = new RectF();

    private final int mFg;
    private final int mSecondary;
    private final int mAccent;
    private final int mBackground;

    /* The two focus zones: the preset strip and the "Lights off" sleep row. */
    private static final int ZONE_STRIP = 0;
    private static final int ZONE_SLEEP = 1;

    /* Sleep-behaviour segment values; index == segment, matches PROP_SLEEP tokens. */
    private static final String[] SLEEP_VALUES = {"screen", "deepsleep", "never"};
    private final String[] mSleepLabels;

    /** A seamless 0..360 hue wheel for the rainbow preset (first stop == last). */
    private final int[] mWheel = hueWheel(13, 0xff);
    private final int[] mWheelGlow = hueWheel(13, 0x55);

    /** Colours the breathing preset crossfades through (the steady presets 1-7). */
    private static final int[] BREATHE = {
            0xFFFF3030, 0xFFFFA500, 0xFF00C020, 0xFF00C8B0,
            0xFF2060FF, 0xFFE85CFF, 0xFFA0FFB0,
    };
    /** Milliseconds each breathing colour takes to fade into the next (calm pace). */
    private static final long BREATHE_SEG_MS = 2600L;

    /**
     * The presets, in keros order: index == the value written to the brightness
     * node. 0 off, 1-7 steady colours, 8 breathing, 9 rainbow.
     */
    private final Preset[] mPresets;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mSelected;
    private int mPhase;
    private int mZone = ZONE_STRIP;
    private int mSleepSel;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            final Preset p = mPresets[mSelected];
            // Steady/off presets never animate; only the ticker's owners redraw.
            if (!p.animated()) {
                return;
            }
            if (p.effect == RAINBOW) {
                mPhase = (mPhase + 3) % 360;
            }
            invalidate();   // breathing reads the clock in onDraw; rainbow uses mPhase
            mHandler.postDelayed(this, 33);
        }
    };

    RgbView(Context context) {
        super(context);
        final TypedArray a = context.obtainStyledAttributes(new int[] {
                android.R.attr.textColorPrimary, android.R.attr.textColorSecondary,
                android.R.attr.colorAccent, android.R.attr.colorBackground});
        mFg = a.getColor(0, 0xFFFFFFFF);
        mSecondary = a.getColor(1, 0xFF9E9E9E);
        mAccent = a.getColor(2, 0xFF80CBC4);
        mBackground = a.getColor(3, 0xFF101010);
        a.recycle();

        mSleepLabels = new String[] {
                context.getString(R.string.rgb_sleep_screen),
                context.getString(R.string.rgb_sleep_deepsleep),
                context.getString(R.string.rgb_sleep_never),
        };

        mPresets = new Preset[] {
                new Preset(context.getString(R.string.rgb_preset_off),       0,          STEADY),
                new Preset(context.getString(R.string.rgb_preset_red),       0xFFFF3030, STEADY),
                new Preset(context.getString(R.string.rgb_preset_amber),     0xFFFFA500, STEADY),
                new Preset(context.getString(R.string.rgb_preset_green),     0xFF00C020, STEADY),
                new Preset(context.getString(R.string.rgb_preset_teal),      0xFF00C8B0, STEADY),
                new Preset(context.getString(R.string.rgb_preset_blue),      0xFF2060FF, STEADY),
                new Preset(context.getString(R.string.rgb_preset_pink),      0xFFE85CFF, STEADY),
                new Preset(context.getString(R.string.rgb_preset_mint),      0xFFA0FFB0, STEADY),
                new Preset(context.getString(R.string.rgb_preset_breathing), 0,          BREATHING),
                new Preset(context.getString(R.string.rgb_preset_rainbow),   0,          RAINBOW),
        };

        mSelected = readSelected();
        mSleepSel = sleepIndex(RgbLights.readSleep("deepsleep"));
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    private static int sleepIndex(String value) {
        for (int i = 0; i < SLEEP_VALUES.length; i++) {
            if (SLEEP_VALUES[i].equals(value)) {
                return i;
            }
        }
        return 1;   // "deepsleep" default
    }

    /* ---- lifecycle ----------------------------------------------------- */

    void start() {
        mHandler.removeCallbacks(mTick);
        // Only an animated preset (breathing / rainbow) needs the ticker.
        if (mPresets[mSelected].animated()) {
            mHandler.post(mTick);
        }
    }

    void stop() {
        mHandler.removeCallbacks(mTick);
    }

    /* ---- selection ----------------------------------------------------- */

    /** DPAD up/down: move between the two focus zones (strip / sleep row). */
    void moveZone(int dir) {
        final int z = Math.max(ZONE_STRIP, Math.min(ZONE_SLEEP, mZone + dir));
        if (z != mZone) {
            mZone = z;
            invalidate();
        }
    }

    /** DPAD left/right: change the focused zone's value live. */
    void step(int dir) {
        if (mZone == ZONE_STRIP) {
            final int n = mPresets.length;
            apply((mSelected + dir % n + n) % n);    // presets wrap
        } else {
            final int n = SLEEP_VALUES.length;
            applySleep(Math.max(0, Math.min(n - 1, mSleepSel + dir)));  // segments clamp
        }
    }

    private void apply(int i) {
        if (i == mSelected) {
            // Already on this preset; re-writing the prop would re-run the full
            // power-up + init + effect frame on the chip for no visible change.
            return;
        }
        mSelected = i;
        // 1:1 with the keros chip: the index IS the brightness/preset value init
        // writes to /sys/class/leds/keros::ambient/brightness.
        RgbLights.setPreset(i);
        start();        // (re)arm or drop the ticker for the new preset
        invalidate();
    }

    private void applySleep(int i) {
        if (i == mSleepSel) {
            return;
        }
        mSleepSel = i;
        RgbLights.setSleep(SLEEP_VALUES[i]);
        invalidate();
    }

    /** Read the saved preset back; defaults to the rainbow index when unset. */
    private int readSelected() {
        return RgbLights.readPreset(mPresets.length - 1);
    }

    /* ---- drawing ------------------------------------------------------- */

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private static int withAlpha(int color, float factor) {
        return (color & 0x00ffffff) | (Math.round(Color.alpha(color) * factor) << 24);
    }

    private static int[] hueWheel(int n, int alpha) {
        final int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = Color.HSVToColor(alpha, new float[] {360f * i / (n - 1), 1f, 1f});
        }
        return a;
    }

    private static int lerpColor(int a, int b, float t) {
        final int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        final int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return 0xff000000
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    /** The breathing colour at the current moment: a crossfade across BREATHE. */
    private static int breatheColor() {
        final int n = BREATHE.length;
        final long now = SystemClock.uptimeMillis();
        final float pos = (now % (BREATHE_SEG_MS * n)) / (float) BREATHE_SEG_MS; // 0..n
        final int k = (int) pos;
        final float f = pos - k;
        return lerpColor(BREATHE[k], BREATHE[(k + 1) % n], f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        final Preset p = mPresets[mSelected];

        /* ---- two fixed-size preview rings ---- */
        final float ringR = Math.min(w, h) * 0.15f;
        final float ringCY = h * 0.25f;
        final float cxL = w * 0.31f;
        final float cxR = w * 0.69f;
        drawRing(canvas, cxL, ringCY, ringR, p);
        drawRing(canvas, cxR, ringCY, ringR, p);

        /* ---- Left / Right captions ---- */
        mText.setShader(null);
        mText.setFakeBoldText(false);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTextSize(sp(15));
        mText.setColor(mSecondary);
        final float capBase = ringCY + ringR + sp(24);
        canvas.drawText(getContext().getString(R.string.rgb_preview_left), cxL, capBase, mText);
        canvas.drawText(getContext().getString(R.string.rgb_preview_right), cxR, capBase, mText);

        /* ---- selected preset name, in the accent colour ---- */
        mText.setFakeBoldText(true);
        mText.setTextSize(sp(22));
        mText.setColor(mAccent);
        canvas.drawText(p.name, w / 2f, capBase + sp(40), mText);
        mText.setFakeBoldText(false);

        /* ---- preset strip (focus zone 0) ---- */
        drawStrip(canvas, w, h, mZone == ZONE_STRIP);

        /* ---- divider + "Lights off" sleep row (focus zone 1) ---- */
        drawDivider(canvas, w, h * 0.80f);
        drawSleepRow(canvas, w, h, h * 0.90f, mZone == ZONE_SLEEP);
    }

    /**
     * A believable LED ring: a soft colour bloom, a crisp coloured ring, a faint
     * white inner highlight and a small grey centre nub. A steady preset uses a
     * solid ring; breathing uses a solid ring whose colour crossfades; rainbow uses
     * a hue-wheel sweep rotated by the phase; "Off" is a dim neutral ring. The
     * geometry is identical for all - the radius never changes.
     */
    private void drawRing(Canvas canvas, float cx, float cy, float r, Preset p) {
        final float stroke = r * 0.14f;
        if (p.effect == RAINBOW) {
            drawRainbowRing(canvas, cx, cy, r, stroke);
        } else if (p.effect == BREATHING) {
            drawSolidRing(canvas, cx, cy, r, stroke, breatheColor());
        } else if ((p.color & 0xffffff) != 0) {
            drawSolidRing(canvas, cx, cy, r, stroke, 0xff000000 | p.color);
        } else {
            drawOffRing(canvas, cx, cy, r, stroke);
        }

        /* centre stick nub */
        final float nubR = r * 0.2f;
        mFill.setShader(null);
        mFill.setColor(withAlpha(mFg, p.lit() ? 0.30f : 0.14f));
        canvas.drawCircle(cx, cy, nubR, mFill);
        mStroke.setShader(null);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(Math.max(1f, r * 0.03f));
        mStroke.setColor(withAlpha(mFg, 0.28f));
        canvas.drawCircle(cx, cy, nubR, mStroke);
    }

    private void drawSolidRing(Canvas canvas, float cx, float cy, float r, float stroke,
            int solid) {
        /* outer bloom: colour at the ring fading to transparent */
        final float bloomR = r + stroke * 3.2f;
        final RadialGradient g = new RadialGradient(cx, cy, bloomR,
                new int[] {withAlpha(solid, 0.55f), withAlpha(solid, 0.22f),
                        withAlpha(solid, 0f)},
                new float[] {0f, 0.58f, 1f}, Shader.TileMode.CLAMP);
        mGlow.setShader(g);
        mGlow.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, bloomR, mGlow);
        mGlow.setShader(null);

        /* translucent inner fill */
        mFill.setShader(null);
        mFill.setColor(withAlpha(solid, 0.16f));
        canvas.drawCircle(cx, cy, r - stroke * 0.5f, mFill);

        /* crisp core ring */
        mStroke.setShader(null);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(stroke);
        mStroke.setColor(solid);
        canvas.drawCircle(cx, cy, r, mStroke);

        /* faint white inner highlight */
        mStroke.setStrokeWidth(stroke * 0.4f);
        mStroke.setColor(withAlpha(0xffffffff, 0.45f));
        canvas.drawCircle(cx, cy, r - stroke * 0.42f, mStroke);
    }

    private void drawRainbowRing(Canvas canvas, float cx, float cy, float r, float stroke) {
        mRotate.setRotate(mPhase, cx, cy);

        /* soft wide bloom from the translucent wheel */
        final SweepGradient glow = new SweepGradient(cx, cy, mWheelGlow, null);
        glow.setLocalMatrix(mRotate);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(stroke * 3f);
        mStroke.setColor(0xffffffff);
        mStroke.setShader(glow);
        canvas.drawCircle(cx, cy, r, mStroke);

        /* crisp spectrum ring */
        final SweepGradient ring = new SweepGradient(cx, cy, mWheel, null);
        ring.setLocalMatrix(mRotate);
        mStroke.setStrokeWidth(stroke);
        mStroke.setShader(ring);
        canvas.drawCircle(cx, cy, r, mStroke);
        mStroke.setShader(null);

        /* faint white inner highlight */
        mStroke.setStrokeWidth(stroke * 0.4f);
        mStroke.setColor(withAlpha(0xffffffff, 0.45f));
        canvas.drawCircle(cx, cy, r - stroke * 0.42f, mStroke);
    }

    private void drawOffRing(Canvas canvas, float cx, float cy, float r, float stroke) {
        mFill.setShader(null);
        mFill.setColor(withAlpha(mSecondary, 0.06f));
        canvas.drawCircle(cx, cy, r - stroke * 0.5f, mFill);
        mStroke.setShader(null);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(stroke);
        mStroke.setColor(withAlpha(mSecondary, 0.4f));
        canvas.drawCircle(cx, cy, r, mStroke);
    }

    /**
     * The preset swatch strip: Off (a hollow ring), the steady colour swatches, a
     * two-tone gradient chip for breathing and a hue-wheel chip for rainbow (so the
     * two animated presets read as effects, not colours). The selected chip is
     * enlarged and ringed in accent, with "Off" captioned under the first. Chip
     * sizes are fixed per draw - selection changes which chip is big, never an
     * animation.
     */
    private void drawStrip(Canvas canvas, float w, float h, boolean focused) {
        final int n = mPresets.length;
        final float stripW = w * 0.80f;
        final float leftX = w * 0.5f - stripW / 2f;
        final float step = stripW / (n - 1);
        final float baseR = Math.min(step * 0.30f, Math.min(w, h) * 0.035f);
        final float cy = h * 0.66f;

        for (int i = 0; i < n; i++) {
            final float cx = leftX + i * step;
            final boolean sel = i == mSelected;
            final float rr = sel ? baseR * 1.3f : baseR;
            final Preset p = mPresets[i];

            if (p.effect == RAINBOW) {
                final SweepGradient g = new SweepGradient(cx, cy, mWheel, null);
                mFill.setShader(g);
                mFill.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, rr, mFill);
                mFill.setShader(null);
            } else if (p.effect == BREATHING) {
                /* soft two-tone gradient chip - reads as an effect, not a colour */
                final LinearGradient g = new LinearGradient(cx - rr, cy - rr, cx + rr, cy + rr,
                        0xFF2060FF, 0xFFE85CFF, Shader.TileMode.CLAMP);
                mFill.setShader(g);
                mFill.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, rr, mFill);
                mFill.setShader(null);
            } else if ((p.color & 0xffffff) != 0) {
                mFill.setShader(null);
                mFill.setColor(0xff000000 | p.color);
                mFill.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, rr, mFill);
            } else {
                /* Off: a hollow ring */
                mStroke.setShader(null);
                mStroke.setStyle(Paint.Style.STROKE);
                mStroke.setStrokeWidth(Math.max(1.5f, rr * 0.16f));
                mStroke.setColor(withAlpha(mFg, 0.6f));
                canvas.drawCircle(cx, cy, rr * 0.86f, mStroke);
            }

            if (sel) {
                /* The selected-chip ring doubles as the zone-0 focus indicator:
                 * full accent when the strip is focused, dimmed when the sleep row
                 * has focus, so the current preset still reads either way. */
                final float pad = baseR * 0.4f;
                mStroke.setShader(null);
                mStroke.setStyle(Paint.Style.STROKE);
                mStroke.setStrokeWidth(Math.max(2f, baseR * 0.18f));
                mStroke.setColor(focused ? mAccent : withAlpha(mAccent, 0.45f));
                canvas.drawCircle(cx, cy, rr + pad, mStroke);
            }
        }

        /* "Off" caption under the first chip */
        mText.setShader(null);
        mText.setFakeBoldText(false);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTextSize(sp(12));
        mText.setColor(mSecondary);
        canvas.drawText(getContext().getString(R.string.rgb_preset_off), leftX,
                cy + baseR * 1.3f + sp(16), mText);
    }

    /** A thin horizontal rule separating the preset strip from the sleep row. */
    private void drawDivider(Canvas canvas, float w, float y) {
        mStroke.setShader(null);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(Math.max(1f, sp(0.6f)));
        mStroke.setColor(withAlpha(mSecondary, 0.30f));
        canvas.drawLine(w * 0.08f, y, w * 0.92f, y, mStroke);
    }

    /**
     * The "Lights off" row (focus zone 1): a left label and a three-way segmented
     * control (With screen / At sleep / Never). The selected segment is filled in
     * accent with dark text; the rest are dark pills with light text. When the row
     * is focused an accent outline is drawn around the whole control as the focus
     * indicator (like the strip's selected ring); the selected fill dims when the
     * row is unfocused so the current value still reads. Sizes are view-fraction /
     * clamped so the row stays on screen at 640x480.
     */
    private void drawSleepRow(Canvas canvas, float w, float h, float cy, boolean focused) {
        /* label on the left */
        mText.setShader(null);
        mText.setFakeBoldText(false);
        mText.setTextAlign(Paint.Align.LEFT);
        mText.setTextSize(sp(17));
        mText.setColor(mFg);
        final Paint.FontMetrics fm = mText.getFontMetrics();
        final float textCy = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(getContext().getString(R.string.rgb_sleep_label), w * 0.08f,
                textCy, mText);

        /* segmented control on the right */
        final int n = mSleepLabels.length;
        final float groupRight = w * 0.95f;
        final float groupLeft = w * 0.40f;
        final float gap = Math.min(sp(8), (groupRight - groupLeft) * 0.04f);
        final float segH = Math.min(sp(34), h * 0.10f);
        final float segW = (groupRight - groupLeft - gap * (n - 1)) / n;
        final float top = cy - segH / 2f;
        final float radius = segH * 0.30f;

        mText.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            final float left = groupLeft + i * (segW + gap);
            mRect.set(left, top, left + segW, top + segH);
            final boolean sel = i == mSleepSel;

            mFill.setShader(null);
            mFill.setStyle(Paint.Style.FILL);
            if (sel) {
                mFill.setColor(focused ? mAccent : withAlpha(mAccent, 0.62f));
            } else {
                mFill.setColor(withAlpha(mFg, 0.12f));   // dark pill
            }
            canvas.drawRoundRect(mRect, radius, radius, mFill);

            /* fit the label: shrink text a touch if it would overflow the segment */
            float ts = sp(13);
            mText.setFakeBoldText(sel);
            mText.setTextSize(ts);
            final String label = mSleepLabels[i];
            final float maxW = segW - sp(8);
            if (mText.measureText(label) > maxW && mText.measureText(label) > 0) {
                ts *= maxW / mText.measureText(label);
                mText.setTextSize(ts);
            }
            mText.setColor(sel ? mBackground : mFg);
            final Paint.FontMetrics sfm = mText.getFontMetrics();
            canvas.drawText(label, left + segW / 2f,
                    cy - (sfm.ascent + sfm.descent) / 2f, mText);
        }
        mText.setFakeBoldText(false);

        /* focus indicator: accent outline around the whole control when focused */
        if (focused) {
            final float pad = Math.max(2f, sp(3));
            mRect.set(groupLeft - pad, top - pad, groupRight + pad, top + segH + pad);
            mStroke.setShader(null);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(Math.max(2f, sp(1.5f)));
            mStroke.setColor(mAccent);
            canvas.drawRoundRect(mRect, radius + pad, radius + pad, mStroke);
        }
    }
}
