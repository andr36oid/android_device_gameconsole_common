package org.andr36oid.storagecare;

import java.util.Map;

/**
 * What the helper found out about EASYROMS (see health() in andr36oid-storagecare.sh),
 * boiled down to the one problem worth telling. Plain Java for the host test.
 */
final class Health {

    private Health() {
    }

    /** vold couldn't mount it: the check before mounting failed, or it isn't a file system. */
    static final String UNMOUNTABLE = "unmountable";
    /** Mounted read-only: the kernel hit an error and stopped writing, or vold's check left errors. */
    static final String READ_ONLY = "read_only";
    /** The kernel reported file system errors, but it's still writable. */
    static final String ERRORS = "errors";
    /** The check before mounting found and fixed errors (after a power loss). */
    static final String FIXED = "fixed";

    /** The worst problem, or null if all is well (or nothing is known). */
    static String problem(Map<String, String> h) {
        if (h == null) return null;
        if ("unmountable".equals(h.get("easyroms_state"))) return UNMOUNTABLE;
        if (!"mounted".equals(h.get("easyroms_state"))) return null;
        if ("1".equals(h.get("easyroms_ro"))) return READ_ONLY;
        int errors = 0;
        try {
            errors = Integer.parseInt(h.getOrDefault("fs_errors", "0"));
        } catch (NumberFormatException e) {
            // unknown
        }
        if (errors > 0) return ERRORS;
        if ("fixed".equals(h.get("fsck"))) return FIXED;
        return null;
    }
}
