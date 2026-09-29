package org.andr36oid.touchmapper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Host test of SharedLayouts (tests/run-tests.sh). Args: temp dir, Inputs.java path. */
public class SharedLayoutsTest {

    static int failures;
    static int checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void write(File dir, String name, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    static Set<String> arrayInSource(String src, String name) {
        final Matcher m = Pattern.compile(name + "\\s*=\\s*\\{([^}]*)\\}").matcher(src);
        final Set<String> out = new HashSet<>();
        if (m.find()) {
            final Matcher q = Pattern.compile("\"([A-Z0-9]+)\"").matcher(m.group(1));
            while (q.find()) out.add(q.group(1));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        final File tmp = new File(args[0]);
        final String inputs = new String(Files.readAllBytes(new File(args[1]).toPath()),
                StandardCharsets.UTF_8);

        // Our lists match the editor's
        check(SharedLayouts.BUTTONS.equals(arrayInSource(inputs, "BUTTONS")), "BUTTONS match Inputs");
        check(SharedLayouts.SHIFT_BUTTONS.equals(arrayInSource(inputs, "SHIFT_BUTTONS")),
                "SHIFT_BUTTONS match Inputs");

        // A layout the editor writes comes back the same
        final Profile p = new Profile("com.example.game");
        p.shift = "L2";
        p.hints = false;
        p.deadzone = 20;
        final Profile.Control a = new Profile.Control();
        a.type = Profile.TAP; a.button = "A"; a.x = 0.88f; a.y = 0.8f;
        final Profile.Control j = new Profile.Control();
        j.type = Profile.JOYSTICK; j.stick = "left"; j.x = 0.17f; j.y = 0.72f; j.radius = 0.14f;
        final Profile.Control s = new Profile.Control();
        s.type = Profile.SWIPE; s.button = "Y"; s.angle = 90; s.layer = 1;
        p.controls.addAll(Arrays.asList(a, j, s));
        final String json = p.toJson();
        final Profile back = SharedLayouts.parse("whatever.json", json);
        check(back != null && json.equals(back.toJson()), "round trip keeps the layout");

        // Package: from the file name when missing; bad ones refused
        final Profile named = SharedLayouts.parse("com.foo.bar.json",
                "{\"controls\":[{\"type\":\"tap\",\"button\":\"B\"}]}");
        check(named != null && "com.foo.bar".equals(named.packageName), "package from file name");
        check(SharedLayouts.parse("x.json", "{\"package\":\"../../etc\"}") == null, "path package refused");
        check(SharedLayouts.parse("nodots.json", "{}") == null, "package without a dot refused");
        check(SharedLayouts.parse("a.b.json", "{\"package\":\"1a.b\"}") == null, "package starting with a digit");
        check(SharedLayouts.parse("a.b.json", "[1,2]") == null, "array refused");
        check(SharedLayouts.parse("a.b.json", "not json") == null, "garbage refused");
        check(SharedLayouts.parse("a.b.json", null) == null, "missing text refused");
        check(SharedLayouts.parse("a.b.json", "{\"version\":2}") == null, "newer version refused");
        final Profile empty = SharedLayouts.parse("a.b.json", "{}");
        check(empty != null && empty.controls.isEmpty(), "empty layout is fine");

        // Controls we don't understand are dropped, the rest kept
        final Profile mixed = SharedLayouts.parse("a.b.json", "{\"shift\":\"R2\",\"controls\":["
                + "{\"type\":\"tap\",\"button\":\"A\"},"
                + "{\"type\":\"tap\",\"button\":\"FN\"},"          // not a control button
                + "{\"type\":\"laser\",\"button\":\"B\"},"         // unknown kind
                + "{\"type\":\"tap\",\"button\":\"A\"},"           // A twice on layer 0
                + "{\"type\":\"tap\",\"button\":\"A\",\"layer\":1},"  // fine on layer 1
                + "{\"type\":\"hold\",\"button\":\"R2\"},"         // the shift button
                + "{\"type\":\"joystick\",\"stick\":\"middle\"},"  // unknown stick
                + "{\"type\":\"joystick\",\"stick\":\"dpad\"},"
                + "{\"type\":\"tap\",\"button\":\"UP\"},"          // clashes with the D-pad joystick
                + "{\"type\":\"camera\",\"stick\":\"right\"},"
                + "{\"type\":\"swipe\"}"                           // no button
                + "]}");
        check(mixed != null && mixed.controls.size() == 4, "4 of 11 controls kept, got "
                + (mixed == null ? "null" : mixed.controls.size()));
        check(mixed != null && "R2".equals(mixed.shift), "shift kept");
        final Profile badShift = SharedLayouts.parse("a.b.json", "{\"shift\":\"A\",\"controls\":["
                + "{\"type\":\"tap\",\"button\":\"A\"},{\"type\":\"tap\",\"button\":\"B\",\"layer\":1}]}");
        check(badShift != null && badShift.shift == null && badShift.controls.size() == 1,
                "bad shift dropped, and with it the shift layer");
        check(SharedLayouts.parse("a.b.json", "{\"controls\":[{\"type\":\"x\"}]}") == null,
                "nothing usable = file refused");
        final Profile clamp = SharedLayouts.parse("a.b.json",
                "{\"controls\":[{\"type\":\"tap\",\"button\":\"A\",\"x\":7,\"y\":-3}]}");
        check(clamp != null && clamp.controls.get(0).x == 1f && clamp.controls.get(0).y == 0f,
                "positions clamped to the screen");

        // A folder: new, kept, bad, BOM, too big, not json
        final File dir = new File(tmp, "in");
        dir.mkdirs();
        write(dir, "com.new.one.json", "﻿{\"controls\":[{\"type\":\"tap\",\"button\":\"A\"}]}");
        write(dir, "com.have.one.json", "{}");
        write(dir, "broken.json", "{");
        write(dir, "readme.txt", "hello");
        write(dir, "renamed.JSON", "{\"package\":\"com.new.two\"}");
        write(dir, "copy.json", "{\"package\":\"com.new.two\"}");  // same app twice
        write(dir, "com.blocked.app.json", "{}");
        final StringBuilder big = new StringBuilder("{\"package\":\"com.big.one\",\"x\":\"");
        while (big.length() < SharedLayouts.MAX_BYTES + 10) big.append("xxxxxxxxxx");
        write(dir, "big.json", big.append("\"}").toString());
        final SharedLayouts.Result r = SharedLayouts.read(dir, Arrays.asList("com.have.one"),
                pkg -> !pkg.equals("com.blocked.app"));
        check(r.added.size() == 2, "2 added, got " + r.added.size());
        check(r.kept == 2, "2 kept (own layout, second copy), got " + r.kept);
        check(r.bad == 3, "3 bad (broken, blocked, too big), got " + r.bad);
        check(!r.added.isEmpty() && "com.new.one".equals(r.added.get(0).packageName)
                && r.added.get(0).controls.size() == 1, "byte order mark skipped");
        final SharedLayouts.Result none = SharedLayouts.read(new File(tmp, "missing"),
                Arrays.asList(), null);
        check(none.added.isEmpty() && none.bad == 0, "missing folder is empty");

        System.out.println((failures == 0 ? "PASS" : "FAILED") + " SharedLayouts: "
                + (checks - failures) + "/" + checks);
        System.exit(failures == 0 ? 0 : 1);
    }
}
