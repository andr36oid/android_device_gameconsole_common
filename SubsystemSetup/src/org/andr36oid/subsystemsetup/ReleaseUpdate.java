package org.andr36oid.subsystemsetup;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.SystemProperties;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds, downloads and installs the dArkOSen release the console can run as it is.
 *
 * The app only finds the release and fetches its files. Checking, unpacking, writing
 * the DARKOS partition and the first setup are the root worker's job; this class asks
 * for them over properties and reports what the worker publishes. Everything that
 * touches the network, the download service or files runs on the caller's IO thread.
 */
final class ReleaseUpdate {
    private static final String TAG = "ReleaseUpdate";
    static final String REPOSITORY = "andr36oid/dArkOSen-erofs";
    private static final String DOWNLOADS = "https://github.com/" + REPOSITORY + "/releases/download/";
    private static final String P = SubsystemSetupActivity.P;
    private static final String INFO = "/data/local/tmp/portmaster/release.txt";
    private static final long HEADROOM = 64L << 20;

    /** Steps the progress page shows, in order. */
    static final int DOWNLOADING = 0, CHECKING = 1, WRITING = 2, FINISHING = 3;

    /** What the console runs now, from the worker's catalog. */
    static final class Installed {
        String darkosen = "", origin = "", transport = "none", sourceId = "", partsDir = "";
        long revision, capacity, partsFree;
        boolean partition;
        boolean release() { return "release".equals(origin) && !darkosen.isEmpty(); }
        boolean present() { return !"none".equals(transport) && !transport.isEmpty(); }
    }

    /** A release on offer. */
    static final class Offer {
        String darkosen, imageName, imageSha, downloadSha, minimum;
        long revision, imageSize, downloadSize;
        final List<String[]> parts = new ArrayList<>(); // name, size, sha256, url
        JSONObject json;
    }

    /** What the progress page draws. */
    static final class State {
        boolean active, finished, success, cancellable, storedAsFile;
        int step;
        long done, total;
        String error = "", version = "";
    }

    private final Context mContext;
    private final SharedPreferences mPrefs;

    ReleaseUpdate(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences("release_update", Context.MODE_PRIVATE);
    }

    static Installed installed() {
        final Installed i = new Installed();
        final Map<String, String> v = new HashMap<>();
        try {
            for (String line : new String(Files.readAllBytes(new File(INFO).toPath()), StandardCharsets.UTF_8).split("\n")) {
                final int cut = line.indexOf('=');
                if (cut > 0) v.put(line.substring(0, cut), line.substring(cut + 1).trim());
            }
        } catch (IOException | RuntimeException e) {
            return i;
        }
        if (!"1".equals(v.get("version"))) return i;
        i.darkosen = text(v, "darkosen"); i.origin = text(v, "origin"); i.transport = text(v, "transport");
        i.sourceId = text(v, "source_id"); i.partsDir = text(v, "parts_dir");
        i.revision = number(v.get("revision")); i.capacity = number(v.get("partition_capacity"));
        i.partsFree = number(v.get("parts_free")); i.partition = "1".equals(v.get("partition"));
        return i;
    }

