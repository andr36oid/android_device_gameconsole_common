package org.andr36oid.keyboard.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Long-press A on a key to get its accented forms and related symbols. The set covers the
 * Latin-script languages Android offers; the current language's own letters come first.
 */
public final class Accents {
    private static final Map<String, String> VARIANTS = new HashMap<>();
    private static final Map<String, String> PREFERRED = new HashMap<>();

    static {
        VARIANTS.put("a", "àáâäæãåāąă");
        VARIANTS.put("c", "çćč");
        VARIANTS.put("d", "ďđð");
        VARIANTS.put("e", "èéêëēęěė");
        VARIANTS.put("g", "ğ");
        VARIANTS.put("i", "ìíîïīįı");
        VARIANTS.put("l", "łľĺ");
        VARIANTS.put("n", "ñńň");
        VARIANTS.put("o", "òóôöõøœōő");
        VARIANTS.put("r", "řŕ");
        VARIANTS.put("s", "ßśšşș");
        VARIANTS.put("t", "ťţțþ");
        VARIANTS.put("u", "ùúûüūůűų");
        VARIANTS.put("y", "ýÿ");
        VARIANTS.put("z", "źżž");
        VARIANTS.put("$", "€£¥¢₹");
        VARIANTS.put("?", "¿");
        VARIANTS.put("!", "¡");
        VARIANTS.put("-", "–—·");
        VARIANTS.put("'", "`´‘’");
        VARIANTS.put("\"", "«»„“”");
        VARIANTS.put("*", "•°§");
        VARIANTS.put("%", "‰");
        VARIANTS.put(".", "…");
        VARIANTS.put("=", "≠±");
        VARIANTS.put("<", "≤«");
        VARIANTS.put(">", "≥»");
        VARIANTS.put("/", "÷");

        PREFERRED.put("fr", "éèàçêùœâîôû");
        PREFERRED.put("de", "äöüß");
        PREFERRED.put("es", "ñáéíóúü");
        PREFERRED.put("ca", "àçèéíïòóúü");
        PREFERRED.put("pt", "ãõçáéíóúâêô");
        PREFERRED.put("it", "àèéìòù");
        PREFERRED.put("pl", "ąćęłńóśźż");
        PREFERRED.put("cs", "áčďéěíňóřšťúůýž");
        PREFERRED.put("sk", "áäčďéíĺľňóôŕšťúýž");
        PREFERRED.put("tr", "çğıöşü");
        PREFERRED.put("ro", "ăâîșț");
        PREFERRED.put("nl", "ëïéè");
        PREFERRED.put("sv", "åäö");
        PREFERRED.put("fi", "äöå");
        PREFERRED.put("da", "æøå");
        PREFERRED.put("nb", "æøå");
        PREFERRED.put("hu", "áéíóöőúüű");
        PREFERRED.put("hr", "čćđšž");
        PREFERRED.put("vi", "àáâãèéêìíòóôõùúýđ");
    }

    private Accents() {}

    public static boolean has(String base) {
        return base != null && VARIANTS.containsKey(base);
    }

    /**
     * The choices for a key, the plain key first, then the language's own letters, then the
     * rest. Shifted letters come out in upper case (except ß, which has no everyday capital).
     */
    public static List<String> variants(String base, String language, boolean shifted) {
        List<String> out = new ArrayList<>();
        if (base == null) return out;
        out.add(base);
        String all = VARIANTS.get(base);
        if (all == null) return shift(out, shifted);
        String pref = language == null ? null : PREFERRED.get(language.toLowerCase(Locale.ROOT));
        if (pref != null) {
            for (int i = 0; i < pref.length(); i++) {
                String c = String.valueOf(pref.charAt(i));
                if (all.contains(c) && !out.contains(c)) out.add(c);
            }
        }
        for (int i = 0; i < all.length(); i++) {
            String c = String.valueOf(all.charAt(i));
            if (!out.contains(c)) out.add(c);
        }
        return shift(out, shifted);
    }

    private static List<String> shift(List<String> list, boolean shifted) {
        if (!shifted) return list;
        List<String> out = new ArrayList<>();
        for (String s : list) {
            String up = s.toUpperCase(Locale.ROOT);
            out.add(up.length() == s.length() ? up : s);
        }
        return out;
    }
}
