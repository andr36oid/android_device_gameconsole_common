package org.andr36oid.kerneltweaks;

import android.content.Context;

/**
 * One kernel knob. The root helper (andr36oid-ktweaks.sh) has the same key and does the
 * writing; this side only knows how to show it.
 */
final class Tweak {

    /** pick one of {@link #values} */
    static final int CHOICE = 0;
    /** stick_invert: four 0/1 flags, shown as checkboxes flipped against the default */
    static final int AXES = 1;
    /** stick_sensitivity: a percent per stick (left, right), each picked from {@link #values} */
    static final int STICKS = 2;

    /** risk levels, shown as a colored dot left of the title; every tweak needs one */
    static final int SAFE = 1;
    static final int LAST_RESORT = 2;
    static final int RISKY = 3;

    final String key;
    final int kind;
    final int title;
    final int what;
    final int why;
    /** the file the helper writes, only shown in the explanation */
    final String file;
    /** sys.andr36oid.kt.* instead of persist: gone after a restart */
    boolean once;
    /** a kernel command line option: takes effect at the next start */
    boolean atBoot;
    /** a system property instead of a kernel file, {@link #file} is its name */
    boolean systemSetting;
    String[] values = {};
    /** a label per value, or 0 to use {@link #unit} */
    int[] labels;
    /** format for a plain number, e.g. "%1$s ms"; 0 shows the number as it is */
    int unit;
    /** the kernel's number is this many units, e.g. 1000 for m°C shown as °C */
    int scale = 1;
    /** {@link #SAFE}, {@link #LAST_RESORT} or {@link #RISKY}; 0 until set */
    int level;

    private Tweak(String key, int kind, int title, int what, int why, String file) {
        this.key = key;
        this.kind = kind;
        this.title = title;
        this.what = what;
        this.why = why;
        this.file = file;
    }

    static Tweak choice(String key, int title, int what, int why, String file) {
        return new Tweak(key, CHOICE, title, what, why, file);
    }

    static Tweak axes(String key, int title, int what, int why, String file) {
        return new Tweak(key, AXES, title, what, why, file);
    }

    static Tweak sticks(String key, int title, int what, int why, String file) {
        return new Tweak(key, STICKS, title, what, why, file);
    }

    Tweak values(String... v) {
        values = v;
        return this;
    }

    Tweak labels(int... l) {
        labels = l;
        return this;
    }

    Tweak unit(int u) {
        unit = u;
        return this;
    }

    Tweak scale(int s) {
        scale = s;
        return this;
    }

    Tweak level(int l) {
        level = l;
        return this;
    }

    Tweak systemSetting() {
        systemSetting = true;
        return this;
    }

    Tweak once() {
        once = true;
        return this;
    }

    Tweak atBoot() {
        atBoot = true;
        return this;
    }

    String prop() {
        return (once ? "sys.andr36oid.kt." : "persist.andr36oid.kt.") + key;
    }

    /** what the kernel had at boot, "" when this console doesn't have the knob */
    String defaultProp() {
        return "sys.andr36oid.ktd." + key;
    }

    /** what the kernel has after the helper's last run */
    String currentProp() {
        return "sys.andr36oid.ktc." + key;
    }

    String label(Context c, String value) {
        if (value == null || value.isEmpty()) return "";
        if (kind == AXES) return value;
        if (labels != null) {
            for (int i = 0; i < values.length && i < labels.length; i++) {
                if (values[i].equals(value) && labels[i] != 0) return c.getString(labels[i]);
            }
        }
        String shown = value;
        if (scale != 1) {
            try {
                shown = Long.toString(Long.parseLong(value) / scale);
            } catch (NumberFormatException e) {
                // show it as the kernel has it
            }
        }
        return unit != 0 ? c.getString(unit, shown) : shown;
    }
}
