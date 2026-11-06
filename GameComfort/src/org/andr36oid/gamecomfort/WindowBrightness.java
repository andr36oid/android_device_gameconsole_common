package org.andr36oid.gamecomfort;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android 11 has no client query for another app's window brightness. Use the
 * effective PowerManager override, off the UI thread; unknown/denied is a veto.
 * Window-only changes without a task event are checked at the next task event. */
final class WindowBrightness {
    private static final long TIMEOUT_MS = 750;
    private static final int MAX_BYTES = 64 * 1024;
    private static final Pattern OVERRIDE = Pattern.compile(
            "(?m)^\\s*mScreenBrightnessOverrideFromWindowManager=(\\S+)[\\t ]*$");
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();

    private WindowBrightness() { }

    /** Call only from the brightness worker, never the main thread. */
    static boolean permitsRules() {
        if (!IN_FLIGHT.compareAndSet(false, true)) return false;
        ParcelFileDescriptor[] pipe = null;
        boolean started = false;
        try {
            IBinder power = ServiceManager.getService("power");
            if (power == null) return false;
            pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor input = pipe[0], output = pipe[1];
            FutureTask<Boolean> read = new FutureTask<>(() -> {
                try (ParcelFileDescriptor.AutoCloseInputStream stream =
                             new ParcelFileDescriptor.AutoCloseInputStream(input)) {
                    // dumpAsync is a one-way binder call; the remote side owns a
                    // duplicate descriptor. Closing ours permits EOF when it ends.
                    power.dumpAsync(output.getFileDescriptor(), new String[0]);
                    output.close();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[2048];
                    int n;
                    while ((n = stream.read(buffer)) != -1) {
                        if (bytes.size() + n > MAX_BYTES) return false;
                        bytes.write(buffer, 0, n);
                    }
                    return permits(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    return false;
                } finally {
                    close(input);
                    close(output);
                }
            });
            Thread thread = new Thread(() -> {
                try { read.run(); } finally { IN_FLIGHT.set(false); }
            }, "WindowBrightnessDump");
            thread.setDaemon(true);
            thread.start();
            started = true;
            try {
                return read.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                read.cancel(true);
                return false;
            } catch (Exception e) {
                read.cancel(true);
                return false;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (pipe != null) {
                close(pipe[0]);
                close(pipe[1]);
            }
            // A stalled binder thread keeps the gate until it actually exits;
            // repeated task events cannot create an unbounded number of threads.
            if (!started) IN_FLIGHT.set(false);
        }
    }

    static boolean permits(String dump) {
        if (dump == null) return false;
        Matcher match = OVERRIDE.matcher(dump);
        if (!match.find()) return false;
        String value = match.group(1);
        if (match.find()) return false;
        try {
            // Android 11's BRIGHTNESS_INVALID_FLOAT is NaN. A finite override,
            // infinity or a different diagnostic format never authorizes a rule.
            return Float.isNaN(Float.parseFloat(value));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void close(ParcelFileDescriptor descriptor) {
        try { descriptor.close(); } catch (IOException | RuntimeException ignored) { }
    }
}
