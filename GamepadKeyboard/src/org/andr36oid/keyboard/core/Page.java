package org.andr36oid.keyboard.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A page of keys (letters or symbols): rows of keys, each row Layouts.COLUMNS units wide. */
public final class Page {
    public final String name;
    private final List<List<Key>> rows = new ArrayList<>();

    public Page(String name, Key[]... keyRows) {
        this.name = name;
        for (Key[] r : keyRows) {
            List<Key> row = new ArrayList<>();
            float x = 0;
            for (Key k : r) {
                k.left = x;
                k.center = x + k.width / 2f;
                x += k.width;
                row.add(k);
            }
            rows.add(Collections.unmodifiableList(row));
        }
    }

    public int rowCount() {
        return rows.size();
    }

    public List<Key> row(int r) {
        return rows.get(r);
    }

    public Key key(int r, int c) {
        return rows.get(r).get(c);
    }

    public float rowWidth(int r) {
        float w = 0;
        for (Key k : rows.get(r)) w += k.width;
        return w;
    }

    /** Index of the key in row r whose span holds x (or the nearest centre). */
    public int columnAt(int r, float x) {
        List<Key> row = rows.get(r);
        for (int c = 0; c < row.size(); c++) {
            Key k = row.get(c);
            if (x >= k.left && x < k.left + k.width) return c;
        }
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int c = 0; c < row.size(); c++) {
            float d = Math.abs(row.get(c).center - x);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    /** Where to find the first key with this code, as {row, col}, or null. */
    public int[] find(int code) {
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).size(); c++) {
                if (rows.get(r).get(c).code == code) return new int[] {r, c};
            }
        }
        return null;
    }
}