    private static String text(Map<String, String> v, String key) { final String s = v.get(key); return s == null ? "" : s; }
    private static long number(String s) { try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return 0; } }

    /** The worker orders releases by date (MMDDYYYY) and then revision; so does the app. */
    static String rank(String darkosen, long revision) {
        if (darkosen == null || !darkosen.matches("[0-9]{8}")) return "";
        return darkosen.substring(4) + darkosen.substring(0, 2) + darkosen.substring(2, 4)
                + String.format(Locale.ROOT, "%010d", revision);
    }

    /** True when the offer should replace what is installed. An own image is never "older". */
    static boolean newer(Offer offer, Installed installed) {
        if (!installed.present()) return true;
        if (!installed.release()) return false;
        final String a = rank(offer.darkosen, offer.revision), b = rank(installed.darkosen, installed.revision);
        return !a.isEmpty() && !b.isEmpty() && a.compareTo(b) > 0;
    }

    /** The newest full release, or null when there is none. Pre-releases are still being tested. */
    Offer check() throws IOException {
        final byte[] listing = fetch("https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=30", 8 << 20, "application/vnd.github+json");
        try {
            final JSONArray releases = new JSONArray(new String(listing, StandardCharsets.UTF_8));
            for (int r = 0; r < releases.length(); r++) {
                final JSONObject release = releases.getJSONObject(r);
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
                final JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;
                for (int a = 0; a < assets.length(); a++) {
                    final JSONObject asset = assets.getJSONObject(a);
                    final long size = asset.optLong("size");
                    final String url = asset.optString("browser_download_url");
                    if (!"manifest.json".equals(asset.optString("name")) || size <= 0 || size > 1 << 20 || !url.startsWith(DOWNLOADS)) continue;
                    final byte[] manifest = fetch(url, (int) size, null);
                    final String digest = asset.optString("digest");
                    if (manifest.length != size || (digest.startsWith("sha256:") && !digest.equals("sha256:" + sha256(manifest)))) {
                        throw new IOException("manifest changed in transit");
                    }
                    return parse(new String(manifest, StandardCharsets.UTF_8), assets);
                }
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return null;
    }

    private static Offer parse(String manifest, JSONArray assets) throws JSONException, IOException {
        final JSONObject j = new JSONObject(manifest);
        final JSONObject image = j.getJSONObject("image"), download = j.getJSONObject("download");
        final Offer o = new Offer();
        o.darkosen = j.getString("darkosen"); o.revision = j.getLong("revision"); o.minimum = j.optString("minimum_andr36oid");
        o.imageName = image.getString("name"); o.imageSize = image.getLong("size"); o.imageSha = image.getString("sha256");
        o.downloadSize = download.getLong("size"); o.downloadSha = download.getString("sha256");
        final JSONArray parts = download.getJSONArray("parts");
        // 78 bytes: the worker's request name (image name + ".request.json") has to fit a property.
        if (j.getInt("format") != 1 || !"xz".equals(download.getString("compression")) || rank(o.darkosen, o.revision).isEmpty()
                || o.revision < 1 || !plain(o.imageName) || o.imageName.length() > 78 || !hash(o.imageSha) || !hash(o.downloadSha)
                || o.imageSize <= 0 || o.imageSize > 64L << 30 || parts.length() == 0 || parts.length() > 64) {
            throw new IOException("unsupported release description");
        }
        long total = 0;
        final Set<String> seen = new HashSet<>();
        for (int p = 0; p < parts.length(); p++) {
            final JSONObject part = parts.getJSONObject(p);
            final String name = part.getString("name"), sum = part.getString("sha256");
            final long size = part.getLong("size");
            String url = null;
            for (int a = 0; a < assets.length(); a++) {
                final JSONObject asset = assets.getJSONObject(a);
                if (!name.equals(asset.optString("name"))) continue;
                final String digest = asset.optString("digest");
                if (asset.optLong("size") != size || (digest.startsWith("sha256:") && !digest.equals("sha256:" + sum))) {
                    throw new IOException("release files do not match their description");
                }
                url = asset.optString("browser_download_url");
            }
            if (!plain(name) || !seen.add(name) || size <= 0 || !hash(sum) || url == null || !url.startsWith(DOWNLOADS)) {
                throw new IOException("unsupported release description");
            }
            total += size;
            o.parts.add(new String[] {name, Long.toString(size), sum, url});
        }
        if (total != o.downloadSize) throw new IOException("unsupported release description");
        // The worker reads exactly these fields; nothing else from the manifest reaches it.
        o.json = new JSONObject().put("format", 1).put("darkosen", o.darkosen).put("revision", o.revision)
                .put("minimum_andr36oid", o.minimum)
                .put("image", new JSONObject().put("name", o.imageName).put("size", o.imageSize).put("sha256", o.imageSha));
        final JSONArray list = new JSONArray();
        for (String[] part : o.parts) list.put(new JSONObject().put("name", part[0]).put("size", Long.parseLong(part[1])).put("sha256", part[2]));
        o.json.put("download", new JSONObject().put("compression", "xz").put("size", o.downloadSize).put("sha256", o.downloadSha).put("parts", list));
        return o;
    }

    private static boolean plain(String s) {
        return s != null && !s.isEmpty() && s.length() <= 128 && s.matches("[A-Za-z0-9._+-]+") && !s.startsWith(".");
    }
    private static boolean hash(String s) { return s != null && s.matches("[0-9a-f]{64}"); }

    private static String sha256(byte[] data) {
        try {
            final StringBuilder out = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] fetch(String address, int limit, String accept) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "andr36oid");
            if (accept != null) c.setRequestProperty("Accept", accept);
            if (c.getResponseCode() != 200) throw new IOException("server answered " + c.getResponseCode());
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[65536];
            try (InputStream in = c.getInputStream()) {
                for (int n; (n = in.read(buffer)) > 0; ) {
                    if (out.size() + n > limit) throw new IOException("answer too large");
                    out.write(buffer, 0, n);
                }
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** True when the release names a newer andr36oid than this one. The worker checks again. */
    static boolean needsNewerSystem(Offer offer) {
        final String want = stamp(offer.minimum), have = stamp(SystemProperties.get("ro.build.version.incremental"));
        return !want.isEmpty() && !have.isEmpty() && have.compareTo(want) < 0;
    }
    private static String stamp(String s) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|\\.)([0-9]{8})(?:\\.([0-9]{6}))?$").matcher(s == null ? "" : s);
        return m.find() ? m.group(1) + (m.group(2) == null ? "000000" : m.group(2)) : "";
    }

    /** Bytes the games card needs free: the packed files, the unpacked image and some room. */
    static long required(Offer offer) { return offer.downloadSize + offer.imageSize + HEADROOM; }

    boolean batteryTooLow() {
        final Intent battery = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 100), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0 && scale > 0 && level * 100 / scale < 30;
    }

    boolean active() { return !mPrefs.getString("phase", "").isEmpty(); }

    /** Starts the download. Returns "" or the reason it cannot start. */
    String start(Offer offer, Installed installed) {
        if (active()) return "busy";
        if (needsNewerSystem(offer)) return "incompatible_build";
        if (batteryTooLow()) return "battery";
        if (installed.partsDir.isEmpty()) return "card_unavailable";
        final File dir = new File(installed.partsDir);
        if (!dir.isDirectory() && !dir.mkdirs()) return "card_unavailable";
        try {
            long have = 0;
            final StringBuilder ids = new StringBuilder();
            final DownloadManager dm = mContext.getSystemService(DownloadManager.class);
            if (dm == null) return "download";
            final List<String[]> wanted = new ArrayList<>();
            for (String[] part : offer.parts) {
                final File file = new File(dir, part[0]);
                // A complete part from an interrupted run is kept; the worker checks every byte anyway.
                if (file.isFile() && file.length() == Long.parseLong(part[1])) { have += file.length(); continue; }
                if (file.exists() && !file.delete()) return "card_unavailable";
                wanted.add(part);
            }
            if (dir.getUsableSpace() + have < required(offer)) return "no_space";
            for (String[] part : wanted) {
                final DownloadManager.Request request = new DownloadManager.Request(Uri.parse(part[3]))
                        .setTitle(mContext.getString(R.string.rel_title))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationUri(Uri.fromFile(new File(dir, part[0])));
                ids.append(ids.length() == 0 ? "" : ",").append(dm.enqueue(request));
            }
            mPrefs.edit().putString("phase", "download").putString("ids", ids.toString()).putString("dir", dir.getAbsolutePath())
                    .putString("request", offer.json.toString()).putString("image", offer.imageName)
                    .putString("version", offer.darkosen).putLong("have", have).putLong("total", offer.downloadSize)
                    .putBoolean("partition", installed.partition && offer.imageSize <= installed.capacity)
                    .remove("error").remove("job").commit();
            return "";
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot start the download", e);
            return "download";
        }
    }

    /** Moves the work along and says where it stands. */
    State poll() {
        final State s = new State();
        final String phase = mPrefs.getString("phase", "");
        s.version = mPrefs.getString("version", "");
        if (phase.isEmpty()) return s;
        s.active = true;
        try {
            switch (phase) {
                case "download": download(s); break;
                case "install": worker(s, false); break;
                case "apply": worker(s, true); break;
                default:
                    s.finished = true; s.success = "done".equals(phase);
                    s.error = mPrefs.getString("error", ""); s.storedAsFile = mPrefs.getBoolean("as_file", false);
                    s.step = mPrefs.getInt("step", FINISHING);
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Update step failed", e);
            fail(s, "io_error", s.step);
        }
        return s;
    }

    private void download(State s) throws IOException, JSONException {
        s.step = DOWNLOADING; s.cancellable = true;
        s.total = mPrefs.getLong("total", 0); s.done = mPrefs.getLong("have", 0);
        final DownloadManager dm = mContext.getSystemService(DownloadManager.class);
        if (dm == null) throw new IOException("no download service");
        boolean complete = true;
        for (String id : mPrefs.getString("ids", "").split(",")) {
            if (id.isEmpty()) continue;
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(Long.parseLong(id)))) {
                if (c == null || !c.moveToFirst()) { fail(s, "download", DOWNLOADING); return; }
                final int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_FAILED) { fail(s, "download", DOWNLOADING); return; }
                s.done += Math.max(0, c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                complete &= status == DownloadManager.STATUS_SUCCESSFUL;
            }
        }
        if (!complete) return;
        // All files are on the card: hand them to the worker with a request it can check.
        final String job = token();
        final JSONObject request = new JSONObject(mPrefs.getString("request", "")).put("job", job);
        final String name = mPrefs.getString("image", "") + ".request.json";
        final File dir = new File(mPrefs.getString("dir", "")), temporary = new File(dir, name + ".tmp"), file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        mPrefs.edit().putString("phase", "install").putString("job", job).remove("ids").commit();
        if (!SubsystemBackend.set(P + "rel_request", name) || !SubsystemBackend.set(P + "rel_request_job", job)
                || !SubsystemBackend.set(SubsystemBackend.REQUEST, "install-release")) throw new IOException("cannot reach the worker");
        s.step = CHECKING; s.cancellable = true; s.done = 0; s.total = 0;
    }

    private void worker(State s, boolean apply) throws IOException {
        final String job = mPrefs.getString("job", ""), state = SubsystemBackend.get(P + "rel_state");
        final boolean mine = job.equals(SubsystemBackend.get(P + "rel_job"));
        s.step = !apply ? CHECKING : mine && ("setup".equals(state) || "checking".equals(state) || "restaging".equals(state)) ? FINISHING : WRITING;
        s.cancellable = !apply;
        if (mine) { s.done = number(SubsystemBackend.get(P + "rel_bytes")); s.total = number(SubsystemBackend.get(P + "rel_total")); }
        if (!job.equals(SubsystemBackend.get(P + "rel_done_job"))) return;
        if (!apply) new File(mPrefs.getString("dir", ""), mPrefs.getString("image", "") + ".request.json").delete();
        if (!"done".equals(state)) {
            final String error = SubsystemBackend.get(P + "rel_error");
            fail(s, error.isEmpty() ? "io_error" : error, s.step);
            return;
        }
        final String source = SubsystemBackend.get(P + "rel_image");
        if (!apply && mPrefs.getBoolean("partition", false) && hash(source)) {
            final String next = token();
            mPrefs.edit().putString("phase", "apply").putString("job", next).commit();
            if (!SubsystemBackend.set(P + "rel_source", source) || !SubsystemBackend.set(P + "rel_request_job", next)
                    || !SubsystemBackend.set(SubsystemBackend.REQUEST, "apply-release-to-partition")) throw new IOException("cannot reach the worker");
            s.step = WRITING; s.cancellable = false; s.done = 0; s.total = 0;
            return;
        }
        final boolean file = !"partition".equals(SubsystemBackend.get(P + "rel_transport"));
        mPrefs.edit().putString("phase", "done").putBoolean("as_file", apply && file).putInt("step", FINISHING).commit();
        s.finished = true; s.success = true; s.storedAsFile = apply && file; s.step = FINISHING;
    }

    private void fail(State s, String error, int step) {
        mPrefs.edit().putString("phase", "failed").putString("error", error).putInt("step", step).remove("ids").commit();
        s.finished = true; s.success = false; s.error = error; s.step = step; s.cancellable = false;
    }

    /** Stops before anything is written to the partition. Downloaded files stay for the next try. */
    void cancel() {
        final String phase = mPrefs.getString("phase", "");
        if ("download".equals(phase)) {
            final DownloadManager dm = mContext.getSystemService(DownloadManager.class);
            for (String id : mPrefs.getString("ids", "").split(",")) {
                try { if (dm != null && !id.isEmpty()) dm.remove(Long.parseLong(id)); } catch (RuntimeException ignored) { }
            }
            mPrefs.edit().clear().commit();
        } else if ("install".equals(phase)) {
            // The worker ends the job as "interrupted"; poll() then shows that result.
            SubsystemBackend.set(P + "rel_request_job", mPrefs.getString("job", ""));
            SubsystemBackend.set(SubsystemBackend.REQUEST, "cancel-release");
        }
    }

    /** The result has been read; the next visit starts fresh. */
    void acknowledge() {
        final String phase = mPrefs.getString("phase", "");
        if ("done".equals(phase) || "failed".equals(phase)) mPrefs.edit().clear().commit();
    }

    private static String token() {
        final byte[] raw = new byte[8];
        new SecureRandom().nextBytes(raw);
        final StringBuilder out = new StringBuilder();
        for (byte b : raw) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }
}
