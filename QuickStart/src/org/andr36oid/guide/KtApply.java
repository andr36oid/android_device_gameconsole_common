package org.andr36oid.guide;

import android.os.SystemClock;
import android.os.SystemProperties;

/**
 * The same way Settings &gt; System &gt; Kernel Tweaks (KernelTweaksFragment) and Buttons on
 * this console (Controllers' ButtonFinderActivity) save a kernel tweak: the choice goes in
 * persist.andr36oid.kt.&lt;key&gt; (empty follows the kernel's own default), then the root
 * helper andr36oid-ktweaks.sh is asked to write it. It reports the boot default in
 * sys.andr36oid.ktd.&lt;key&gt; and what the kernel took in sys.andr36oid.ktc.&lt;key&gt;, so
 * reading is all properties. No new plumbing: QuickStart runs as the system uid, like the
 * two apps above, so the same properties and service work here.
 */
final class KtApply {

    private static final String SERVICE = "andr36oid-ktweaks";
    private static final String REQUEST_PROP = "sys.andr36oid.kt.request";
    private static final String WANT_PREFIX = "persist.andr36oid.kt.";
    private static final String DEFAULT_PREFIX = "sys.andr36oid.ktd.";
    private static final String CURRENT_PREFIX = "sys.andr36oid.ktc.";

    private KtApply() {
    }

    /** What the kernel had at boot, "" when this console doesn't have the knob. */
    static String def(String key) {
        return SystemProperties.get(DEFAULT_PREFIX + key, "").trim();
    }

    /** What the kernel has now: the helper's last report, else the boot default. */
    static String current(String key) {
        final String v = SystemProperties.get(CURRENT_PREFIX + key, "").trim();
        return v.isEmpty() ? def(key) : v;
    }

    /** Whether this console has the knob (so there is a default to go back to). */
    static boolean available(String key) {
        return !def(key).isEmpty();
    }

    /**
     * Saves a tweak and asks the helper to write it, exactly as KernelTweaksFragment.save does:
     * the default is stored as "" so it keeps following the kernel.
     */
    static void set(String key, String value) {
        SystemProperties.set(WANT_PREFIX + key, value.equals(def(key)) ? "" : value);
        request();
    }

    /** Back to the console's own default for this knob. */
    static void clear(String key) {
        SystemProperties.set(WANT_PREFIX + key, "");
        request();
    }

    private static void request() {
        SystemProperties.set(REQUEST_PROP, Long.toString(SystemClock.elapsedRealtime()));
        SystemProperties.set("ctl.start", SERVICE);
    }
}
