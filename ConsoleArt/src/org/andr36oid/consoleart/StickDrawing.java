package org.andr36oid.consoleart;

/**
 * One stick, the same in the controller test, the stick calibration and the Quick start
 * guide: its round gate with a cross through the middle, the dead zone, the path it took
 * and a dot where it is now. Sizes follow the radius; the label goes under it.
 */
public final class StickDrawing {

    public static final class Colors {
        public int fill;
        public int ring;
        public int guide;
        public int dot;
        /** The dot while the stick is clicked in */
        public int pressed;
        public int deadzone;
        public int trail;
        public int label;
    }

    /** Text size of the label, in the painter's units; 0 = a fifth of the radius */
    public float labelSize;

    private float[] mPoints = new float[0];

    /**
     * @param x        where the stick is, -1..1 each way, as the framework reports it
     * @param deadzone the dead zone, 0..1 of the travel; 0 draws none
     * @param trail    recent positions as x, y pairs in a ring buffer, or null
     * @param head     the slot the next position goes in
     * @param count    how many positions are in it
     * @param label    drawn under the stick, or null
     */
    public void draw(Painter p, Colors c, float cx, float cy, float r, float x, float y,
            boolean pressed, float deadzone, float[] trail, int head, int count,
            String label) {
        p.circle(cx, cy, r, c.fill, c.ring, Math.max(1, r * 0.035f));
        final float thin = Math.max(1, r * 0.015f);
        p.poly(new float[] {cx - r, cy, cx + r, cy}, 2, false, 0, c.guide, thin);
        p.poly(new float[] {cx, cy - r, cx, cy + r}, 2, false, 0, c.guide, thin);
        if (deadzone > 0) {
            p.circle(cx, cy, r * Math.min(1f, deadzone), c.deadzone, 0, 0);
        }
        if (trail != null && count > 1) {
            final int capacity = trail.length / 2;
            if (mPoints.length < count * 2) {
                mPoints = new float[count * 2];
            }
            final int first = ((head - count) % capacity + capacity) % capacity;
            for (int k = 0; k < count; k++) {
                final int i = (first + k) % capacity;
                mPoints[k * 2] = cx + ConsoleDrawing.clamp(trail[i * 2]) * r;
                mPoints[k * 2 + 1] = cy + ConsoleDrawing.clamp(trail[i * 2 + 1]) * r;
            }
            p.poly(mPoints, count, false, 0, c.trail, Math.max(1, r * 0.025f));
        }
        p.circle(cx + ConsoleDrawing.clamp(x) * r, cy + ConsoleDrawing.clamp(y) * r,
                Math.max(3, r * 0.12f), pressed ? c.pressed : c.dot, 0, 0);
        if (label != null && !label.isEmpty()) {
            final float size = labelSize > 0 ? labelSize : r * 0.2f;
            p.text(label, cx, cy + r + size * 1.3f, size, false, c.label);
        }
    }
}
