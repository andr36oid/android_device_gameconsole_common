package org.andr36oid.emmc;

import android.os.SystemProperties;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Talks to andr36oid-emmcinstall (the eMMC installer, emmcinstall/ in this repo), which
 * runs as root as an init service: we write a request and start it, it writes JSON progress
 * lines, its result and its records (state-<CID>.json).
 */
final class Installer {
    private static final String TAG = "EmmcInstall";
    static final String SERVICE = "andr36oid-emmcinstall";
    private static final File DIR = new File("/data/misc/andr36oid-emmcinstall");
    /** Records from before they were kept per eMMC (state-<CID>.json). */
    private static final File LEGACY_STATE = new File(DIR, "state.json");
    private static final File RESULT = new File(DIR, "result.json");
    private static final File PROGRESS = new File(DIR, "progress.jsonl");
    private static final File CONFIRM = new File(DIR, "confirm.json");

    private Installer() {
    }

    /** Starts a command; its result shows up in result(). */
    static boolean start(String cmd, String... args) {
        try {
            RESULT.delete();
            final StringBuilder req = new StringBuilder("cmd=").append(cmd).append('\n');
            for (String a : args) req.append("arg=").append(a.replace('\n', ' ')).append('\n');
            try (FileOutputStream out = new FileOutputStream(new File(DIR, "request"))) {
                out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }
            SystemProperties.set("ctl.start", SERVICE);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "can't start the installer", e);
            return false;
        }
    }

    static boolean running() {
        final String state = SystemProperties.get("init.svc." + SERVICE, "");
        return "running".equals(state) || "restarting".equals(state);
    }

    /** andr36oid runs from the SD card (else from the eMMC, or we can't tell). */
    static boolean fromSD() {
        return "ff370000.dwmmc".equals(SystemProperties.get("ro.boot.boot_devices", ""));
    }

    static boolean fromEmmc() {
        return "ff390000.dwmmc".equals(SystemProperties.get("ro.boot.boot_devices", ""));
    }

    /** On the eMMC system: its data card's userdata PARTUUID ("" without one). */
    static String datacard() {
        return SystemProperties.get("ro.boot.datacard", "");
    }

    /** The last command's result: cmd, ok, error, result. Null while there is none. */
    static JSONObject result() {
        return json(RESULT);
    }

    /**
     * What the installer has done on this console's eMMC (State in steps.go); empty when
     * nothing yet. One SD card can install on several consoles: each has its own records.
     */
    static JSONObject state() {
        final String cid = emmcCid();
        if (cid.isEmpty()) return new JSONObject();
        JSONObject s = json(new File(DIR, "state-" + cid + ".json"));
        if (s == null) {
            // older records, until the installer's next run takes them over
            final JSONObject old = json(LEGACY_STATE);
            if (old != null && cid.equals(old.optString("cid"))) s = old;
        }
        return s == null ? new JSONObject() : s;
    }

    /** The built-in eMMC's CID in lower case, "" when it isn't there or can't be read. */
    static String emmcCid() {
        final File[] blocks = new File("/sys/block").listFiles();
        if (blocks != null) {
            for (File b : blocks) {
                final String n = b.getName();
                if (!n.startsWith("mmcblk") || n.contains("boot") || n.contains("rpmb")) continue;
                try {
                    if (!b.getCanonicalPath().contains("/ff390000.")) continue;
                } catch (IOException e) {
                    continue;
                }
                final String type = read(new File(b, "device/type"), 16);
                final String cid = clean(read(new File(b, "device/cid"), 64));
                if (type != null && "MMC".equals(type.trim()) && cid != null) return cid;
            }
        }
        // the helper's last look at the eMMC, made as root
        final String info = read(Helper.INFO, 1 << 16);
        final String cid = info == null ? null : clean(EmmcInfo.parse(info).field("cid"));
        return cid == null ? "" : cid;
    }

    private static String clean(String cid) {
        if (cid == null) return null;
        final String c = cid.trim().toLowerCase(Locale.ROOT);
        return c.matches("[0-9a-f]{32}") ? c : null;
    }

    /** On the eMMC system: what the test start check said, null until it ran. */
    static JSONObject confirmResult() {
        return json(CONFIRM);
    }

    private static JSONObject json(File f) {
        final String text = read(f, 1 << 20);
        if (text == null) return null;
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Reads the progress file as it grows: only the new lines each time. */
    static final class ProgressReader {
        private long mPos;
        private final StringBuilder mPart = new StringBuilder();

        /** New events since the last call; a new command starts the file again. */
        List<JSONObject> poll() {
            final List<JSONObject> out = new ArrayList<>();
            try (RandomAccessFile f = new RandomAccessFile(PROGRESS, "r")) {
                final long len = f.length();
                if (len < mPos) {
                    mPos = 0;
                    mPart.setLength(0);
                }
                if (len == mPos) return out;
                f.seek(mPos);
                final byte[] buf = new byte[(int) Math.min(len - mPos, 4 << 20)];
                f.readFully(buf);
                mPos += buf.length;
                mPart.append(new String(buf, StandardCharsets.UTF_8));
                int nl;
                while ((nl = mPart.indexOf("\n")) >= 0) {
                    final String line = mPart.substring(0, nl);
                    mPart.delete(0, nl + 1);
                    try {
                        out.add(new JSONObject(line));
                    } catch (JSONException e) {
                        // a line cut short by a crash: skip it
                    }
                }
            } catch (IOException e) {
                // no progress yet
            }
            return out;
        }

        void reset() {
            mPos = 0;
            mPart.setLength(0);
        }
    }

    static List<JSONObject> list(JSONObject o, String key) {
        final List<JSONObject> out = new ArrayList<>();
        final JSONArray a = o == null ? null : o.optJSONArray(key);
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            final JSONObject e = a.optJSONObject(i);
            if (e != null) out.add(e);
        }
        return out;
    }

    static String read(File f, int max) {
        try (FileInputStream in = new FileInputStream(f)) {
            final byte[] buf = new byte[max];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
