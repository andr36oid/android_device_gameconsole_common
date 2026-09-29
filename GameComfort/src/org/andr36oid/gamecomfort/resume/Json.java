package org.andr36oid.gamecomfort.resume;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for RetroArch's playlists: objects become maps, arrays lists, numbers
 * doubles. Plain Java so the tests run on a PC (org.json is only in the Android framework).
 */
final class Json {

    private final String mText;
    private int mPos;

    private Json(String text) {
        mText = text;
    }

    /** The value in {@code text}, or throws IllegalArgumentException if it isn't JSON. */
    static Object parse(String text) {
        final Json json = new Json(text);
        json.skipSpace();
        final Object value = json.value();
        json.skipSpace();
        if (json.mPos != text.length()) {
            throw json.error("text after the end");
        }
        return value;
    }

    private Object value() {
        if (mPos >= mText.length()) {
            throw error("unexpected end");
        }
        final char c = mText.charAt(mPos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return word("true", Boolean.TRUE);
            case 'f':
                return word("false", Boolean.FALSE);
            case 'n':
                return word("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        final Map<String, Object> map = new LinkedHashMap<>();
        mPos++;
        skipSpace();
        if (peek() == '}') {
            mPos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("expected a name");
            }
            final String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            map.put(key, value());
            skipSpace();
            final char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected , or }");
            }
        }
    }

    private List<Object> array() {
        final List<Object> list = new ArrayList<>();
        mPos++;
        skipSpace();
        if (peek() == ']') {
            mPos++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value());
            skipSpace();
            final char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected , or ]");
            }
        }
    }

    private String string() {
        expect('"');
        final StringBuilder sb = new StringBuilder();
        while (true) {
            final char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            final char e = next();
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    sb.append(e);
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (mPos + 4 > mText.length()) {
                        throw error("short \\u escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(mText.substring(mPos, mPos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    mPos += 4;
                    break;
                default:
                    throw error("bad escape");
            }
        }
    }

    private Double number() {
        final int start = mPos;
        while (mPos < mText.length() && "+-0123456789.eE".indexOf(mText.charAt(mPos)) >= 0) {
            mPos++;
        }
        try {
            return Double.valueOf(mText.substring(start, mPos));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private Object word(String word, Object value) {
        if (!mText.startsWith(word, mPos)) {
            throw error("unexpected word");
        }
        mPos += word.length();
        return value;
    }

    private void skipSpace() {
        while (mPos < mText.length()) {
            final char c = mText.charAt(mPos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != '﻿') {
                return;
            }
            mPos++;
        }
    }

    private char peek() {
        return mPos < mText.length() ? mText.charAt(mPos) : '\0';
    }

    private char next() {
        if (mPos >= mText.length()) {
            throw error("unexpected end");
        }
        return mText.charAt(mPos++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("expected '" + c + "'");
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at " + mPos);
    }
}
