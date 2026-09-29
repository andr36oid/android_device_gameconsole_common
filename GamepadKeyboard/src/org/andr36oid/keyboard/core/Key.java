package org.andr36oid.keyboard.core;

/** One key of the grid. Plain Java, so the layout logic can be tested on the build host. */
public final class Key {
    // Codes for keys that do something other than type their text.
    public static final int CHAR = 0;
    public static final int SHIFT = -1;
    public static final int PAGE = -2;       // letters <-> symbols
    public static final int SPACE = -3;
    public static final int DELETE = -4;
    public static final int ENTER = -5;
    public static final int SWITCH_IME = -6; // go to the other keyboard

    public final String label;
    public final String text;   // what CHAR keys type (lower case for letters)
    public final int code;
    public final float width;   // in key units, a row adds up to Layouts.COLUMNS

    /** Left edge and centre in key units, filled in by Page. */
    float left;
    float center;

    public Key(String label, String text, int code, float width) {
        this.label = label;
        this.text = text;
        this.code = code;
        this.width = width;
    }

    public static Key ch(String s) {
        return new Key(s, s, CHAR, 1f);
    }

    public static Key special(String label, int code, float width) {
        return new Key(label, null, code, width);
    }

    public float left() {
        return left;
    }

    public float center() {
        return center;
    }

    public boolean isLetter() {
        return code == CHAR && text.length() == 1 && Character.isLetter(text.charAt(0));
    }

    /** The text to type, taking shift into account. */
    public String output(boolean shifted) {
        if (code != CHAR) return null;
        return shifted ? text.toUpperCase(java.util.Locale.ROOT) : text;
    }

    @Override
    public String toString() {
        return label;
    }
}
