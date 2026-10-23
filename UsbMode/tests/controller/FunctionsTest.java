package org.andr36oid.usbmode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class FunctionsTest {
    private static Set<Integer> masks(Integer... masks) {
        return new LinkedHashSet<>(Arrays.asList(masks));
    }
    private static void reason(UsbFunctions functions, int mask, String expected) {
        if (!expected.equals(functions.reason(mask))) throw new AssertionError(mask + ": " + functions.reason(mask));
    }
    private static int calls(Path dir) throws IOException {
        return Files.exists(dir.resolve("calls")) ? Files.readAllLines(dir.resolve("calls")).size() : 0;
    }
    private static void protocol(String helper, String input, int status, String expected) throws Exception {
        final Process process = new ProcessBuilder(helper, "--reasons").start();
        try {
            process.getOutputStream().write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().close();
            final String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() != status || !expected.equals(output)) throw new AssertionError("Batch protocol: " + output);
        } finally { process.destroy(); }
    }
    public static void main(String[] args) throws Exception {
        final UsbFunctions functions = new UsbFunctions(args[0]);
        final Set<Integer> requested = masks(0, UsbFunctions.MIDI,
                UsbFunctions.NETWORK | UsbFunctions.SERIAL | UsbFunctions.STORAGE | UsbFunctions.ADB,
                UsbFunctions.SWITCH | UsbFunctions.KEYBOARD, UsbFunctions.MEDIA | UsbFunctions.MIDI,
                UsbFunctions.NETWORK | UsbFunctions.RNDIS, UsbFunctions.SOUNDBOARD,
                UsbFunctions.SOUND | UsbFunctions.SOUNDBOARD,
                UsbFunctions.SOUND | UsbFunctions.SOUNDBOARD | UsbFunctions.KEYBOARD,
                UsbFunctions.FLIPPER, UsbFunctions.FLIPPER | UsbFunctions.STORAGE);
        for (int mask = 1; mask < 64; mask++) requested.add(mask);
        functions.query(requested);
        int valid = 0;
        for (int mask = 1; mask < 64; mask++) {
            if ("ok".equals(functions.reason(mask))) valid++;
            else reason(functions, mask, "controllers");
        }
        if (valid != 47) throw new AssertionError(valid);
        reason(functions, 0, "empty");
        reason(functions, UsbFunctions.MIDI, "ok");
        reason(functions, UsbFunctions.NETWORK | UsbFunctions.SERIAL | UsbFunctions.STORAGE | UsbFunctions.ADB, "endpoints");
        reason(functions, UsbFunctions.SWITCH | UsbFunctions.KEYBOARD, "standalone");
        reason(functions, UsbFunctions.MEDIA | UsbFunctions.MIDI, "routing");
        reason(functions, UsbFunctions.NETWORK | UsbFunctions.RNDIS, "networks");
        reason(functions, UsbFunctions.SOUNDBOARD, "sound");
        reason(functions, UsbFunctions.SOUND | UsbFunctions.SOUNDBOARD, "ok");
        reason(functions, UsbFunctions.SOUND | UsbFunctions.SOUNDBOARD | UsbFunctions.KEYBOARD, "routing");
        reason(functions, UsbFunctions.FLIPPER, "ok");
        reason(functions, UsbFunctions.FLIPPER | UsbFunctions.STORAGE, "standalone");
        reason(functions, UsbFunctions.ALL, "unavailable");
        reason(new UsbFunctions(), 25, "unavailable");
        for (String bad : new String[] {"", "0 ok", "1 ok 0 0 54272\n", "0 other 0 0 54272\n",
                "0 ok x 0 54272\n", "0 ok 0 -1 54272\n", "0 ok 0 0 65536\n", "0 ok 0 0 1 extra\n",
                "0 ok 0 0 1\n1 ok 0 0 1\n"}) {
            try { UsbFunctions.read(new StringReader(bad), masks(0)); throw new AssertionError(bad); }
            catch (IOException expected) { }
        }
        reason(UsbFunctions.read(new StringReader("0 empty 0 0 54272\n"), masks(0)), 0, "empty");
        protocol(args[0], "", 0, "");
        protocol(args[0], "0\n2\n2", 0, "0 empty 0 0 54272\n2 ok 1 1 54274\n2 ok 1 1 54274\n");
        protocol(args[0], "524288\n", 0, "524288 empty 0 0 16384\n");
        for (String bad : new String[] {"\n", "xinput\n", "-1\n", "1 2\n", "1000000\n", "999999999999999999\n"})
            protocol(args[0], bad, 2, "");
        final Process removed = new ProcessBuilder(args[0], "--policies").start();
        try {
            if (!removed.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || removed.exitValue() != 2
                    || removed.getInputStream().read() != -1) throw new AssertionError("Full dump still supported");
        }
        finally { removed.destroy(); }

        final Path dir = Files.createTempDirectory(Paths.get(args[1]), "policy-");
        Files.createSymbolicLink(dir.resolve("daemon"), Paths.get(args[0]).toAbsolutePath());
        final Path helper = dir.resolve("helper");
        Files.writeString(helper, "#!/bin/sh\ndir=${0%/*}\n[ \"$#\" = 1 ] && [ \"$1\" = --reasons ] || exit 2\n"
                + "printf 'exec\\n' >>\"$dir/calls\"\ncat >\"$dir/request\"\n"
                + "if [ -f \"$dir/response\" ]; then cat \"$dir/response\"; exit \"$(cat \"$dir/status\")\"; fi\n"
                + "exec \"$dir/daemon\" --reasons <\"$dir/request\"\n");
        if (!helper.toFile().setExecutable(true)) throw new AssertionError();
        final UsbFunctions page = new UsbFunctions(helper.toString());
        if (calls(dir) != 0) throw new AssertionError("Policy construction ran helper");
        for (int current : new int[] {0, UsbFunctions.XINPUT, UsbFunctions.XINPUT | UsbFunctions.GAMEPAD,
                UsbFunctions.KEYBOARD | UsbFunctions.MEDIA,
                UsbFunctions.SOUND | UsbFunctions.SOUNDBOARD, UsbFunctions.FLIPPER, UsbFunctions.ALL,
                UsbFunctions.STORAGE | UsbFunctions.KEYBOARD | UsbFunctions.MOUSE | UsbFunctions.ADB}) {
            final Set<Integer> batch = UsbFunctions.pageMasks(current);
            final int before = calls(dir);
            page.query(batch);
            final List<String> lines = Files.readAllLines(dir.resolve("request"));
            if (batch.size() > 20 || lines.size() != batch.size() || calls(dir) != before + 1)
                throw new AssertionError("Unbounded device page policy query: " + lines.size());
            for (int mask : batch) if ("unavailable".equals(page.reason(mask))) throw new AssertionError(mask);
            page.queryMissing(batch);
            if (calls(dir) != before + 1) throw new AssertionError("Cache missed a page mask");
        }
        final int single = calls(dir);
        page.query(Collections.singleton(UsbFunctions.FLIPPER));
        if (calls(dir) != single + 1 || !Files.readAllLines(dir.resolve("request")).equals(Arrays.asList("262144")))
            throw new AssertionError("Selected mask was not queried alone");
        final UsbFunctions missing = new UsbFunctions(helper.toString());
        int before = calls(dir);
        missing.queryMissing(masks(UsbFunctions.XINPUT, UsbFunctions.GAMEPAD));
        if (calls(dir) != before + 1 || Files.readAllLines(dir.resolve("request")).size() != 2)
            throw new AssertionError("Missing masks were not batched");
        before = calls(dir);
        missing.query(Collections.emptySet());
        missing.query(masks(-1, UsbFunctions.ALL + 1));
        if (calls(dir) != before) throw new AssertionError("Empty/invalid batch ran helper");
        reason(missing, -1, "unavailable"); reason(missing, UsbFunctions.ALL + 1, "unavailable");
        Files.writeString(dir.resolve("status"), "0");
        for (String bad : new String[] {"", "1 ok 1 1 54273\n", "1 ok 1 1 54273\n2 bad 1 1 54274\n",
                "2 ok 1 1 54274\n1 ok 1 1 54273\n", "1 ok 1 1 54273\n2 ok 1 1 54274\n3 ok 0 0 1\n"}) {
            Files.deleteIfExists(dir.resolve("response"));
            missing.query(masks(1, 2));
            reason(missing, 1, "ok"); reason(missing, 2, "ok");
            Files.writeString(dir.resolve("response"), bad);
            missing.query(masks(1, 2));
            reason(missing, 1, "unavailable"); reason(missing, 2, "unavailable");
        }
        Files.writeString(dir.resolve("response"), "1 ok 1 1 54273\n2 ok 1 1 54274\n");
        Files.writeString(dir.resolve("status"), "1");
        missing.query(masks(1, 2));
        reason(missing, 1, "unavailable"); reason(missing, 2, "unavailable");
        Files.delete(dir.resolve("response"));
        missing.query(masks(1, 2));
        reason(missing, 1, "ok"); reason(missing, 2, "ok");
        Files.delete(helper);
        missing.query(masks(1, 2));
        reason(missing, 1, "unavailable"); reason(missing, 2, "unavailable");
        if (!UsbPeer.matches(0x18d1,0x4800,"andr36oid","andr36oid USB Device")
                || !UsbPeer.matches(0x18d1,0x6c00,"andr36oid","andr36oid USB Device")
                || !UsbPeer.matches(0x18d1,0x5002,"andr36oid","andr36oid XInput Device")
                || UsbPeer.matches(0x18d1,0xd402,"andr36oid","andr36oid XInput Device")
                || UsbPeer.matches(0x18d1,0x4100,"andr36oid","andr36oid USB Device")
                || UsbPeer.matches(0x1234,0x4800,"andr36oid","andr36oid USB Device")) throw new AssertionError("USB peer identity");
        System.out.println("App batches at most 20 page masks in one helper, caches native reasons and fails closed on errors");
    }
}
