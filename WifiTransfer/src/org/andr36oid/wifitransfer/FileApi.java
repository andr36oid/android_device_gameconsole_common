package org.andr36oid.wifitransfer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The web page and its file API: PIN sign-in, folder listing, upload, download, new folder,
 * rename and delete, all confined to the given roots. Plain Java so it can be tested on a
 * PC; the Android side only supplies the roots, the page assets and an {@link Events}.
 */
final class FileApi implements HttpServer.Handler {

    /** What happened, for the console screen, the notification and the media scanner. */
    interface Events {
        void onSignedIn(String client);

        void onWrongPin(String client, boolean lockedOut);

        /** Too many wrong PINs from anywhere: a new PIN was made. */
        void onPinChanged(String pin);

        void onUploadProgress(String client, String name, long done, long total);

        void onUploadDone(String client, Root root, String path, File file, long bytes);

        void onUploadFailed(String client, String name, String why);

        /** A file or folder was deleted, renamed or created (rescan it). */
        void onChanged(File file);
    }

    /** The web page files, e.g. from the APK's assets. */
    interface Assets {
        InputStream open(String name) throws IOException;
    }

    static final String COOKIE = "wt_session";
    /** Every change needs this header; a page on another site can't send it without
     *  asking first, and we never answer that question. */
    static final String CSRF_HEADER = "X-Requested-With";
    static final String CSRF_VALUE = "WifiTransfer";

    /** Wrong PINs in a row from one address before it has to wait. */
    static final int MAX_TRIES = 5;
    static final long LOCKOUT_MS = 60_000;
    /** Wrong PINs in total before the PIN is replaced. */
    static final int MAX_TOTAL_WRONG = 50;

    private static final String TEMP_SUFFIX = ".wtpart";
    private static final int BUFFER = 256 * 1024;
    private static final long PROGRESS_EVERY_MS = 300;
    /** Kept free on the card so the file system doesn't run completely full. */
    private static final long SPARE_BYTES = 1024 * 1024;

    private final Root.Source mRoots;
    private final Assets mAssets;
    private final Events mEvents;
    private final SecureRandom mRandom = new SecureRandom();
    private final Set<String> mSessions = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, int[]> mTries = new HashMap<>();
    private final Map<String, Long> mLockedUntil = new HashMap<>();
    private int mTotalWrong;
    private volatile String mPin;
    private volatile long mLastActivity = System.currentTimeMillis();

    FileApi(Root.Source roots, Assets assets, Events events) {
        mRoots = roots;
        mAssets = assets;
        mEvents = events;
        mPin = newPin();
    }

    String pin() {
        return mPin;
    }

    /** For tests: use a known PIN. */
    void setPin(String pin) {
        mPin = pin;
    }

    /** When the last request came in or the last upload made progress. */
    long lastActivity() {
        return mLastActivity;
    }

    private String newPin() {
        return String.format(Locale.ROOT, "%04d", mRandom.nextInt(10000));
    }

