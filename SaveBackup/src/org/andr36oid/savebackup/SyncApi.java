package org.andr36oid.savebackup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Talks to the Syncthing app's REST API on this console (127.0.0.1:8384 by default, HTTPS
 * with a self-signed certificate, the API key from its config.xml). Plain Java, so the
 * host test can run it against a fake Syncthing.
 *
 * <p>Only loopback addresses are accepted, and only for them is the certificate not
 * checked: the key never leaves the console.
 */
final class SyncApi {

    static final class HttpException extends IOException {
        final int code;

        HttpException(int code, String message) {
            super("HTTP " + code + ": " + message);
            this.code = code;
        }
    }

    private static final int CONNECT_MS = 3000;
    private static final int READ_MS = 15000;

    private final String mBase;
    private final String mKey;

    /** @param base "https://127.0.0.1:8384" or "http://127.0.0.1:8384" */
    SyncApi(String base, String key) {
        mBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        mKey = key;
    }

    /** From the lines the helper writes (url=…, apikey=…); null if they're incomplete. */
    static SyncApi fromKeyFile(String text) {
        final Map<String, String> kv = Index.keyValues(text);
        final String url = kv.get("url"), key = kv.get("apikey");
        if (url == null || key == null || url.isEmpty() || key.isEmpty()) return null;
        if (!loopback(url)) return null;
        return new SyncApi(url.trim(), key.trim());
    }

    static boolean loopback(String url) {
        return url.matches("https?://(127\\.0\\.0\\.1|localhost|\\[::1\\])(:\\d+)?/?");
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Object get(String path) throws IOException {
        return parse(call("GET", path, null));
    }

    Object put(String path, Object body) throws IOException {
        return parse(call("PUT", path, Json.write(body)));
    }

    Object post(String path, Object body) throws IOException {
        return parse(call("POST", path, Json.write(body)));
    }

    void delete(String path) throws IOException {
        call("DELETE", path, null);
    }

    byte[] bytes(String path) throws IOException {
        return request("GET", path, null);
    }

    /** True when Syncthing answers. */
    boolean ping() {
        try {
            return "pong".equals(Json.str(Json.obj(get("/rest/system/ping")).get("ping")));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Object parse(String s) {
        final String t = s.trim();
        return t.isEmpty() ? null : Json.parse(t);
    }

    private String call(String method, String path, String body) throws IOException {
        return new String(request(method, path, body), StandardCharsets.UTF_8);
    }

    private byte[] request(String method, String path, String body) throws IOException {
        final HttpURLConnection c = open(path);
        try {
            c.setRequestMethod(method);
            c.setRequestProperty("X-API-Key", mKey);
            if (body != null) {
                final byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(b);
                }
            }
            final int code = c.getResponseCode();
            if (code / 100 != 2) {
                String msg = "";
                final InputStream err = c.getErrorStream();
                if (err != null) {
                    try (InputStream in = err) {
                        msg = new String(readAll(in), StandardCharsets.UTF_8).trim();
                    }
                }
                throw new HttpException(code, msg);
            }
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        if (!loopback(mBase)) throw new IOException("not a local address: " + mBase);
        final HttpURLConnection c = (HttpURLConnection) new URL(mBase + path).openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setUseCaches(false);
        if (c instanceof HttpsURLConnection) {
            final HttpsURLConnection s = (HttpsURLConnection) c;
            s.setSSLSocketFactory(trustAll());
            // the certificate is Syncthing's own, made for "syncthing"; we only ever
            // connect to 127.0.0.1 (checked above)
            s.setHostnameVerifier((host, session) -> true);
        }
        return c;
    }

    private static SSLSocketFactory sFactory;

    private static synchronized SSLSocketFactory trustAll() throws IOException {
        if (sFactory != null) return sFactory;
        try {
            final SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String auth) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String auth) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            sFactory = ctx.getSocketFactory();
            return sFactory;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
