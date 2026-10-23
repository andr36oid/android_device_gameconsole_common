package org.andr36oid.usbmode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/** The native policy is also the root helper's validator. Fail closed if unavailable. */
final class UsbFunctions {
    static final int STORAGE = 1, XINPUT = 2, GAMEPAD = 4, KEYBOARD = 8, MOUSE = 16, ADB = 32;
    final String[] reasons = new String[64];

    String reason(int mask) {
        return mask >= 0 && mask < reasons.length && reasons[mask] != null ? reasons[mask] : "unavailable";
    }

    static UsbFunctions read(Reader input) throws IOException {
        final UsbFunctions result = new UsbFunctions();
        final BufferedReader reader = new BufferedReader(input);
        for (int mask = 0; mask < 64; mask++) {
            final String line = reader.readLine();
            if (line == null) throw new IOException("Missing USB policy");
            final String[] fields = line.split(" ");
            if (fields.length != 5 || !fields[0].equals(Integer.toString(mask))
                    || !fields[1].matches("ok|empty|controllers|endpoints"))
                throw new IOException("Invalid USB policy");
            result.reasons[mask] = fields[1];
        }
        return result;
    }
}
