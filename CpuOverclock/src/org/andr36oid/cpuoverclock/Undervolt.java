package org.andr36oid.cpuoverclock;

import android.text.TextUtils;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The CPU undervolt is a kernel parameter that lowers every CPU OPP voltage by a number of
 * microvolts. The kernel never keeps it, so the console always starts at stock voltages, and
 * nothing here saves it either, like the overclock.
 */
final class Undervolt {

    private static final String TAG = "CpuUndervolt";

    private static final String PARAM = "/sys/module/rockchip_cpufreq/parameters/undervolt_uv";

    /** The steps offered, in microvolts. The kernel allows up to 100 mV. */
    static final int[] STEPS_UV = {0, 25000, 50000, 75000};

    boolean isSupported() {
        return get() >= 0;
    }

    /** The current undervolt in microvolts, -1 if the kernel doesn't have it. */
    int get() {
        try {
            final String text = new String(Files.readAllBytes(Paths.get(PARAM)),
                    StandardCharsets.UTF_8).trim();
            return TextUtils.isEmpty(text) ? -1 : Integer.parseInt(text);
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    boolean set(int uv) {
        try (FileOutputStream out = new FileOutputStream(PARAM)) {
            out.write(String.valueOf(uv).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Couldn't set the undervolt to " + uv + " uV", e);
            return false;
        }
    }
}
