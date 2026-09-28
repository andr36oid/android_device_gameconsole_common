package org.andr36oid.savebackup;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which game a save file belongs to. RetroArch names saves after the ROM ("Zelda.srm",
 * "Zelda.state1", "Zelda.state.auto"), so the game is the name without those endings.
 * PSP saves are folders named after the game's ID (ULUS10041DATA00) with the title in
 * PARAM.SFO; PPSSPP's save states start with the same ID.
 */
final class Games {

    /** Battery saves and memory cards, found anywhere by their ending. */
    private static final String[] SAVE_ENDINGS = {
        ".srm", ".sav", ".rtc", ".eep", ".sra", ".fla", ".mpk", ".mcd", ".mcr", ".dsv",
        ".ldci", ".ppst",
    };
    /** Save states: .state, .state1 … .state999, .state.auto, and their thumbnails. */
    private static final Pattern STATE =
            Pattern.compile(".*\\.state(\\d+|\\.auto)?(\\.png)?$");
    private static final Pattern PSP_ID = Pattern.compile("[A-Z]{4}\\d{5}");

    private final Map<String, String> mTitles = new HashMap<>();

    static boolean isSaveName(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        for (String e : SAVE_ENDINGS) {
            if (n.endsWith(e) && n.length() > e.length()) return true;
        }
        return STATE.matcher(n).matches();
    }

    /** "Zelda (USA).state.auto" -> "Zelda (USA)" */
    static String baseName(String name) {
        String n = name;
        final String lower = n.toLowerCase(Locale.ROOT);
        if (STATE.matcher(lower).matches()) {
            return n.substring(0, lower.lastIndexOf(".state"));
        }
        final int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** Sets the game key, the label and the folder of a save. */
    void classify(SaveFile s) {
        final String[] seg = s.id.split("/");
        final int n = seg.length;
        s.folder = n >= 3 ? seg[n - 2] : (s.id.startsWith(Roots.EASYROMS) ? "EASYROMS" : "");
        for (int i = 1; i + 1 < n - 1; i++) {
            if (!seg[i].equalsIgnoreCase("PSP")) continue;
            final String kind = seg[i + 1];
            if (kind.equalsIgnoreCase("SAVEDATA") && i + 2 < n) {
                final String dirName = seg[i + 2];
                final String id = pspId(dirName);
                s.key = "psp:" + id;
                String title = null;
                if (i + 3 < n) {
                    File dir = s.file.getParentFile();
                    for (int k = i + 3; k < n - 1 && dir != null; k++) dir = dir.getParentFile();
                    title = dir != null ? title(dir) : null;
                }
                s.label = title != null ? title : id;
                s.folder = "PSP";
                return;
            }
            if (kind.equalsIgnoreCase("PPSSPP_STATE")) {
                final String name = seg[n - 1];
                final int us = name.indexOf('_');
                final String id = pspId(us > 0 ? name.substring(0, us) : baseName(name));
                s.key = "psp:" + id;
                s.label = id;
                s.folder = "PSP";
                return;
            }
        }
        final String base = baseName(seg[n - 1]);
        s.key = "game:" + base.toLowerCase(Locale.ROOT);
        s.label = base;
    }

    /** ULUS10041DATA00 -> ULUS10041; other names stay as they are */
    static String pspId(String name) {
        final String up = name.toUpperCase(Locale.ROOT);
        if (up.length() >= 9 && PSP_ID.matcher(up.substring(0, 9)).matches()) {
            return up.substring(0, 9);
        }
        return name;
    }

    /** The TITLE from a PSP save folder's PARAM.SFO, cached per folder. */
    private String title(File dir) {
        final String key = dir.getPath();
        if (mTitles.containsKey(key)) return mTitles.get(key);
        String t = null;
        File sfo = new File(dir, "PARAM.SFO");
        if (!sfo.isFile()) sfo = new File(dir, "param.sfo");
        if (sfo.isFile() && sfo.length() < 65536) {
            try (InputStream in = new FileInputStream(sfo)) {
                final byte[] b = new byte[(int) sfo.length()];
                int off = 0, r;
                while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
                t = sfoTitle(b);
            } catch (IOException | RuntimeException e) {
                t = null;
            }
        }
        mTitles.put(key, t);
        return t;
    }

    /** The PSF format: a header, a key table and a data table, all little endian. */
    static String sfoTitle(byte[] b) {
        if (b.length < 20 || b[0] != 0 || b[1] != 'P' || b[2] != 'S' || b[3] != 'F') return null;
        final int keys = le32(b, 8), data = le32(b, 12), count = le32(b, 16);
        for (int i = 0; i < count && 20 + i * 16 + 16 <= b.length; i++) {
            final int e = 20 + i * 16;
            final int keyOff = (b[e] & 0xff) | (b[e + 1] & 0xff) << 8;
            final int len = le32(b, e + 4);
            final int dataOff = le32(b, e + 12);
            final int k = keys + keyOff;
            int end = k;
            while (end < b.length && b[end] != 0) end++;
            if (k >= b.length) continue;
            if (!"TITLE".equals(new String(b, k, end - k, StandardCharsets.US_ASCII))) continue;
            final int d = data + dataOff;
            int l = Math.min(len, b.length - d);
            if (d < 0 || l <= 0) return null;
            while (l > 0 && b[d + l - 1] == 0) l--;
            final String title = new String(b, d, l, StandardCharsets.UTF_8)
                    .replace('\n', ' ').replace('\t', ' ').replace('\r', ' ').trim();
            return title.isEmpty() ? null : title;
        }
        return null;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16
                | (b[o + 3] & 0xff) << 24;
    }
}
