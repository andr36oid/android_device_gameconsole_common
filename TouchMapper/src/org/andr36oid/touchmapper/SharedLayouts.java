package org.andr36oid.touchmapper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks layout files that come from the SD card (EASYROMS/touchmap, copied into a private
 * folder by the root helper) before they are added. Plain Java with org.json only, so the
 * host test can run it (tests/run-tests.sh).
 *
 * A file is used when it is a JSON object of a version we know, and it names a valid package
 * ("package", or else the file name). Controls we don't understand are left out, and so is a
 * second control for the same button on the same layer. Apps that already have a layout keep
 * theirs: to take the card's one, delete yours first.
 */
final class SharedLayouts {

    /** The same names as {@link Inputs#BUTTONS}; the host test compares both lists. */
    static final Set<String> BUTTONS = new HashSet<>(Arrays.asList(
            "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3", "SELECT", "START",
            "UP", "DOWN", "LEFT", "RIGHT"));
    /** The same names as {@link Inputs#SHIFT_BUTTONS}. */
    static final Set<String> SHIFT_BUTTONS = new HashSet<>(Arrays.asList(
            "L1", "R1", "L2", "R2", "L3", "R3", "SELECT"));
    static final Set<String> STICKS = new HashSet<>(Arrays.asList(
            Profile.STICK_LEFT, Profile.STICK_RIGHT, Profile.STICK_DPAD));

    static final int MAX_BYTES = 65536;
    private static final int MAX_CONTROLS = 64;
    private static final Pattern PACKAGE =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    /** What an import found. */
    static final class Result {
        /** New layouts, one per app, ready to save. */
        final List<Profile> added = new ArrayList<>();
        /** Apps that already had a layout and keep it. */
        int kept;
        /** Files that couldn't be used. */
        int bad;
    }

    private SharedLayouts() {
    }

    /**
     * Reads every *.json file in {@code dir}. {@code existing} are the apps with a layout
     * already; {@code allowed} says which packages may get one (null: any).
     */
    static Result read(File dir, Collection<String> existing, PackageFilter allowed) {
        final Result r = new Result();
        final Set<String> taken = new HashSet<>(existing);
        final String[] names = dir.list();
        if (names == null) {
            return r;
        }
        Arrays.sort(names);
        for (String name : names) {
            if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
                continue;
            }
            final Profile p = parse(name, readFile(new File(dir, name)));
            if (p == null || (allowed != null && !allowed.allowed(p.packageName))) {
                r.bad++;
            } else if (taken.contains(p.packageName)) {
                r.kept++;
            } else {
                taken.add(p.packageName);
                r.added.add(p);
            }
        }
        return r;
    }

    interface PackageFilter {
        boolean allowed(String packageName);
    }

    /** The layout in one file, cleaned up, or null if it can't be used. */
    static Profile parse(String fileName, String json) {
        if (json == null) {
            return null;
        }
        try {
            final JSONObject o = new JSONObject(json);
            if (o.optInt("version", Profile.VERSION) > Profile.VERSION) {
                return null;
            }
            String pkg = o.optString("package", "");
            if (pkg.isEmpty() && fileName != null) {
                final int dot = fileName.length() - ".json".length();
                pkg = dot > 0 ? fileName.substring(0, dot) : "";
            }
            if (pkg.length() > 255 || !PACKAGE.matcher(pkg).matches()) {
                return null;
            }
            final Profile in = Profile.fromJson(pkg, json);
            final Profile out = new Profile(pkg);
            out.enabled = in.enabled;
            out.hints = in.hints;
            out.shift = SHIFT_BUTTONS.contains(in.shift) ? in.shift : null;
            out.deadzone = in.deadzone;
            final Set<String> used = new HashSet<>();
            for (Profile.Control c : in.controls) {
                if (out.controls.size() >= MAX_CONTROLS || !usable(c, out.shift)) {
                    continue;
                }
                if (c.layer == 1 && out.shift == null) {
                    continue;
                }
                // The D-pad joystick and the single D-pad buttons share the same buttons
                final List<String> inputs = Profile.STICK_DPAD.equals(c.input())
                        ? Arrays.asList("UP", "DOWN", "LEFT", "RIGHT") : Arrays.asList(c.input());
                boolean clash = false;
                for (String input : inputs) {
                    clash |= used.contains(c.layer + ":" + input);
                }
                if (clash) {
                    continue;
                }
                for (String input : inputs) {
                    used.add(c.layer + ":" + input);
                }
                out.controls.add(c);
            }
            if (out.controls.isEmpty() && !in.controls.isEmpty()) {
                return null;  // nothing in it made sense
            }
            return out;
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    private static boolean usable(Profile.Control c, String shift) {
        if (c.type == null) {
            return false;
        }
        switch (c.type) {
            case Profile.TAP:
            case Profile.HOLD:
            case Profile.SWIPE:
                return c.button != null && BUTTONS.contains(c.button) && !c.button.equals(shift);
            case Profile.JOYSTICK:
            case Profile.CAMERA:
                return c.stick != null && STICKS.contains(c.stick);
            default:
                return false;
        }
    }

    /** The file as UTF-8 text, or null if it's missing or too big. */
    static String readFile(File f) {
        if (!f.isFile() || f.length() > MAX_BYTES) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) {
                    return null;
                }
            }
            String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (s.startsWith("﻿")) {
                s = s.substring(1);  // Windows Notepad's byte order mark
            }
            return s;
        } catch (IOException e) {
            return null;
        }
    }
}
