package org.andr36oid.savebackup;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where things are, as root sees them, and how a save's path maps to the id kept in a
 * backup: "internal/RetroArch/saves/Zelda.srm" or "easyroms/gba/Zelda.srm". The ids don't
 * hold the card's UUID, so a backup restores onto a new card too.
 */
final class Roots {

    static final String INTERNAL = "internal";
    static final String EASYROMS = "easyroms";
    static final String USB = "usb";

    /** The internal storage, /data/media/0 (what apps see as /storage/emulated/0). */
    final File media;
    /** /mnt/media_rw/<uuid>, null when EASYROMS isn't mounted. */
    final File easyroms;
    /** Other mounted drives (USB sticks), /mnt/media_rw/<uuid>. */
    final List<File> usb;
    /** RetroArch's and PPSSPP's private data dirs, for their settings files. */
    final File raData;
    final File ppssppData;

    Roots(File media, File easyroms, List<File> usb, File raData, File ppssppData) {
        this.media = media;
        this.easyroms = easyroms;
        this.usb = usb != null ? usb : Collections.<File>emptyList();
        this.raData = raData;
        this.ppssppData = ppssppData;
    }

    File area(String name) {
        if (INTERNAL.equals(name)) return media;
        if (EASYROMS.equals(name)) return easyroms;
        return null;
    }

    /** The file a backup id restores to, null if the id is unsafe or its area isn't there. */
    File resolve(String id) {
        final int slash = id.indexOf('/');
        if (slash <= 0) return null;
        final File root = area(id.substring(0, slash));
        final String rel = id.substring(slash + 1);
        if (root == null || !safeRelative(rel)) return null;
        final File f = new File(root, rel);
        return inside(root, f) ? f : null;
    }

    /** No absolute paths, no "..", nothing that would break a line in our files. */
    static boolean safeRelative(String rel) {
        if (rel.isEmpty() || rel.startsWith("/") || rel.endsWith("/")) return false;
        for (int i = 0; i < rel.length(); i++) {
            final char c = rel.charAt(i);
            if (c < ' ' || c == '\\') return false;
        }
        for (String seg : rel.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) return false;
        }
        return true;
    }

    /** f is below root, also after following links. */
    static boolean inside(File root, File f) {
        try {
            final String r = root.getCanonicalPath();
            return f.getCanonicalPath().startsWith(r.endsWith("/") ? r : r + "/");
        } catch (IOException e) {
            return false;
        }
    }

    /** The backup id of a file found while scanning, null if it's outside our areas. */
    String idOf(File f) {
        final String p = f.getPath();
        String rel = below(media, p);
        if (rel != null) return INTERNAL + "/" + rel;
        rel = easyroms != null ? below(easyroms, p) : null;
        if (rel != null) return EASYROMS + "/" + rel;
        return null;
    }

    private static String below(File root, String p) {
        final String r = root.getPath();
        if (p.length() > r.length() + 1 && p.startsWith(r) && p.charAt(r.length()) == '/') {
            final String rel = p.substring(r.length() + 1);
            return safeRelative(rel) ? rel : null;
        }
        return null;
    }

    /**
     * A folder as an app names it (retroarch.cfg, PPSSPP's memstick_dir.txt), as root sees
     * it. Null if it's somewhere we don't back up.
     */
    File lower(String p) {
        if (p == null) return null;
        p = p.trim();
        if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) {
            p = p.substring(1, p.length() - 1);
        }
        if (p.startsWith("content://")) return fromDocumentUri(p);
        if (p.startsWith("file://")) p = p.substring(7);
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        final String[] internal = {
            "/storage/emulated/0", "/storage/self/primary", "/sdcard", "/mnt/sdcard",
            "/mnt/user/0/primary", "/data/media/0", media.getPath()
        };
        for (String prefix : internal) {
            if (p.equals(prefix)) return media;
            if (p.startsWith(prefix + "/")) return new File(media, p.substring(prefix.length() + 1));
        }
        for (String prefix : new String[] {"/storage/", "/mnt/media_rw/"}) {
            if (!p.startsWith(prefix)) continue;
            final String rest = p.substring(prefix.length());
            final int slash = rest.indexOf('/');
            final String uuid = slash < 0 ? rest : rest.substring(0, slash);
            if (easyroms != null && uuid.equalsIgnoreCase(easyroms.getName())) {
                return slash < 0 ? easyroms : new File(easyroms, rest.substring(slash + 1));
            }
        }
        if (easyroms != null) {
            final String e = easyroms.getPath();
            if (p.equals(e)) return easyroms;
            if (p.startsWith(e + "/")) return new File(easyroms, p.substring(e.length() + 1));
        }
        return null;
    }

    /** content://com.android.externalstorage.documents/tree/primary%3APSP and the like */
    private File fromDocumentUri(String uri) {
        final int tree = uri.indexOf("/tree/");
        if (tree < 0) return null;
        String doc = uri.substring(tree + 6);
        final int end = doc.indexOf('/');
        if (end >= 0) doc = doc.substring(0, end);
        try {
            doc = URLDecoder.decode(doc, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
        final int colon = doc.indexOf(':');
        if (colon < 0) return null;
        final String vol = doc.substring(0, colon);
        final String path = doc.substring(colon + 1);
        if (vol.equals("primary")) return lower("/storage/emulated/0/" + path);
        return lower("/storage/" + vol + "/" + path);
    }

    File internalBackups() {
        return new File(media, "Backups/Saves");
    }

    File easyromsBackups() {
        return easyroms != null ? new File(easyroms, "backups/saves") : null;
    }

    static File usbBackups(File drive) {
        return new File(drive, "Backups/Saves");
    }

    /** Every backup folder that could be there, with where it is. */
    List<Place> places(boolean withUsb) {
        final List<Place> list = new ArrayList<>();
        if (media.isDirectory()) list.add(new Place(INTERNAL, internalBackups()));
        if (easyroms != null && easyroms.isDirectory()) {
            list.add(new Place(EASYROMS, easyromsBackups()));
        }
        if (withUsb) {
            for (File u : usb) {
                if (u.isDirectory()) list.add(new Place(USB, usbBackups(u)));
            }
        }
        return list;
    }

    /** A backup folder: internal, easyroms or usb. */
    static final class Place {
        final String where;
        final File dir;

        Place(String where, File dir) {
            this.where = where;
            this.dir = dir;
        }
    }
}
