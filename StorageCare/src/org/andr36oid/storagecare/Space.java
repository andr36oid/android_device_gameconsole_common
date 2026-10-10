package org.andr36oid.storagecare;

/**
 * When free space counts as low, and when to tell the user. Plain Java for the host test.
 *
 * Internal storage (userdata, about 16 GB) and EASYROMS are judged apart. Android's own
 * warning only looks at the internal storage, at 500 MB, and doesn't say what to do.
 *
 * A warning is shown once per step down (low, then full). It comes back only after the
 * space went up past the limit plus a margin and then fell again, so a card that sits
 * right at the limit doesn't warn every few hours.
 */
final class Space {

    private Space() {
    }

    static final int OK = 0;
    static final int LOW = 1;
    static final int FULL = 2;

    static final long MB = 1024L * 1024L;
    static final long GB = 1024L * MB;

    static final String INTERNAL = "internal";
    static final String EASYROMS = "easyroms";

    /** Below this it's "almost full". */
    static long lowLimit(String volume) {
        return INTERNAL.equals(volume) ? GB : GB / 2;
    }

    /**
     * Below this it's "full": apps start failing (internal), saves and states can't be
     * written (EASYROMS; a PSP save state alone is 30-40 MB).
     */
    static long fullLimit(String volume) {
        return INTERNAL.equals(volume) ? 300 * MB : 128 * MB;
    }

    /** How far above a limit the space must get before the warning is armed again. */
    static final long MARGIN = 256 * MB;

    static int level(String volume, long freeBytes) {
        if (freeBytes < fullLimit(volume)) return FULL;
        if (freeBytes < lowLimit(volume)) return LOW;
        return OK;
    }

    /**
     * The next step. {@code warned} is the level we last warned about (OK = none). Returns
     * the new "warned" level in the low bits and whether to show the warning now in bit 8.
     */
    static int next(String volume, long freeBytes, int warned) {
        final int now = level(volume, freeBytes);
        if (now > warned) return now | SHOW;
        // Only step back down once the space is clearly above that step's limit.
        int stay = warned;
        while (stay > now) {
            final long limit = stay == FULL ? fullLimit(volume) : lowLimit(volume);
            if (freeBytes < limit + MARGIN) break;
            stay--;
        }
        return stay;
    }

    static final int SHOW = 0x100;

    static boolean show(int next) {
        return (next & SHOW) != 0;
    }

    static int warned(int next) {
        return next & 0xff;
    }
}
