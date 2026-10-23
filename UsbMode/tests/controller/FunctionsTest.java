package org.andr36oid.usbmode;

import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;

public class FunctionsTest {
    public static void main(String[] args) throws Exception {
        final UsbFunctions functions;
        try (FileReader reader = new FileReader(args[0])) { functions = UsbFunctions.read(reader); }
        int valid = 0;
        for (int mask = 1; mask < 64; mask++) {
            if ("ok".equals(functions.reason(mask))) valid++;
            else if (!"controllers".equals(functions.reason(mask))) throw new AssertionError(mask);
        }
        if (valid != 47 || !"empty".equals(functions.reason(0))) throw new AssertionError();
        if (!"unavailable".equals(new UsbFunctions().reason(25))) throw new AssertionError();
        for (String bad : new String[] {"", "0 ok", "1 ok 0 0 54272\n", "0 other 0 0 54272\n"}) {
            try { UsbFunctions.read(new StringReader(bad)); throw new AssertionError(); }
            catch (IOException expected) { }
        }
        System.out.println("App reads the native policy for all 64 masks and fails closed on missing/malformed policy");
    }
}
