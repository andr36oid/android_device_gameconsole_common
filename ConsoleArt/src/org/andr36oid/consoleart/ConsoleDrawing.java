package org.andr36oid.consoleart;

import org.andr36oid.consoleart.R36s.Control;

/**
 * Draws the R36S (see R36s) in its units. Each app decides how every control looks through a
 * Styler: the button mapper shows the selection, the controller test what is pressed and what
 * has worked. The Quick start guide uses the plain drawing.
 */
public final class ConsoleDrawing {

    public static final float STROKE = 1.1f;
    private static final float FOCUS_WIDTH = 1.6f;
    private static final float FOCUS_GAP = 2.5f;
    private static final float BADGE_RADIUS = 3.2f;
    private static final float TAB_RADIUS = 2.5f;
    private static final float DPAD_RADIUS = 2.5f;
    /** How far a stick's cap moves at full tilt */
    private static final float STICK_TRAVEL = 3.5f;
    private static final float STICK_CAP = 10;

    public static final class Colors {
        public int body;
        public int outline;
        public int button;
        public int label;
        /** Captions printed on the body (FN, SELECT, START) */
        public int caption;
        public int bezel;
        public int screen;
        public int accent;
        public int onAccent;
        /** Around badges, to set them off the drawing */
        public int background;
    }

    /** How one control looks. Styler.style() gets it filled with the plain look. */
    public static final class Look {
        public int fill;
        public int outline;
        /** Its label or icon */
        public int content;
        public int caption;
        /** A ring around it */
        public boolean focus;
        /** A dot on its corner, e.g. "does something else now" */
        public boolean badge;
        /** Sticks: how far the cap is tilted, -1..1 */
        public float stickX;
        public float stickY;
    }

    public interface Styler {
        void style(Control control, Look look);
    }

    private final Look mLook = new Look();
    private final float[] mPoints = new float[24];

    public void draw(Painter p, Colors c, Styler styler) {
        rect(p, R36s.BODY, R36s.BODY_RADIUS, c.body, c.outline, STROKE);
        rect(p, R36s.BEZEL, R36s.BEZEL_RADIUS, c.bezel, c.outline, STROKE);
        rect(p, R36s.SCREEN, 1, c.screen, 0, 0);
        speaker(p, c);
        dpadCross(p, c);
        for (Control control : Control.values()) {
            mLook.fill = c.button;
            mLook.outline = c.outline;
            mLook.content = c.label;
            mLook.caption = c.caption;
            mLook.focus = false;
            mLook.badge = false;
            mLook.stickX = 0;
            mLook.stickY = 0;
            if (styler != null) {
                styler.style(control, mLook);
            }
            control(p, c, control, mLook);
        }
    }

    private void control(Painter p, Colors c, Control k, Look look) {
        final float cx = k.centerX();
        final float cy = k.centerY();
        switch (k.shape) {
            case R36s.SHAPE_TAB:
                p.rect(k.left, k.top, k.right, k.bottom, TAB_RADIUS, look.fill, look.outline,
                        STROKE);
                if (k == Control.POWER) {
                    power(p, cx, cy, 2.4f, look.content);
                } else {
                    p.text(k.label, cx, cy, k.width() > 12 ? 5 : 6, true, look.content);
                }
                break;
            case R36s.SHAPE_DPAD:
                // An arm is just part of the cross until it looks different
                if (look.fill != c.button || look.outline != c.outline) {
                    p.rect(k.left, k.top, k.right, k.bottom, DPAD_RADIUS, look.fill,
                            look.outline, STROKE);
                }
                arrow(p, k, look.content);
                break;
            case R36s.SHAPE_STICK: {
                final float r = k.width() / 2;
                // The base, then the cap tilted the way the stick is
                p.circle(cx, cy, r, c.button, c.outline, STROKE);
                final float x = cx + clamp(look.stickX) * STICK_TRAVEL;
                final float y = cy + clamp(look.stickY) * STICK_TRAVEL;
                p.circle(x, y, STICK_CAP, look.fill, look.outline, STROKE);
                p.circle(x, y, STICK_CAP * 0.62f, 0, look.outline, STROKE * 0.8f);
                p.text(k.label, x, y, 5, true, look.content);
                break;
            }
            default: {
                final float r = k.width() / 2;
                p.circle(cx, cy, r, look.fill, look.outline, STROKE);
                if (k.label != null) {
                    p.text(k.label, cx, cy, 6, true, look.content);
                }
                if (k.caption != null) {
                    // FN's sits above it, under the speaker; Select's and Start's below
                    final float y = k == Control.FN ? k.top - 2.6f : k.bottom + 3.4f;
                    p.text(k.caption, cx, y, 3.6f, false, look.caption);
                }
                break;
            }
        }
        if (look.focus) {
            focus(p, k, c.accent);
        }
        if (look.badge) {
            badge(p, k, c);
        }
    }

