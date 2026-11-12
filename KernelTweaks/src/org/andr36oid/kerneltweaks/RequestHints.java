package org.andr36oid.kerneltweaks;

/** Advisory only: the root helper also checks generation and all saved values. */
final class RequestHints {
    static String singleKey(String key, String pending, String done) {
        if (key == null || !key.matches("[a-z][a-z0-9_]*") || !pending.equals(done)) return "";
        return key;
    }
}
