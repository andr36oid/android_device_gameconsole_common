package org.andr36oid.stickcalibration;

import java.util.Locale;

/**
 * One stick calibration: for each axis the raw reading at both ends and in the middle, in the
 * joypad driver's raw units (millivolts times its ADC scale). Axes are in the driver's order:
 * left X, left Y, right X, right Y. No Android classes, so it can be tested on the host.
 */
final class Calibration {

    static final int AXES = 4;
    static final int DEFAULT_DEADZONE = 8;
    static final int MAX_DEADZONE = 30;

    /** Raw travel from the middle a direction needs before it counts as reached */
    static final int MIN_TRAVEL = 300;
    /** The stored ends sit this far inside the measured ones, so full tilt is easy to reach */
    static final float EDGE_MARGIN = 0.05f;
    /** Resting jitter above this share of the travel means a stick was touched */
    static final float MAX_REST_SPREAD = 0.10f;

    final int[] min = new int[AXES];
    final int[] center = new int[AXES];
    final int[] max = new int[AXES];

    /** The text the driver's stick_cal file takes and shows: min center max, per axis. */
    String format() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AXES; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%d %d %d", min[i], center[i], max[i]));
        }
        return sb.toString();
    }

    /** Reads stick_cal text back, null for "off" or anything else that isn't 12 numbers. */
    static Calibration parse(String text) {
        if (text == null) {
            return null;
        }
        final String[] parts = text.trim().split("\\s+");
        if (parts.length != AXES * 3) {
            return null;
        }
        final Calibration cal = new Calibration();
        try {
            for (int i = 0; i < AXES; i++) {
                cal.min[i] = Integer.parseInt(parts[i * 3]);
                cal.center[i] = Integer.parseInt(parts[i * 3 + 1]);
                cal.max[i] = Integer.parseInt(parts[i * 3 + 2]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return cal;
    }

    /** What went wrong when a capture could not be turned into a calibration. */
    enum Problem { NONE, LEFT_SHORT, RIGHT_SHORT, NOT_STILL }

    /**
     * Collects readings while the user circles the sticks (the extremes), then while they
     * rest (the middle).
     */
    static final class Capture {
        final int[] lo = new int[AXES];
        final int[] hi = new int[AXES];
        /** The first reading, taken as the middle until the real one is measured */
        final int[] start = new int[AXES];
        private boolean mStarted;

        private final long[] mRestSum = new long[AXES];
        private final int[] mRestLo = new int[AXES];
        private final int[] mRestHi = new int[AXES];
        private int mRestCount;

        Problem problem = Problem.NONE;
        Calibration result;
        int deadzone;

        /** A reading while circling. Readings of all zeros (driver not polled yet) are skipped. */
        void add(int[] raw) {
            if (!valid(raw)) {
                return;
            }
            for (int i = 0; i < AXES; i++) {
                if (!mStarted) {
                    lo[i] = hi[i] = start[i] = raw[i];
                } else {
                    lo[i] = Math.min(lo[i], raw[i]);
                    hi[i] = Math.max(hi[i], raw[i]);
                }
            }
            mStarted = true;
        }

        boolean started() {
            return mStarted;
        }

        /** Directions of one stick (0 left, 1 right) that went far enough from the start. */
        int directions(int stick) {
            if (!mStarted) {
                return 0;
            }
            int n = 0;
            for (int i = stick * 2; i < stick * 2 + 2; i++) {
                if (start[i] - lo[i] >= MIN_TRAVEL) {
                    n++;
                }
                if (hi[i] - start[i] >= MIN_TRAVEL) {
                    n++;
                }
            }
            return n;
        }

        void clearRest() {
            mRestCount = 0;
        }

        /** A reading while the sticks rest. */
        void addRest(int[] raw) {
            if (!valid(raw)) {
                return;
            }
            for (int i = 0; i < AXES; i++) {
                if (mRestCount == 0) {
                    mRestLo[i] = mRestHi[i] = raw[i];
                    mRestSum[i] = 0;
                }
                mRestSum[i] += raw[i];
                mRestLo[i] = Math.min(mRestLo[i], raw[i]);
                mRestHi[i] = Math.max(mRestHi[i], raw[i]);
            }
            mRestCount++;
            // The rest readings count as circling readings too
            add(raw);
        }

        /**
         * Works out the calibration from what was collected. Sets {@link #result} and
         * {@link #deadzone}, or {@link #problem}. The dead zone is the chosen one, raised when
         * the sticks jitter more than that at rest.
         */
        boolean finish(int chosenDeadzone) {
            result = null;
            problem = Problem.NONE;
            deadzone = chosenDeadzone;
            if (mRestCount == 0 || !mStarted) {
                problem = Problem.NOT_STILL;
                return false;
            }
            final Calibration cal = new Calibration();
            int jitter = 0;
            for (int i = 0; i < AXES; i++) {
                final int mid = (int) Math.round(mRestSum[i] / (double) mRestCount);
                final int down = mid - lo[i];
                final int up = hi[i] - mid;
                if (down < MIN_TRAVEL || up < MIN_TRAVEL) {
                    problem = i < 2 ? Problem.LEFT_SHORT : Problem.RIGHT_SHORT;
                    return false;
                }
                final int travel = Math.min(down, up);
                final int spread = mRestHi[i] - mRestLo[i];
                if (spread > travel * MAX_REST_SPREAD) {
                    problem = Problem.NOT_STILL;
                    return false;
                }
                // Half the spread either side of the middle, in percent, plus some room
                jitter = Math.max(jitter,
                        (int) Math.ceil(spread * 50.0 / travel) + 2);
                cal.center[i] = mid;
                cal.min[i] = mid - Math.round(down * (1 - EDGE_MARGIN));
                cal.max[i] = mid + Math.round(up * (1 - EDGE_MARGIN));
            }
            deadzone = Math.min(MAX_DEADZONE, Math.max(chosenDeadzone, jitter));
            result = cal;
            return true;
        }

        private static boolean valid(int[] raw) {
            if (raw == null || raw.length != AXES) {
                return false;
            }
            for (int v : raw) {
                if (v != 0) {
                    return true;
                }
            }
            return false;
        }
    }
}