    @Override
    public void handle(HttpServer.Request req, HttpServer.Response resp) throws IOException {
        mLastActivity = System.currentTimeMillis();
        final String path = req.path;
        if (!path.startsWith("/api/")) {
            serveAsset(req, resp);
            return;
        }
        final boolean change = !req.method.equals("GET") && !req.method.equals("HEAD");
        if (change && !CSRF_VALUE.equals(req.header(CSRF_HEADER))) {
            throw new HttpServer.HttpException(403, "missing " + CSRF_HEADER + " header");
        }
        if (path.equals("/api/login")) {
            requireMethod(req, "POST");
            login(req, resp);
            return;
        }
        if (path.equals("/api/session")) {
            resp.sendJson(200, "{\"signedIn\":" + signedIn(req) + "}");
            return;
        }
        if (!signedIn(req)) {
            throw new HttpServer.HttpException(401, "Enter the PIN shown on the console");
        }
        switch (path) {
            case "/api/logout":
                requireMethod(req, "POST");
                mSessions.remove(req.cookie(COOKIE));
                resp.header("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
                resp.sendJson(200, "{\"ok\":true}");
                return;
            case "/api/roots":
                roots(resp);
                return;
            case "/api/list":
                list(req, resp);
                return;
            case "/api/download":
                download(req, resp);
                return;
            case "/api/upload":
                requireMethod(req, "PUT");
                upload(req, resp);
                return;
            case "/api/mkdir":
                requireMethod(req, "POST");
                mkdir(req, resp);
                return;
            case "/api/rename":
                requireMethod(req, "POST");
                rename(req, resp);
                return;
            case "/api/delete":
                requireMethod(req, "POST");
                delete(req, resp);
                return;
            default:
                throw new HttpServer.HttpException(404, "no such API");
        }
    }

    private static void requireMethod(HttpServer.Request req, String method)
            throws HttpServer.HttpException {
        if (!req.method.equals(method)) {
            throw new HttpServer.HttpException(405, "use " + method);
        }
    }

    // ---- Sign-in ----

    private boolean signedIn(HttpServer.Request req) {
        final String token = req.cookie(COOKIE);
        return token != null && mSessions.contains(token);
    }

    private void login(HttpServer.Request req, HttpServer.Response resp) throws IOException {
        final String form = new String(req.body.readSmall(1024), StandardCharsets.UTF_8);
        String pin = null;
        for (String pair : form.split("&")) {
            if (pair.startsWith("pin=")) pin = decode(pair.substring(4)).trim();
        }
        final long now = System.currentTimeMillis();
        String newPin = null;
        boolean lockedOut = false;
        synchronized (mTries) {
            final Long until = mLockedUntil.get(req.client);
            if (until != null && now < until) {
                final long secs = (until - now + 999) / 1000;
                resp.header("Retry-After", String.valueOf(secs));
                throw new HttpServer.HttpException(429,
                        "Too many wrong PINs. Try again in " + secs + " seconds.");
            }
            if (pin != null && constantTimeEquals(pin, mPin)) {
                mTries.remove(req.client);
                mLockedUntil.remove(req.client);
            } else {
                int[] tries = mTries.get(req.client);
                if (tries == null) mTries.put(req.client, tries = new int[1]);
                if (++tries[0] >= MAX_TRIES) {
                    tries[0] = 0;
                    mLockedUntil.put(req.client, now + LOCKOUT_MS);
                    lockedOut = true;
                }
                if (++mTotalWrong >= MAX_TOTAL_WRONG) {
                    mTotalWrong = 0;
                    mPin = newPin = newPin();
                }
            }
        }
        if (newPin != null) mEvents.onPinChanged(newPin);
        if (pin == null || !constantTimeEquals(pin, mPin) || newPin != null) {
            mEvents.onWrongPin(req.client, lockedOut);
            throw new HttpServer.HttpException(lockedOut ? 429 : 401, lockedOut
                    ? "Too many wrong PINs. Try again in a minute."
                    : "Wrong PIN. Check the number on the console screen.");
        }
        final byte[] raw = new byte[16];
        mRandom.nextBytes(raw);
        final StringBuilder token = new StringBuilder();
        for (byte b : raw) token.append(String.format("%02x", b & 0xff));
        mSessions.add(token.toString());
        resp.header("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
        resp.sendJson(200, "{\"ok\":true}");
        mEvents.onSignedIn(req.client);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    // ---- Paths ----

    private Root root(HttpServer.Request req) throws HttpServer.HttpException {
        final String id = req.param("root");
        for (Root r : mRoots.roots()) {
            if (r.id.equals(id)) return r;
        }
        throw new HttpServer.HttpException(404, "That storage isn't there any more");
    }

    /**
     * The file for a path below the root. Rejects "." and ".." parts and anything that would
     * end up outside the root through a link.
     */
    static File resolve(Root root, String path) throws IOException {
        File f = root.dir;
        if (path != null) {
            for (String part : path.split("/")) {
                if (part.isEmpty()) continue;
                if (part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0
                        || part.indexOf('\0') >= 0) {
                    throw new HttpServer.HttpException(400, "bad path");
                }
                f = new File(f, part);
            }
        }
        final String base = root.dir.getCanonicalPath();
        final String real = f.getCanonicalPath();
        if (!real.equals(base) && !real.startsWith(base.endsWith("/") ? base : base + "/")) {
            throw new HttpServer.HttpException(403, "outside the allowed folders");
        }
        return f;
    }

    /** Checks a new file or folder name; FAT and exFAT don't allow some characters. */
    static void checkName(String name) throws HttpServer.HttpException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new HttpServer.HttpException(400, "missing name");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new HttpServer.HttpException(400, "name too long: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0) {
                throw new HttpServer.HttpException(400,
                        "Names can't contain / \\ : * ? \" < > |  (" + name + ")");
            }
        }
        if (name.endsWith(" ") || name.endsWith(".")) {
            throw new HttpServer.HttpException(400,
                    "Names can't end with a space or a dot (" + name + ")");
        }
        if (name.endsWith(TEMP_SUFFIX)) {
            throw new HttpServer.HttpException(400, "Names can't end with " + TEMP_SUFFIX);
        }
    }

    private static String decode(String s) throws HttpServer.HttpException {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            throw new HttpServer.HttpException(400, "bad form encoding");
        }
    }

