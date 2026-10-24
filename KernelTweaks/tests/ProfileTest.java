package org.andr36oid.kerneltweaks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ProfileTest {

    private static int passed;
    private static final List<Tweak> TWEAKS = new ArrayList<>();
    private static final String HEADER = "format=andr36oid-ktprofile-1\n";
    private static final String PREFIX = "persist.andr36oid.kt.";

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        passed++;
    }

    private static void reject(String text) throws Exception {
        try {
            Profile.parse(text, TWEAKS);
            throw new AssertionError("accepted invalid file");
        } catch (IOException expected) {
            passed++;
        }
    }

    public static void main(String[] args) throws Exception {
        for (Tweaks.Category category : Tweaks.all()) TWEAKS.addAll(category.tweaks);
        for (Tweak t : TWEAKS) {
            if (t.once) continue;
            // STICKS keeps per-side options in values, not whole "left right" values
            if (t.kind != Tweak.STICKS) {
                for (String value : t.values) {
                    final Profile p = Profile.parse(HEADER + PREFIX + t.key + "=" + value, TWEAKS);
                    check(value.equals(p.values.get(t)), "choice " + t.key + "=" + value);
                }
            }
            final Profile bad = Profile.parse(HEADER + PREFIX + t.key + "=unexpected", TWEAKS);
            check(bad.values.isEmpty() && bad.invalid.size() == 1, "invalid " + t.key);
            final Profile empty = Profile.parse(HEADER + PREFIX + t.key + "=", TWEAKS);
            check(empty.values.isEmpty() && empty.invalid.size() == 1, "empty " + t.key);
        }
        final Profile axes = Profile.parse(HEADER + PREFIX + "stick_invert=1\t0  1 0\r\n", TWEAKS);
        check(axes.values.containsValue("1 0 1 0"), "axes normalized");
        for (int flags = 0; flags < 16; flags++) {
            final String value = Tweaks.axesValue(new boolean[] {(flags & 1) != 0,
                    (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0});
            check(Profile.parse(HEADER + PREFIX + "stick_invert=" + value, TWEAKS)
                    .values.containsValue(value), "axes flags " + flags);
        }
        for (String value : new String[] {"1 2 0 0", "x 0 0 0", "1 0 0", "1 0 0 0 0"}) {
            check(Profile.parse(HEADER + PREFIX + "stick_invert=" + value, TWEAKS)
                    .invalid.size() == 1, "bad axes " + value);
        }
        final Profile sens = Profile.parse(HEADER + PREFIX + "stick_sensitivity=80\t100\r\n",
                TWEAKS);
        check(sens.values.containsValue("80 100"), "sticks normalized");
        for (String value : new String[] {"100 100", "10 10", "50 70"}) {
            check(Profile.parse(HEADER + PREFIX + "stick_sensitivity=" + value, TWEAKS)
                    .values.containsValue(value), "sticks " + value);
        }
        for (String value : new String[] {"100", "5 100", "100 200", "x 50", "80 80 80", "0 0"}) {
            check(Profile.parse(HEADER + PREFIX + "stick_sensitivity=" + value, TWEAKS)
                    .invalid.size() == 1, "bad sticks " + value);
        }
        final Profile ignored = Profile.parse(HEADER + PREFIX + "paused=1\n" + PREFIX
                + "tries=2\n" + PREFIX + "sleep_test=core\n" + PREFIX
                + "new_knob=1\nsys.andr36oid.kt.swappiness=30\n", TWEAKS);
        check(ignored.values.isEmpty() && ignored.unknown.size() == 5, "unknown and temporary");
        final Profile partial = Profile.parse(HEADER + PREFIX + "swappiness=30\n" + PREFIX
                + "cores=0\nfuture.key=value\n", TWEAKS);
        check(partial.values.size() == 1 && partial.invalid.size() == 1
                && partial.unknown.size() == 1, "invalid entries do not hide valid choices");
        reject(PREFIX + "swappiness=30");
        reject("format=unknown");
        reject(HEADER + HEADER);
        reject(HEADER + PREFIX + "cores=2\n" + PREFIX + "cores=3");
        reject(HEADER + "not a setting");
        reject(HEADER + "bad key=value");
        reject(HEADER + "model=bad\0model");
        reject(HEADER + new String(new char[Profile.MAX_BYTES]).replace('\0', 'a'));

        final Map<String, String> console = new HashMap<>();
        console.put("model", "Example clone");
        console.put("compatible", "example,clone rockchip,rk3326");
        final Profile matched = Profile.parse(HEADER + "model=Example clone\n"
                + "compatible=example,clone rockchip,rk3326\n"
                + "match.model=Example clone\nmatch.compatible=example,clone\n", TWEAKS);
        check(matched.recommended(console), "model and compatible match");
        check(matched.differences(console).isEmpty(), "same identity");
        matched.matchCompatible = "example,clon";
        check(!matched.recommended(console), "compatible must match whole token");
        matched.matchCompatible = "";
        check(matched.recommended(console), "model alone");
        matched.matchModel = "";
        check(!matched.recommended(console), "no selector never recommended");
        matched.matchCompatible = "example,clone";
        check(matched.recommended(console), "compatible alone");
        matched.matchModel = "Another clone";
        check(!matched.recommended(console), "both selectors must match");
        matched.identity.put("version", "other version");
        matched.identity.put("panel", "other panel");
        check(matched.differences(console).contains("version")
                && matched.differences(console).contains("panel"), "version and panel warning");
        check(Profile.parse(HEADER, TWEAKS).differences(console).contains("model"),
                "missing identity warning");
        if (args.length > 0) {
            final Profile exported = Profile.parse(new String(Files.readAllBytes(
                    Paths.get(args[0])), StandardCharsets.UTF_8), TWEAKS);
            check(exported.values.size() == 3 && exported.invalid.isEmpty(), "helper round trip");
            check(exported.unknown.size() == 1 && exported.unknown.get(0).endsWith("extra_rates"),
                    "other settings page knob reported on import");
            check("Experimental/EE-V12-Var1-Panel8".equals(exported.identity.get("profile")),
                    "BOOT profile round trip");
        }
        System.out.println("profile Java tests passed " + passed);
    }
}
