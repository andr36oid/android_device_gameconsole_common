package org.andr36oid.keyboard.core;

import java.util.Arrays;
import java.util.List;

/**
 * The key grids. Every page has 5 rows of 10 units: digits, three letter rows, and a bottom
 * row with the page, keyboard switch, space and enter keys. Letters are always in reach of a
 * few d-pad presses; accents are behind a long press of A (see Accents).
 */
public final class Layouts {
    public static final int COLUMNS = 10;

    public static final String QWERTY = "qwerty";
    public static final String QWERTZ = "qwertz";
    public static final String AZERTY = "azerty";
    public static final String AUTO = "auto";

    /** Languages that normally type on QWERTZ. */
    private static final List<String> QWERTZ_LANGS = Arrays.asList(
            "de", "cs", "sk", "hu", "sl", "hr", "sr", "bs", "sq", "lb", "rm");

    private Layouts() {}

    /**
     * The letter layout for a language, as Android reports it (ISO 639 code, may be the old
     * "iw"/"in" forms) and a country (may be empty). Anything that is not QWERTZ or AZERTY,
     * including every non-Latin language, gets QWERTY.
     */
    public static String familyFor(String language, String country) {
        String lang = language == null ? "" : language.toLowerCase(java.util.Locale.ROOT);
        String cc = country == null ? "" : country.toUpperCase(java.util.Locale.ROOT);
        if (lang.equals("fr") && !cc.equals("CA") && !cc.equals("CH")) return AZERTY;
        if (lang.equals("nl") && cc.equals("BE")) return AZERTY;
        if (lang.equals("fr") && cc.equals("CH")) return QWERTZ;
        if (QWERTZ_LANGS.contains(lang)) return QWERTZ;
        return QWERTY;
    }

    /** A saved choice (auto/qwerty/qwertz/azerty) resolved against the language. */
    public static String resolve(String choice, String language, String country) {
        if (QWERTY.equals(choice) || QWERTZ.equals(choice) || AZERTY.equals(choice)) {
            return choice;
        }
        return familyFor(language, country);
    }

    private static Key[] chars(String s) {
        Key[] out = new Key[s.length()];
        for (int i = 0; i < s.length(); i++) out[i] = Key.ch(String.valueOf(s.charAt(i)));
        return out;
    }

    private static Key[] row(Key first, String middle, Key last) {
        Key[] mid = chars(middle);
        Key[] out = new Key[mid.length + 2];
        out[0] = first;
        System.arraycopy(mid, 0, out, 1, mid.length);
        out[out.length - 1] = last;
        return out;
    }

    private static Key[] bottomRow(String pageLabel, String extra) {
        return new Key[] {
            Key.special(pageLabel, Key.PAGE, 1.5f),
            Key.special("⌨", Key.SWITCH_IME, 1f),
            Key.ch(","),
            Key.special("space", Key.SPACE, 3f),
            Key.ch("."),
            Key.ch(extra),
            Key.special("↵", Key.ENTER, 1.5f),
        };
    }

    public static Page letters(String family) {
        String r1, r2, r3;
        if (AZERTY.equals(family)) {
            r1 = "azertyuiop";
            r2 = "qsdfghjklm";
            r3 = "wxcvbn'";
        } else if (QWERTZ.equals(family)) {
            r1 = "qwertzuiop";
            r2 = "asdfghjkl-";
            r3 = "yxcvbnm";
        } else {
            family = QWERTY;
            r1 = "qwertyuiop";
            r2 = "asdfghjkl-";
            r3 = "zxcvbnm";
        }
        return new Page(family,
                chars("1234567890"),
                chars(r1),
                chars(r2),
                row(Key.special("⇧", Key.SHIFT, 1.5f), r3,
                        Key.special("⌫", Key.DELETE, 1.5f)),
                bottomRow("?123", "@"));
    }

    public static Page symbols() {
        return new Page("symbols",
                chars("1234567890"),
                chars("@#$%&*-+=/"),
                chars("!?\"':;_()\\"),
                row(Key.ch("<"), ">[]{}~^",
                        Key.special("⌫", Key.DELETE, 2f)),
                bottomRow("ABC", "|"));
    }
}
