package org.andr36oid.kerneltweaks;

import android.os.SystemClock;
import android.os.SystemProperties;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Talks to the root helper on a worker thread. Imported files are bounded snapshots. */
final class Profiles {

    private static final File DIR = new File("/data/misc/andr36oid-ktprofiles");
    private static final String SERVICE = "andr36oid-ktprofiles";

    static final class Entry {
        final String name;
        final boolean builtin;
        final Profile profile;

        Entry(String name, boolean builtin, Profile profile) {
            this.name = name;
            this.builtin = builtin;
            this.profile = profile;
        }
    }

    static final class Scan {
        final List<Entry> entries = new ArrayList<>();
        final Profile console;
        int skipped;

        Scan(Profile console) {
            this.console = console;
        }
    }

    static synchronized String export(String readme) throws IOException {
        write("readme", readme);
        run("export");
        return read("saved").trim();
    }

    static synchronized Scan scan(List<Tweak> tweaks) throws IOException {
        final String status = run("scan");
        final Profile console = Profile.parse("format=andr36oid-ktprofile-1\n"
                + read("identity"), tweaks);
        final Scan out = new Scan(console);
        try {
            out.skipped = Integer.parseInt(status.substring("done ".length()));
        } catch (NumberFormatException e) {
            throw new IOException("bad helper status", e);
        }
        for (String line : read("list").split("\n")) {
            if (line.isEmpty()) continue;
            final String[] f = line.split("\t", 3);
            if (f.length != 3 || !f[0].matches("[0-9]+")) {
                throw new IOException("bad profile list");
            }
            try {
                final Profile profile = Profile.parse(read("in/" + f[0] + ".txt"), tweaks);
                final boolean builtin = "builtin".equals(f[1]);
                if (!builtin || profile.recommended(console.identity)) {
                    out.entries.add(new Entry(builtin ? f[2] : f[1] + "/" + f[2], builtin,
                            profile));
                }
            } catch (IOException e) {
                out.skipped++;
            }
        }
        return out;
    }

    private static String run(String request) throws IOException {
        if ("running".equals(SystemProperties.get("init.svc." + SERVICE, ""))) {
            throw new IOException("profile helper busy");
        }
        new File(DIR, "status").delete();
        write("request", request);
        SystemProperties.set("ctl.start", SERVICE);
        final long start = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - start < 60000) {
            SystemClock.sleep(200);
            final String status = new File(DIR, "status").isFile() ? read("status").trim() : "";
            if (status.startsWith("done ")) return status;
            if (status.startsWith("error ")) throw new IOException(status);
            if (SystemClock.elapsedRealtime() - start > 5000
                    && "stopped".equals(SystemProperties.get("init.svc." + SERVICE, ""))) {
                throw new IOException("profile helper stopped");
            }
        }
        throw new IOException("profile helper timed out");
    }

    private static String read(String name) throws IOException {
        try (InputStream in = new FileInputStream(new File(DIR, name));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > Profile.MAX_BYTES) throw new IOException("file too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void write(String name, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(DIR, name))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