    // ---- Reading ----

    private void roots(HttpServer.Response resp) throws IOException {
        final StringBuilder sb = new StringBuilder("{\"roots\":[");
        boolean first = true;
        for (Root r : mRoots.roots()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(Json.quote(r.id))
                    .append(",\"name\":").append(Json.quote(r.name))
                    .append(",\"free\":").append(r.dir.getUsableSpace())
                    .append(",\"total\":").append(r.dir.getTotalSpace()).append('}');
        }
        resp.sendJson(200, sb.append("]}").toString());
    }

    private void list(HttpServer.Request req, HttpServer.Response resp) throws IOException {
        final Root root = root(req);
        final File dir = resolve(root, req.param("path"));
        if (!dir.isDirectory()) throw new HttpServer.HttpException(404, "No such folder");
        final File[] files = dir.listFiles();
        if (files == null) throw new HttpServer.HttpException(403, "Can't read this folder");
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        final StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"free\":").append(dir.getUsableSpace())
                .append(",\"total\":").append(dir.getTotalSpace())
                .append(",\"entries\":[");
        boolean first = true;
        for (File f : files) {
            final String name = f.getName();
            if (name.endsWith(TEMP_SUFFIX)) continue;
            if (!first) sb.append(',');
            first = false;
            final boolean isDir = f.isDirectory();
            sb.append("{\"name\":").append(Json.quote(name))
                    .append(",\"dir\":").append(isDir)
                    .append(",\"size\":").append(isDir ? 0 : f.length())
                    .append(",\"mtime\":").append(f.lastModified()).append('}');
        }
        resp.sendJson(200, sb.append("]}").toString());
    }

    private void download(HttpServer.Request req, HttpServer.Response resp) throws IOException {
        final Root root = root(req);
        final File file = resolve(root, req.param("path"));
        if (!file.isFile()) throw new HttpServer.HttpException(404, "No such file");
        final long size = file.length();
        long start = 0;
        long end = size - 1;
        int status = 200;
        final String range = req.header("range");
        if (range != null && range.startsWith("bytes=") && range.indexOf(',') < 0) {
            try {
                final String spec = range.substring(6).trim();
                final int dash = spec.indexOf('-');
                if (dash == 0) {
                    start = Math.max(0, size - Long.parseLong(spec.substring(1)));
                } else {
                    start = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) {
                        end = Math.min(end, Long.parseLong(spec.substring(dash + 1)));
                    }
                }
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                start = 0;
                end = size - 1;
            }
            if (start >= size || start > end) {
                resp.header("Content-Range", "bytes */" + size);
                throw new HttpServer.HttpException(416, "bad range");
            }
            status = 206;
            resp.header("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }
        resp.header("Accept-Ranges", "bytes");
        resp.header("Cache-Control", "no-store");
        resp.header("Content-Disposition", contentDisposition(file.getName()));
        final long length = size == 0 ? 0 : end - start + 1;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.seek(start);
            final OutputStream out = resp.start(status, "application/octet-stream", length);
            final byte[] buf = new byte[BUFFER];
            long left = length;
            while (left > 0) {
                final int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
                mLastActivity = System.currentTimeMillis();
            }
        }
    }

    private static String contentDisposition(String name) {
        final StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            ascii.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
        }
        String utf8;
        try {
            utf8 = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            utf8 = ascii.toString();
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8;
    }

    // ---- Changes ----

    /**
     * PUT /api/upload?root=&dir=&name=[&overwrite=1] with the file as the raw body. The name
     * may contain folders ("Game/disc1.bin") for folder uploads. The body goes to a hidden
     * temporary file in blocks and is renamed when complete, so a broken upload never leaves
     * a half file behind under the real name.
     */
    private void upload(HttpServer.Request req, HttpServer.Response resp) throws IOException {
        final Root root = root(req);
        final String name = req.param("name");
        if (name == null) throw new HttpServer.HttpException(400, "missing name");
        final List<String> parts = new ArrayList<>();
        for (String p : name.split("/")) {
            if (p.isEmpty()) continue;
            checkName(p);
            parts.add(p);
        }
        if (parts.isEmpty()) throw new HttpServer.HttpException(400, "missing name");
        final long length = req.contentLength;
        if (length < 0) throw new HttpServer.HttpException(411, "missing Content-Length");

        final String dirPath = req.param("dir") == null ? "" : req.param("dir");
        final String relPath = (dirPath.isEmpty() ? "" : dirPath + "/") + String.join("/", parts);
        final File target = resolve(root, relPath);
        final File parent = target.getParentFile();
        final String shown = parts.get(parts.size() - 1);
        if (target.isDirectory()) {
            throw new HttpServer.HttpException(409, "A folder called " + shown + " is already there");
        }
        if (target.exists() && !"1".equals(req.param("overwrite"))) {
            throw new HttpServer.HttpException(409, "exists");
        }
        if (!parent.isDirectory()) {
            if (!parent.mkdirs() && !parent.isDirectory()) {
                throw new HttpServer.HttpException(409, "Can't make the folder for " + shown);
            }
            mEvents.onChanged(parent);
        }
        final long free = parent.getUsableSpace();
        final long existing = target.isFile() ? target.length() : 0;
        if (length + SPARE_BYTES > free + existing) {
            throw new HttpServer.HttpException(507, "Not enough space for " + shown + ": "
                    + formatSize(length) + " needed, " + formatSize(free) + " free");
        }

        final String tempName = "." + shown + TEMP_SUFFIX;
        final File temp = new File(parent, tempName.getBytes(StandardCharsets.UTF_8).length <= 255
                ? tempName : ".upload-" + System.nanoTime() + TEMP_SUFFIX);
        boolean done = false;
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                final byte[] buf = new byte[BUFFER];
                long got = 0;
                long lastReport = 0;
                mEvents.onUploadProgress(req.client, shown, 0, length);
                while (got < length) {
                    final int n = req.body.read(buf, 0, (int) Math.min(buf.length, length - got));
                    if (n < 0) throw new IOException("upload cut off");
                    out.write(buf, 0, n);
                    got += n;
                    final long now = System.currentTimeMillis();
                    if (now - lastReport >= PROGRESS_EVERY_MS) {
                        lastReport = now;
                        mLastActivity = now;
                        mEvents.onUploadProgress(req.client, shown, got, length);
                    }
                }
                // On the card before we say it arrived: people switch off right after
                out.getFD().sync();
            }
            if (!temp.renameTo(target)) {
                // Some file systems don't replace on rename
                if (!target.delete() || !temp.renameTo(target)) {
                    throw new IOException("can't rename " + temp.getName());
                }
            }
            done = true;
        } catch (IOException e) {
            final String why = uploadError(e);
            mEvents.onUploadFailed(req.client, shown, why);
            throw new HttpServer.HttpException(e instanceof HttpServer.HttpException
                    ? ((HttpServer.HttpException) e).status : 500, why);
        } finally {
            if (!done) temp.delete();
        }
        mEvents.onUploadDone(req.client, root, relPath, target, length);
        resp.sendJson(200, "{\"ok\":true,\"name\":" + Json.quote(shown)
                + ",\"size\":" + length + "}");
    }

    private static String uploadError(IOException e) {
        final String msg = String.valueOf(e.getMessage());
        if (msg.contains("EFBIG") || msg.contains("File too large")) {
            return "The card is formatted as FAT32, which can't hold files of 4 GB or more";
        }
        if (msg.contains("ENOSPC") || msg.contains("No space")) {
            return "The card is full";
        }
        return msg;
    }

    private void mkdir(HttpServer.Request req, HttpServer.Response resp) throws IOException {
        final Root root = root(req);
        final String path = req.param("path");
        if (path == null) throw new HttpServer.HttpException(400, "missing path");
        final String name = path.substring(path.lastIndexOf('/') + 1);
        checkName(name);
        final File dir = resolve(root, path);
        if (dir.exists()) throw new HttpServer.HttpException(409, name + " is already there");
        if (!dir.mkdirs()) throw new HttpServer.HttpException(500, "Can't make " + name);
        mEvents.onChanged(dir);
        resp.sendJson(200, "{\"ok\":true}");
    }

    private void rename(HttpServer.Request req, HttpServer.Response resp) throws IOException {
        final Root root = root(req);
        final File from = resolve(root, req.param("path"));
        final String to = req.param("to");
        checkName(to);
        if (from.equals(root.dir)) throw new HttpServer.HttpException(403, "Can't rename that");
        if (!from.exists()) throw new HttpServer.HttpException(404, "It isn't there any more");
        final File target = new File(from.getParentFile(), to);
        if (target.exists() && !target.getName().equalsIgnoreCase(from.getName())) {
            throw new HttpServer.HttpException(409, to + " is already there");
        }
        if (!from.renameTo(target)) throw new HttpServer.HttpException(500, "Can't rename");
        mEvents.onChanged(from);
        mEvents.onChanged(target);
        resp.sendJson(200, "{\"ok\":true}");
    }

    private void delete(HttpServer.Request req, HttpServer.Response resp) throws IOException {
        final Root root = root(req);
        final File file = resolve(root, req.param("path"));
        if (file.equals(root.dir) || file.getCanonicalFile().equals(root.dir.getCanonicalFile())) {
            throw new HttpServer.HttpException(403, "Can't delete the whole storage");
        }
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) {
            throw new HttpServer.HttpException(404, "It isn't there any more");
        }
        if (!deleteTree(file)) throw new HttpServer.HttpException(500, "Couldn't delete everything");
        mEvents.onChanged(file);
        resp.sendJson(200, "{\"ok\":true}");
    }

    /** Deletes a file or a folder with everything in it; links are removed, not followed. */
    private static boolean deleteTree(File f) {
        boolean ok = true;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            final File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) ok &= deleteTree(c);
            }
        }
        return f.delete() && ok;
    }

    // ---- Page ----

    private void serveAsset(HttpServer.Request req, HttpServer.Response resp) throws IOException {
        if (!req.method.equals("GET") && !req.method.equals("HEAD")) {
            throw new HttpServer.HttpException(405, "use GET");
        }
        String name = req.path.equals("/") ? "index.html" : req.path.substring(1);
        if (!name.matches("[a-z0-9-]+\\.(html|js|css|svg)")) {
            throw new HttpServer.HttpException(404, "not found");
        }
        final byte[] data;
        try (InputStream in = mAssets.open(name)) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            data = buf.toByteArray();
        } catch (IOException e) {
            throw new HttpServer.HttpException(404, "not found");
        }
        final String type;
        if (name.endsWith(".html")) type = "text/html; charset=utf-8";
        else if (name.endsWith(".js")) type = "text/javascript; charset=utf-8";
        else if (name.endsWith(".css")) type = "text/css; charset=utf-8";
        else type = "image/svg+xml";
        resp.header("Cache-Control", "no-cache");
        resp.header("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; "
                + "frame-ancestors 'none'");
        resp.send(200, type, data);
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        final String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }
}
