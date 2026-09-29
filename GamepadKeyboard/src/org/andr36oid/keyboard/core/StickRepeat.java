package org.andr36oid.keyboard.core;

/**
 * Turns a stick into d-pad steps: one step when it is pushed, then after a pause repeated
 * steps while it is held, like a held d-pad. Time is passed in, so this runs on the host.
 */
public final class StickRepeat {
    public static final float PUSH = 0.6f;
    public static final float LET_GO = 0.35f;
    public static final long FIRST_DELAY_MS = 380;
    public static final long REPEAT_MS = 110;

    private int dir = -1;
    private long nextAt;

    /** The direction held right now, or -1. */
    public int direction() {
        return dir;
    }

    /** When the next repeat is due (only meaningful while direction() != -1). */
    public long nextAt() {
        return nextAt;
    }

    /**
     * New stick position. Returns a GridNav direction to step once now, or -1. Diagonals go
     * to whichever axis is pushed further.
     */
    public int update(float x, float y, long now) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float m = Math.max(ax, ay);
        if (dir != -1 && m < LET_GO) {
            dir = -1;
            return -1;
        }
        if (m < PUSH) return -1;
        int d = ax > ay ? (x > 0 ? GridNav.RIGHT : GridNav.LEFT) : (y > 0 ? GridNav.DOWN : GridNav.UP);
        if (d == dir) return -1;
        dir = d;
        nextAt = now + FIRST_DELAY_MS;
        return d;
    }

    /** Called from a timer. Returns the direction to step again, or -1. */
    public int tick(long now) {
        if (dir == -1 || now < nextAt) return -1;
        nextAt = now + REPEAT_MS;
        return dir;
    }

    public void reset() {
        dir = -1;
    }
}