    private void speaker(Painter p, Colors c) {
        rect(p, R36s.SPEAKER, 6, 0, c.outline, STROKE * 0.8f);
        // Two rows of slots
        final float[] s = R36s.SPEAKER;
        for (int row = 0; row < 2; row++) {
            final float y = s[1] + 4 + row * 3.2f;
            for (int i = 0; i < 3; i++) {
                final float x = s[0] + 4.5f + i * 4;
                p.rect(x, y, x + 3, y + 1.2f, 0.6f, c.outline, 0, 0);
            }
        }
    }

    private void dpadCross(Painter p, Colors c) {
        final float[] v = R36s.DPAD_VERTICAL;
        final float[] h = R36s.DPAD_HORIZONTAL;
        final float[] xy = {
                v[0], v[1], v[2], v[1], v[2], h[1], h[2], h[1], h[2], h[3], v[2], h[3],
                v[2], v[3], v[0], v[3], v[0], h[3], h[0], h[3], h[0], h[1], v[0], h[1]};
        System.arraycopy(xy, 0, mPoints, 0, xy.length);
        p.poly(mPoints, 12, true, c.button, c.outline, STROKE);
    }

    private void arrow(Painter p, Control k, int color) {
        final float x = k.centerX();
        final float y = k.centerY();
        final float s = 2.6f;
        final float back = s * 0.6f;
        final float[] xy;
        switch (k) {
            case DPAD_UP:
                xy = new float[] {x, y - s, x + s, y + back, x - s, y + back};
                break;
            case DPAD_DOWN:
                xy = new float[] {x, y + s, x - s, y - back, x + s, y - back};
                break;
            case DPAD_LEFT:
                xy = new float[] {x - s, y, x + back, y - s, x + back, y + s};
                break;
            default:
                xy = new float[] {x + s, y, x - back, y + s, x - back, y - s};
                break;
        }
        p.poly(xy, 3, true, color, 0, 0);
    }

    /** The power symbol: a circle open at the top with a line through the gap. */
    public static void power(Painter p, float x, float y, float r, int color) {
        p.arc(x, y, r, -60, 300, color, STROKE);
        p.poly(new float[] {x, y - r * 1.25f, x, y - r * 0.15f}, 2, false, 0, color, STROKE);
    }

    private void focus(Painter p, Control k, int color) {
        final float g = FOCUS_GAP;
        switch (k.shape) {
            case R36s.SHAPE_ROUND:
            case R36s.SHAPE_STICK:
                p.circle(k.centerX(), k.centerY(), k.width() / 2 + g, 0, color, FOCUS_WIDTH);
                break;
            default:
                p.rect(k.left - g, k.top - g, k.right + g, k.bottom + g,
                        (k.shape == R36s.SHAPE_TAB ? TAB_RADIUS : DPAD_RADIUS) + g, 0, color,
                        FOCUS_WIDTH);
                break;
        }
    }

    private void badge(Painter p, Control k, Colors c) {
        float x = k.right;
        float y = k.top;
        if (k.shape == R36s.SHAPE_ROUND || k.shape == R36s.SHAPE_STICK) {
            // On the rim, towards the top right
            final float d = k.width() / 2 * 0.7071f;
            x = k.centerX() + d;
            y = k.centerY() - d;
        } else if (k == Control.DPAD_DOWN) {
            y = k.bottom;
        } else if (k == Control.DPAD_LEFT) {
            x = k.left;
        }
        p.circle(x, y, BADGE_RADIUS, c.accent, c.background, 1);
    }

    private static void rect(Painter p, float[] r, float radius, int fill, int stroke,
            float width) {
        p.rect(r[0], r[1], r[2], r[3], radius, fill, stroke, width);
    }

    static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
