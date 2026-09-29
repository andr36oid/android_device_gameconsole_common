package org.andr36oid.savebackup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Sync saves": what Save backup sets up in the Syncthing app (Syncthing-Fork). Plain
 * Java, no android.*, so the host test runs it against a fake Syncthing.
 *
 * <p>Two Syncthing folders with fixed IDs, so two consoles with Sync saves on share the
 * same folders: EASYROMS (saves next to the ROMs, ArkOS style) and the internal storage
 * (RetroArch's and PPSSPP's own folders). Both are the whole partition with ignore
 * patterns that let only save files and save states through, never ROMs.
 *
 * <p>Other devices are added by the user on the Sync saves page: a device that asked to
 * connect (a PC that added this console's ID) or one found on the same Wi-Fi.
 */
final class SyncSetup {

    static final String PKG = "com.github.catfriend1.syncthingfork";
    static final String FOLDER_EASYROMS = "andr36oid-saves-easyroms";
    static final String FOLDER_INTERNAL = "andr36oid-saves-internal";
    static final List<String> FOLDERS =
            Collections.unmodifiableList(Arrays.asList(FOLDER_EASYROMS, FOLDER_INTERNAL));

    /** Save endings, the same as Games.SAVE_ENDINGS. */
    private static final String[] SAVE_ENDINGS = {
        "srm", "sav", "rtc", "eep", "sra", "fla", "mpk", "mcd", "mcr", "dsv", "ldci", "ppst",
    };

    private SyncSetup() {
    }

    // ---- ignore patterns -------------------------------------------------------------

    /**
     * The .stignore of one of our folders. Rooted ignores come first: only those let
     * Syncthing skip a folder without looking inside. Then the saves are let through, and
     * everything else is ignored. See docs.syncthing.net/users/ignoring.html.
     */
    static List<String> ignores(String folderId) {
        final List<String> l = new ArrayList<>();
        l.add("// Made by Settings > Save backup > Sync saves. Only save games and save");
        l.add("// states are synced, never ROMs. Changes here are replaced.");
        if (FOLDER_INTERNAL.equals(folderId)) {
            l.add("/Android");
            l.add("/Backups");
            l.add("/DCIM");
            l.add("/Download");
            l.add("/Movies");
            l.add("/Music");
            l.add("/Pictures");
        } else {
            l.add("/backups");
            l.add("/bios");
            l.add("/andr36oid-reports");
        }
        for (String e : SAVE_ENDINGS) l.add("!(?i)*." + e);
        // .state, .state1 … .state999, .state.auto, and their .png thumbnails
        l.add("!(?i)*.state");
        l.add("!(?i)*.state.png");
        l.add("!(?i)*.state.auto");
        l.add("!(?i)*.state.auto.png");
        l.add("!(?i)*.state[0-9]*");
        // RetroArch's and PPSSPP's own save folders (anything in them)
        l.add("!RetroArch/saves");
        l.add("!RetroArch/states");
        l.add("!PSP/SAVEDATA");
        l.add("!PSP/PPSSPP_STATE");
        l.add("*");
        return l;
    }

    // ---- the Syncthing app's files (read and written by the root helper) --------------

    private static final Pattern GUI = Pattern.compile("<gui\\b([^>]*)>(.*?)</gui>",
            Pattern.DOTALL);

    /**
     * The REST address and key from Syncthing's config.xml: "url=https://127.0.0.1:8384",
     * "apikey=…"; empty when the file isn't complete yet.
     */
    static Map<String, String> gui(String configXml) {
        final Map<String, String> m = new LinkedHashMap<>();
        if (configXml == null) return m;
        final Matcher g = GUI.matcher(configXml);
        if (!g.find()) return m;
        final boolean tls = g.group(1).matches("(?s).*\\btls=\"true\".*");
        final String address = tag(g.group(2), "address");
        final String key = tag(g.group(2), "apikey");
        if (address == null || key == null || key.isEmpty()) return m;
        String hostPort = address.trim();
        // 0.0.0.0 or an empty host means every address; we use loopback
        if (hostPort.startsWith("0.0.0.0:")) hostPort = "127.0.0.1" + hostPort.substring(7);
        if (hostPort.startsWith(":")) hostPort = "127.0.0.1" + hostPort;
        final String url = (tls ? "https://" : "http://") + hostPort;
        if (!SyncApi.loopback(url)) return m;
        m.put("url", url);
        m.put("apikey", key.trim());
        return m;
    }

    private static String tag(String xml, String name) {
        final Matcher t = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        return t.find() ? t.group(1) : null;
    }

    /**
     * The Syncthing app's settings Sync saves needs, merged into its preferences file
     * (keys from Syncthing-Fork's Constants.java). On: runs from boot, but Syncthing itself
     * only runs on Wi-Fi (not metered, not mobile data) while charging, not in battery
     * saver; our page may start and stop it by broadcast. Off: no start at boot.
     */
    static String mergePrefs(String xml, boolean on) {
        final Map<String, String> v = new LinkedHashMap<>();
        v.put("boolean:always_run_in_background", on ? "true" : "false");
        v.put("boolean:broadcast_service_control", "true");
        v.put("int:btnStateForceStartStop", "0");
        if (on) {
            v.put("boolean:run_on_wifi", "true");
            v.put("boolean:run_on_metered_wifi", "false");
            v.put("boolean:run_on_mobile_data", "false");
            v.put("boolean:run_on_roaming", "false");
            v.put("boolean:respect_battery_saving", "true");
            v.put("boolean:run_in_flight_mode", "false");
            v.put("string:power_source", "ac_power");
        }
        String body = xml != null && xml.contains("<map") ? xml : null;
        if (body == null || body.matches("(?s).*<map\\s*/>.*")) {
            body = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n";
        }
        for (Map.Entry<String, String> e : v.entrySet()) {
            final String type = e.getKey().substring(0, e.getKey().indexOf(':'));
            final String name = e.getKey().substring(type.length() + 1);
            final String q = Pattern.quote(name);
            // drop the old entry, whatever its type
            body = body.replaceAll("[ \\t]*<\\w+ name=\"" + q + "\"[^>]*/>[ \\t]*\\n?", "");
            body = body.replaceAll("(?s)[ \\t]*<(\\w+) name=\"" + q + "\">.*?</\\1>[ \\t]*\\n?",
                    "");
            final String entry = type.equals("string")
                    ? "    <string name=\"" + name + "\">" + e.getValue() + "</string>\n"
                    : "    <" + type + " name=\"" + name + "\" value=\"" + e.getValue()
                            + "\" />\n";
            final int end = body.lastIndexOf("</map>");
            body = body.substring(0, end) + entry + body.substring(end);
        }
        return body;
    }

    // ---- the REST side ---------------------------------------------------------------

    /** What the Sync saves page shows. */
    static final class State {
        String myId = "";
        /** Devices our folders are shared with: id -> name. */
        final Map<String, String> devices = new LinkedHashMap<>();
        final List<String> connected = new ArrayList<>();
        /** Devices that asked to connect: id -> name they gave. */
        final Map<String, String> pending = new LinkedHashMap<>();
        /** Devices seen on the local network that aren't set up yet. */
        final List<String> nearby = new ArrayList<>();
        /** Our folders that exist: id -> "idle", "scanning", "syncing", "error" … */
        final Map<String, String> folders = new LinkedHashMap<>();
        long needFiles;
        long errors;
        boolean paused;
    }

    /**
     * Makes our folders exist, point at the right place, run, and have the right ignore
     * patterns. Idempotent; a folder whose path is null (EASYROMS not mounted) is left
     * alone. The first time, this console's name in Syncthing becomes {@code name}.
     *
     * @param paths folder id -> path as the Syncthing app sees it
     */
    static void setUp(SyncApi api, Map<String, String> paths, Map<String, String> labels,
            String name) throws IOException {
        final String myId = myId(api);
        if (name != null && !name.isEmpty() && !myId.isEmpty()) {
            final Map<String, Object> me = Json.obj(api.get("/rest/config/devices/"
                    + SyncApi.enc(myId)));
            if (!name.equals(Json.str(me.get("name")))) {
                me.put("name", name);
                api.put("/rest/config/devices/" + SyncApi.enc(myId), me);
            }
        }
        final List<String> shared = sharedDevices(api);
        for (String id : FOLDERS) {
            final String path = paths.get(id);
            if (path == null) continue;
            Map<String, Object> f = folder(api, id);
            final boolean isNew = f == null;
            if (isNew) {
                f = new LinkedHashMap<>();
                f.put("id", id);
                f.put("type", "sendreceive");
                f.put("rescanIntervalS", 3600L);
                f.put("fsWatcherEnabled", Boolean.TRUE);
                f.put("fsWatcherDelayS", 10L);
                f.put("ignorePerms", Boolean.TRUE);
                f.put("hashers", 1L);
                // the console keeps the last 5 versions of a replaced save in .stversions
                final Map<String, Object> versioning = new LinkedHashMap<>();
                final Map<String, Object> params = new LinkedHashMap<>();
                params.put("keep", "5");
                versioning.put("type", "simple");
                versioning.put("params", params);
                f.put("versioning", versioning);
            }
            f.put("label", labels.containsKey(id) ? labels.get(id) : id);
            f.put("path", path);
            f.put("paused", Boolean.FALSE);
            final List<Object> devs = Json.list(f.get("devices"));
            for (String d : shared) {
                if (!hasDevice(devs, d)) devs.add(device(d));
            }
            f.put("devices", devs);
            api.put("/rest/config/folders/" + SyncApi.enc(id), f);
            final List<String> want = ignores(id);
            final List<Object> have = Json.list(Json.obj(api.get("/rest/db/ignores?folder="
                    + SyncApi.enc(id))).get("ignore"));
            if (!new ArrayList<Object>(want).equals(have)) {
                final Map<String, Object> body = new LinkedHashMap<>();
                body.put("ignore", new ArrayList<Object>(want));
                api.post("/rest/db/ignores?folder=" + SyncApi.enc(id), body);
            }
        }
    }

    /** Pauses our folders (Sync saves off); devices and settings stay for next time. */
    static void pause(SyncApi api) throws IOException {
        for (String id : FOLDERS) {
            final Map<String, Object> f = folder(api, id);
            if (f == null || Json.bool(f.get("paused"))) continue;
            f.put("paused", Boolean.TRUE);
            api.put("/rest/config/folders/" + SyncApi.enc(id), f);
        }
    }

    /** True if the user syncs other folders too, so Syncthing must keep running. */
    static boolean otherFoldersActive(SyncApi api) throws IOException {
        for (Object o : Json.list(api.get("/rest/config/folders"))) {
            final Map<String, Object> f = Json.obj(o);
            if (!FOLDERS.contains(Json.str(f.get("id"))) && !Json.bool(f.get("paused"))) {
                return true;
            }
        }
        return false;
    }

    /** Adds a device (if needed) and shares our folders with it. */
    static void addDevice(SyncApi api, String id, String name) throws IOException {
        if (!validId(id)) throw new IOException("bad device id");
        final String path = "/rest/config/devices/" + SyncApi.enc(id);
        Map<String, Object> d = null;
        try {
            d = Json.obj(api.get(path));
        } catch (SyncApi.HttpException e) {
            if (e.code != 404) throw e;
        }
        if (d == null || Json.str(d.get("deviceID")).isEmpty()) {
            d = new LinkedHashMap<>();
            d.put("deviceID", id);
            d.put("name", name != null ? name : "");
            final List<Object> addresses = new ArrayList<>();
            addresses.add("dynamic");
            d.put("addresses", addresses);
            api.put(path, d);
        }
        for (String fid : FOLDERS) {
            final Map<String, Object> f = folder(api, fid);
            if (f == null) continue;
            final List<Object> devs = Json.list(f.get("devices"));
            if (hasDevice(devs, id)) continue;
            devs.add(device(id));
            f.put("devices", devs);
            api.put("/rest/config/folders/" + SyncApi.enc(fid), f);
        }
    }

    /** Stops sharing our folders with a device; removes it if it shares nothing else. */
    static void removeDevice(SyncApi api, String id) throws IOException {
        for (String fid : FOLDERS) {
            final Map<String, Object> f = folder(api, fid);
            if (f == null) continue;
            final List<Object> devs = Json.list(f.get("devices"));
            if (!devs.removeIf(o -> id.equals(Json.str(Json.obj(o).get("deviceID"))))) continue;
            f.put("devices", devs);
            api.put("/rest/config/folders/" + SyncApi.enc(fid), f);
        }
        for (Object o : Json.list(api.get("/rest/config/folders"))) {
            if (hasDevice(Json.list(Json.obj(o).get("devices")), id)) return;
        }
        try {
            api.delete("/rest/config/devices/" + SyncApi.enc(id));
        } catch (SyncApi.HttpException e) {
            if (e.code != 404) throw e;
        }
    }

    static State state(SyncApi api) throws IOException {
        final State s = new State();
        s.myId = myId(api);
        final Map<String, String> names = new LinkedHashMap<>();
        for (Object o : Json.list(api.get("/rest/config/devices"))) {
            final Map<String, Object> d = Json.obj(o);
            names.put(Json.str(d.get("deviceID")), Json.str(d.get("name")));
        }
        boolean anyRunning = false;
        for (String id : FOLDERS) {
            final Map<String, Object> f = folder(api, id);
            if (f == null) continue;
            if (!Json.bool(f.get("paused"))) anyRunning = true;
            for (Object o : Json.list(f.get("devices"))) {
                final String d = Json.str(Json.obj(o).get("deviceID"));
                if (!d.isEmpty() && !d.equals(s.myId) && !s.devices.containsKey(d)) {
                    s.devices.put(d, names.containsKey(d) ? names.get(d) : "");
                }
            }
            String state;
            try {
                final Map<String, Object> st = Json.obj(api.get("/rest/db/status?folder="
                        + SyncApi.enc(id)));
                state = Json.str(st.get("state"));
                s.needFiles += Json.num(st.get("needFiles"));
                s.errors += Math.max(Json.num(st.get("errors")),
                        Json.num(st.get("pullErrors")));
            } catch (SyncApi.HttpException e) {
                state = "error";
            }
            s.folders.put(id, state);
        }
        s.paused = !s.folders.isEmpty() && !anyRunning;
        final Map<String, Object> conns = Json.obj(Json.obj(
                api.get("/rest/system/connections")).get("connections"));
        for (Map.Entry<String, Object> e : conns.entrySet()) {
            if (Json.bool(Json.obj(e.getValue()).get("connected"))) s.connected.add(e.getKey());
        }
        for (Map.Entry<String, Object> e : Json.obj(
                api.get("/rest/cluster/pending/devices")).entrySet()) {
            if (validId(e.getKey()) && !s.devices.containsKey(e.getKey())) {
                s.pending.put(e.getKey(), Json.str(Json.obj(e.getValue()).get("name")));
            }
        }
        try {
            for (String id : Json.obj(api.get("/rest/system/discovery")).keySet()) {
                if (validId(id) && !id.equals(s.myId) && !s.devices.containsKey(id)
                        && !s.pending.containsKey(id)) {
                    s.nearby.add(id);
                }
            }
        } catch (SyncApi.HttpException e) {
            // discovery can be turned off; then there's just nothing nearby
        }
        return s;
    }

    static String myId(SyncApi api) throws IOException {
        return Json.str(Json.obj(api.get("/rest/system/status")).get("myID"));
    }

    /** A Syncthing device ID: 8 groups of 7 letters/digits, with dashes. */
    static boolean validId(String id) {
        return id != null && id.matches("[A-Z2-7]{7}(-[A-Z2-7]{7}){7}");
    }

    /** "ABCD123" - the first group, as Syncthing shows it. */
    static String shortId(String id) {
        return id != null && id.length() >= 7 ? id.substring(0, 7) : "";
    }

    private static Map<String, Object> folder(SyncApi api, String id) throws IOException {
        try {
            final Map<String, Object> f = Json.obj(api.get("/rest/config/folders/"
                    + SyncApi.enc(id)));
            return Json.str(f.get("id")).isEmpty() ? null : f;
        } catch (SyncApi.HttpException e) {
            if (e.code == 404) return null;
            throw e;
        }
    }

    /** Devices already sharing one of our folders (so a new folder gets them too). */
    private static List<String> sharedDevices(SyncApi api) throws IOException {
        final List<String> l = new ArrayList<>();
        for (String id : FOLDERS) {
            final Map<String, Object> f = folder(api, id);
            if (f == null) continue;
            for (Object o : Json.list(f.get("devices"))) {
                final String d = Json.str(Json.obj(o).get("deviceID"));
                if (!d.isEmpty() && !l.contains(d)) l.add(d);
            }
        }
        return l;
    }

    private static boolean hasDevice(List<Object> devs, String id) {
        for (Object o : devs) {
            if (id.equals(Json.str(Json.obj(o).get("deviceID")))) return true;
        }
        return false;
    }

    private static Map<String, Object> device(String id) {
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("deviceID", id);
        return d;
    }
}
