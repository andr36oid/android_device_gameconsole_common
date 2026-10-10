package org.andr36oid.consoleart;

/**
 * The front of the R36S, upright as it is held, in drawing units: the body is 120 wide and 176
 * tall. The screen fills the top, the D-pad sits under it on the left, the speaker in the
 * middle, A B X Y on the right, FN below the speaker with Select and Start under it, and the
 * two sticks in the bottom corners. The shoulder buttons are on the top edge (L2 L1 ... R2 R1
 * from the front), power on the left side and the volume buttons on the right side, + above -;
 * they are drawn as tabs sticking out of the body. Plain Java, so the host test can draw it
 * too.
 */
public final class R36s {

    public static final int SHAPE_ROUND = 0;
    /** On the top edge or a side */
    public static final int SHAPE_TAB = 1;
    /** One arm of the D-pad */
    public static final int SHAPE_DPAD = 2;
    public static final int SHAPE_STICK = 3;

    /** Everything that is drawn, with some room for focus rings and badges: l, t, r, b */
    public static final float[] VIEWPORT = {-13, -13, 133, 180};
    public static final float[] BODY = {0, 0, 120, 176};
    public static final float BODY_RADIUS = 9;
    public static final float[] BEZEL = {6, 5, 114, 83.5f};
    public static final float BEZEL_RADIUS = 4;
    /** The 640x480 panel, 4:3 */
    public static final float[] SCREEN = {11, 8.5f, 109, 82};
    public static final float[] SPEAKER = {50, 90, 70, 102};
    /** The D-pad cross, its arms are the controls */
    public static final float[] DPAD_VERTICAL = {20, 86, 30, 116};
    public static final float[] DPAD_HORIZONTAL = {10, 96, 40, 106};

    public enum Control {
        DPAD_UP(SHAPE_DPAD, null, null, 20, 86, 30, 96),
        DPAD_DOWN(SHAPE_DPAD, null, null, 20, 106, 30, 116),
        DPAD_LEFT(SHAPE_DPAD, null, null, 10, 96, 20, 106),
        DPAD_RIGHT(SHAPE_DPAD, null, null, 30, 96, 40, 106),
        X(SHAPE_ROUND, "X", null, round(95, 95.5f, 5.5f)),
        Y(SHAPE_ROUND, "Y", null, round(84.5f, 106, 5.5f)),
        A(SHAPE_ROUND, "A", null, round(105.5f, 106, 5.5f)),
        B(SHAPE_ROUND, "B", null, round(95, 116.5f, 5.5f)),
        FN(SHAPE_ROUND, null, "FN", round(60, 112, 4.2f)),
        SELECT(SHAPE_ROUND, null, "SELECT", round(49, 123, 4.2f)),
        START(SHAPE_ROUND, null, "START", round(71, 123, 4.2f)),
        L3(SHAPE_STICK, "L3", null, round(25, 148, 14)),
        R3(SHAPE_STICK, "R3", null, round(95, 148, 14)),
        L2(SHAPE_TAB, "L2", null, 4, -10, 21, -2),
        L1(SHAPE_TAB, "L1", null, 23, -10, 40, -2),
        VOLUME_UP(SHAPE_TAB, "+", null, 122, 20, 130, 32),
        VOLUME_DOWN(SHAPE_TAB, "−", null, 122, 34, 130, 46),
        /** Drawn with the power symbol */
        POWER(SHAPE_TAB, null, null, -10, 22, -2, 36),
        R2(SHAPE_TAB, "R2", null, 80, -10, 97, -2),
        R1(SHAPE_TAB, "R1", null, 99, -10, 116, -2);

        public final int shape;
        /** Text on the control, null for an icon or nothing */
        public final String label;
        /** Text printed on the body next to it (FN above, Select and Start below), or null */
        public final String caption;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        Control(int shape, String label, String caption, float left, float top, float right,
                float bottom) {
            this.shape = shape;
            this.label = label;
            this.caption = caption;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        Control(int shape, String label, String caption, float[] box) {
            this(shape, label, caption, box[0], box[1], box[2], box[3]);
        }

        public float centerX() {
            return (left + right) / 2;
        }

        public float centerY() {
            return (top + bottom) / 2;
        }

        public float width() {
            return right - left;
        }

        public float height() {
            return bottom - top;
        }
    }

    private static float[] round(float cx, float cy, float r) {
        return new float[] {cx - r, cy - r, cx + r, cy + r};
    }

    private R36s() {
    }
}
