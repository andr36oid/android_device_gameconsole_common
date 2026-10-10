package org.andr36oid.keyboard.core;

/**
 * Stick typing: the left stick points at one of 8 groups around a circle, the right stick
 * points at one of the 4 characters of that group, and letting the right stick go back to
 * the middle types it. The group stays picked when the left stick is let go, so several
 * letters of one group need no left stick move.
 *
 * Directions: group 0 is up, then clockwise in 45 degree steps. Slot 0 is up, 1 right,
 * 2 down, 3 left. Stick values are -1..1 with y growing downwards, like Android's axes.
 */
public final class Radial {
    public static final String[] LETTERS = {
        "abcd", "efgh", "ijkl", "mnop", "qrst", "uvwx", "yz,.", "?!@-",
    };
    public static final String[] SYMBOLS = {
        "1234", "5678", "90.,", "@#$%", "&*-+", "=/_:", "()!?", "'\";~",
    };

    /** Past this the stick counts as pointing somewhere. */
    public static final float PICK = 0.55f;
    /** Below this the stick counts as back in the middle. */
    public static final float RELEASE = 0.30f;

    private String[] groups = LETTERS;
    private int group = 0;
    private int slot = -1;          // right stick slot being pointed at, -1 = none
    private boolean typedByButton;  // A typed the pointed slot, don't type again on release

    public void setSymbols(boolean symbols) {
        groups = symbols ? SYMBOLS : LETTERS;
    }

    public String[] groups() {
        return groups;
    }

    public int group() {
        return group;
    }

    public int slot() {
        return slot;
    }

    public void reset() {
        group = 0;
        slot = -1;
        typedByButton = false;
    }

    /** 0..sectors-1 for a stick position, sector 0 centred straight up, clockwise. */
    public static int sector(float x, float y, int sectors) {
        double deg = Math.toDegrees(Math.atan2(x, -y)); // 0 = up, 90 = right
        if (deg < 0) deg += 360;
        double step = 360.0 / sectors;
        return (int) Math.floor((deg + step / 2) / step) % sectors;
    }

    private static float mag(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    /** Left stick moved. Returns true if the picked group changed. */
    public boolean left(float x, float y) {
        if (mag(x, y) < PICK) return false;
        int g = sector(x, y, 8);
        if (g == group) return false;
        group = g;
        return true;
    }

    /** Step the group with the d-pad: +1 clockwise, -1 the other way. */
    public void rotate(int step) {
        group = ((group + step) % 8 + 8) % 8;
    }

    /**
     * Right stick moved. Returns the character to type when the stick comes back to the
     * middle after pointing at a slot, otherwise null.
     */
    public String right(float x, float y) {
        float m = mag(x, y);
        if (m >= PICK) {
            int s = sector(x, y, 4);
            if (s != slot) typedByButton = false;
            slot = s;
            return null;
        }
        if (m < RELEASE && slot >= 0) {
            String out = typedByButton ? null : charAt(group, slot);
            slot = -1;
            typedByButton = false;
            return out;
        }
        return null;
    }

    /**
     * A pressed: type what the right stick points at right away (each press types once more,
     * and letting the stick go then types nothing).
     */
    public String pressA() {
        if (slot < 0) return null;
        typedByButton = true;
        return charAt(group, slot);
    }

    public String charAt(int g, int s) {
        String grp = groups[g];
        return s < grp.length() ? String.valueOf(grp.charAt(s)) : null;
    }
}
