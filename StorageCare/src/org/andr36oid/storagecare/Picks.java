package org.andr36oid.storagecare;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The last scan and what the user picked from it, shared by the main page and the review
 * pages while the app is open. Old states and RetroArch's cache start picked. Old save
 * backups start unpicked (Save backup keeps its own number of them as a safety net), and so
 * do box art, screenshots, APKs and duplicates, since those may be wanted.
 */
final class Picks {

    private Picks() {
    }

    static final Set<String> PICKED_AT_START =
            new HashSet<>(Arrays.asList(Scan.STATES, Scan.RACACHE));

    static Scan scan;
    static final Set<String> picked = new HashSet<>();
    /** App caches (not files the helper removes: PackageManager clears them). */
    static boolean caches = true;
    static long cacheBytes = -1;

    static void take(Scan s) {
        scan = s;
        picked.clear();
        for (Scan.Item i : s.items) {
            if (PICKED_AT_START.contains(i.group)) picked.add(i.path);
        }
        caches = true;
    }

    static void clear() {
        scan = null;
        picked.clear();
        cacheBytes = -1;
    }

    static List<Scan.Item> pickedItems() {
        final List<Scan.Item> l = new ArrayList<>();
        if (scan == null) return l;
        for (Scan.Item i : scan.items) {
            if (picked.contains(i.path)) l.add(i);
        }
        return l;
    }

    /** {files, bytes} picked in a group, or in all groups if group is null. */
    static long[] count(String group) {
        final long[] n = new long[2];
        if (scan == null) return n;
        for (Scan.Item i : scan.items) {
            if ((group == null || i.group.equals(group)) && picked.contains(i.path)) {
                n[0]++;
                n[1] += i.bytes;
            }
        }
        return n;
    }

    static long total(String group) {
        long n = 0;
        if (scan == null) return n;
        for (Scan.Item i : scan.items) {
            if (i.group.equals(group)) n += i.bytes;
        }
        return n;
    }
}
