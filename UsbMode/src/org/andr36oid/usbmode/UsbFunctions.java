package org.andr36oid.usbmode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/** The native policy is also the root helper's validator. Fail closed if unavailable. */
final class UsbFunctions {
    static final int STORAGE = 1, XINPUT = 2, GAMEPAD = 4, KEYBOARD = 8, MOUSE = 16, ADB = 32,
            MEDIA = 64, SWITCH = 128, MIDI = 256, SERIAL = 512, SOUND = 1024, NETWORK = 2048,
            PS3 = 4096, GAMECUBE = 8192, XBOX = 16384, RNDIS = 32768, PS4 = 65536, SOUNDBOARD = 131072, FLIPPER = 262144;
    static final int ALL = 524287, CONTROLLERS = XINPUT | GAMEPAD | SWITCH | PS3 | GAMECUBE | XBOX | PS4;
    static final int[] BITS = {STORAGE, KEYBOARD, MOUSE, MEDIA, MIDI, NETWORK, ADB, SOUND, SERIAL, RNDIS};
    static final int[] CONTROLLER_BITS = {0, XINPUT, GAMEPAD, SWITCH, PS3, GAMECUBE, XBOX, PS4};
    private final HashMap<Integer,String> reasons = new HashMap<>();
    private final String helper;

    UsbFunctions() { this("/system/bin/andr36oid-controller"); }
    UsbFunctions(String helper) { this.helper = helper; }

    String reason(int mask) {
        return reasons.getOrDefault(mask, "unavailable");
    }

    static Set<Integer> pageMasks(int functions) {
        final Set<Integer> masks = new LinkedHashSet<>();
        masks.add(functions);
        for (int bit : BITS) masks.add(functions | bit);
        masks.add(functions | SOUNDBOARD);
        for (int bit : CONTROLLER_BITS) masks.add((functions & ~CONTROLLERS) | bit);
        return masks;
    }

    void queryMissing(Set<Integer> masks) {
        final Set<Integer> missing = new LinkedHashSet<>(masks);
        missing.removeAll(reasons.keySet());
        query(missing);
    }

    void query(Set<Integer> masks) {
        final Set<Integer> requested = new LinkedHashSet<>();
        for (int mask : masks) if (mask >= 0 && mask <= ALL) requested.add(mask);
        for (int mask : requested) reasons.remove(mask);
        if (requested.isEmpty()) return;
        try {
            final Process process = new ProcessBuilder(helper, "--reasons").start();
            try {
                try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                    for (int mask : requested) { output.write(Integer.toString(mask)); output.newLine(); }
                }
                final UsbFunctions policy;
                try (InputStreamReader input = new InputStreamReader(process.getInputStream())) {
                    policy = read(input, requested);
                }
                if (process.waitFor() == 0) reasons.putAll(policy.reasons);
            } finally { process.destroy(); }
        } catch (IOException e) { }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static UsbFunctions read(Reader input, Set<Integer> masks) throws IOException {
        final UsbFunctions result = new UsbFunctions();
        final BufferedReader reader = new BufferedReader(input);
        for (int mask : masks) {
            final String line = reader.readLine();
            if (line == null) throw new IOException("Missing USB policy");
            final String[] fields = line.split(" ", -1);
            if (fields.length != 5 || mask < 0 || mask > ALL || !fields[0].equals(Integer.toString(mask)))
                throw new IOException("Invalid USB policy");
            switch (fields[1]) {
                case "ok": case "empty": case "controllers": case "endpoints": case "standalone":
                case "routing": case "networks": case "sound": break;
                default: throw new IOException("Invalid USB policy");
            }
            try {
                if (Integer.parseInt(fields[2]) < 0 || Integer.parseInt(fields[3]) < 0
                        || Integer.parseInt(fields[4]) < 0 || Integer.parseInt(fields[4]) > 65535)
                    throw new IOException("Invalid USB policy");
            } catch (NumberFormatException e) { throw new IOException("Invalid USB policy", e); }
            result.reasons.put(mask, fields[1]);
        }
        if (reader.readLine() != null) throw new IOException("Unexpected USB policy");
        return result;
    }
}
