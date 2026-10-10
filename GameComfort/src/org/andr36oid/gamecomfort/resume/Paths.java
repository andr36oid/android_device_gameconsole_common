package org.andr36oid.gamecomfort.resume;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * Paths as apps name them (/storage/emulated/0/..., /storage/1234-ABCD/..., content:// links
 * from the Android file picker) and as root sees them (/data/media/0/...,
 * /mnt/media_rw/1234-ABCD/...). The root helper only reads root paths.
 */
public final class Paths {

    /** The internal storage as root sees it. */
    public static final String MEDIA = "/data/media/0";
    /** The internal storage as apps see it. */
    public static final String STORAGE = "/storage/emulated/0";

    private static final String[] INTERNAL = {
        STORAGE, "/storage/self/primary", "/sdcard", "/mnt/sdcard", "/mnt/user/0/primary",
        MEDIA,
    };

    private Paths() {
    }

    /**
     * The root path for an app path, or null if it is nowhere the helper may read (the
     * internal storage, SD cards and USB drives, RetroArch's own data folder).
     */
    public static String toRoot(String p) {
        if (p == null) {
            return null;
        }
        p = p.trim();
        if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
            p = p.substring(1, p.length() - 1);
        }
        if (p.startsWith("content://")) {
            return fromDocumentUri(p);
        }
        if (p.startsWith("file://")) {
            p = decode(p.substring(7));
            if (p == null) {
                return null;
            }
        }
        if (!p.startsWith("/") || p.indexOf('\n') >= 0 || p.indexOf('\t') >= 0) {
            return null;
        }
        p = trimSlashes(p);
        if (hasDotDot(p)) {
            return null;
        }
        for (String prefix : INTERNAL) {
            if (p.equals(prefix)) {
                return MEDIA;
            }
            if (p.startsWith(prefix + "/")) {
                return MEDIA + p.substring(prefix.length());
            }
        }
        if (p.startsWith("/storage/") && p.length() > 9) {
            // /storage/<uuid>/... is /mnt/media_rw/<uuid>/...
            return "/mnt/media_rw/" + p.substring(9);
        }
        if (p.startsWith("/mnt/media_rw/")) {
            return p;
        }
        if (p.startsWith("/data/data/com.retroarch/") || p.equals("/data/data/com.retroarch")) {
            return "/data/user/0/com.retroarch" + p.substring("/data/data/com.retroarch".length());
        }
        if (p.startsWith("/data/user/0/com.retroarch/") || p.equals("/data/user/0/com.retroarch")) {
            return p;
        }
        return null;
    }

    /** An app path for a root path from {@link #toRoot}. */
    public static String toApp(String root) {
        if (root.equals(MEDIA)) {
            return STORAGE;
        }
        if (root.startsWith(MEDIA + "/")) {
            return STORAGE + root.substring(MEDIA.length());
        }
        if (root.startsWith("/mnt/media_rw/")) {
            return "/storage/" + root.substring("/mnt/media_rw/".length());
        }
        return root;
    }

    /**
     * content://com.android.externalstorage.documents/tree/primary%3APSP (a folder) or
     * .../document/primary%3APSP%2FGAME%2Fx.iso (a file) as a root path.
     */
    static String fromDocumentUri(String uri) {
        if (!uri.startsWith("content://com.android.externalstorage.documents/")) {
            return null;
        }
        String doc = null;
        final int document = uri.indexOf("/document/");
        if (document >= 0) {
            doc = uri.substring(document + 10);
        } else {
            final int tree = uri.indexOf("/tree/");
            if (tree >= 0) {
                doc = uri.substring(tree + 6);
            }
        }
        if (doc == null) {
            return null;
        }
        final int slash = doc.indexOf('/');
        if (slash >= 0) {
            doc = doc.substring(0, slash);
        }
        doc = decode(doc);
        if (doc == null) {
            return null;
        }
        final int colon = doc.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        final String volume = doc.substring(0, colon);
        final String path = doc.substring(colon + 1);
        if (volume.indexOf('/') >= 0) {
            return null;
        }
        if (volume.equals("primary")) {
            return toRoot(STORAGE + "/" + path);
        }
        return toRoot("/storage/" + volume + "/" + path);
    }

    /** The folder part of a root path, null for none. */
    public static String parent(String p) {
        final int slash = p.lastIndexOf('/');
        return slash > 0 ? p.substring(0, slash) : null;
    }

    /** The last part of a path, a content link or an archive entry ("a.zip#b.gba"). */
    public static String name(String p) {
        if (p.startsWith("content://")) {
            final String decoded = decode(p);
            if (decoded != null) {
                p = decoded;
            }
        }
        p = trimSlashes(p);
        final int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf(':'));
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** A file name without its ending: "Tetris (World).gb" is "Tetris (World)". */
    public static String withoutExtension(String name) {
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String trimSlashes(String p) {
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static boolean hasDotDot(String p) {
        for (String part : p.split("/")) {
            if (part.equals("..") || part.equals(".")) {
                return true;
            }
        }
        return false;
    }

    private static String decode(String s) {
        try {
            // URLDecoder would turn '+' into a space, which file names keep as it is
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }
}
