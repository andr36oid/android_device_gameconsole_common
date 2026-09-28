package org.andr36oid.wifitransfer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A small HTTP/1.1 server: one request per connection (Connection: close), a fixed pool of
 * worker threads, request bodies handed to the handler as a stream so big uploads go
 * straight to disk. Plain Java, no Android classes, so it can be tested on a PC.
 */
final class HttpServer {

    /** Answers one request. May throw {@link HttpException} for a plain error reply. */
    interface Handler {
        void handle(Request request, Response response) throws IOException;
    }

    /** An error answer with a status code and a short message for the user. */
    static final class HttpException extends IOException {
        final int status;

        HttpException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final int THREADS = 8;
    private static final int QUEUE = 32;
    private static final int MAX_LINE = 8192;
    private static final int MAX_HEADERS = 100;
    /** A stalled client (phone asleep, Wi-Fi gone) gives up its thread after this. */
    private static final int READ_TIMEOUT_MS = 60_000;
    /** Unread request body we still swallow after an early reply, at most this much for at
     *  most this long. */
    private static final long DRAIN_LIMIT = 64L * 1024 * 1024;
    private static final int LINGER_MS = 2000;

    private final Handler mHandler;
    private final Set<Socket> mClients = Collections.synchronizedSet(new HashSet<>());
    private ServerSocket mServer;
    private ThreadPoolExecutor mPool;
    private Thread mAcceptor;

    HttpServer(Handler handler) {
        mHandler = handler;
    }

    /**
     * Starts listening on all interfaces, on the preferred port or one of the next few, or
     * any free port. Returns the port.
     */
    synchronized int start(int preferredPort) throws IOException {
        if (mServer != null) return mServer.getLocalPort();
        ServerSocket server = null;
        for (int port = preferredPort; server == null && port < preferredPort + 10; port++) {
            server = bind(port);
        }
        if (server == null) server = bind(0);
        if (server == null) throw new IOException("no free port");
        mServer = server;
        mPool = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE), r -> {
                    final Thread t = new Thread(r, "WifiTransferHttp");
                    t.setDaemon(true);
                    return t;
                });
        mPool.allowCoreThreadTimeOut(true);
        final ServerSocket s = server;
        final ThreadPoolExecutor pool = mPool;
        mAcceptor = new Thread(() -> acceptLoop(s, pool), "WifiTransferAccept");
        mAcceptor.setDaemon(true);
        mAcceptor.start();
        return server.getLocalPort();
    }

    private static ServerSocket bind(int port) {
        final ServerSocket server;
        try {
            server = new ServerSocket();
        } catch (IOException e) {
            return null;
        }
        try {
            server.setReuseAddress(true);
            // A big window keeps Wi-Fi uploads fast
            server.setReceiveBufferSize(512 * 1024);
            server.bind(new InetSocketAddress(port), 16);
            return server;
        } catch (IOException e) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    /** Stops listening and cuts every open connection, so running uploads end too. */
    synchronized void stop() {
        if (mServer == null) return;
        try {
            mServer.close();
        } catch (IOException ignored) {
        }
        mServer = null;
        synchronized (mClients) {
            for (Socket s : mClients) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            mClients.clear();
        }
        mPool.shutdownNow();
        mPool = null;
        mAcceptor = null;
    }

    synchronized boolean isRunning() {
        return mServer != null;
    }

    synchronized int port() {
        return mServer != null ? mServer.getLocalPort() : -1;
    }

    private void acceptLoop(ServerSocket server, ThreadPoolExecutor pool) {
        while (!server.isClosed()) {
            final Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                break;
            }
            try {
                pool.execute(() -> serve(socket));
            } catch (RejectedExecutionException e) {
                closeQuietly(socket);
            }
        }
    }

    private void serve(Socket socket) {
        mClients.add(socket);
        try {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            final InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            final OutputStream out = socket.getOutputStream();
            final Request request = Request.read(in,
                    socket.getInetAddress().getHostAddress());
            if (request == null) return;
            final Response response = new Response(out, request.method.equals("HEAD"));
            try {
                mHandler.handle(request, response);
            } catch (HttpException e) {
                if (!response.committed()) {
                    response.sendJsonError(e.status, e.getMessage());
                }
            } catch (SocketException e) {
                // Client went away or the server stopped
                return;
            } catch (IOException | RuntimeException e) {
                if (!response.committed()) {
                    response.sendJsonError(500, String.valueOf(e.getMessage()));
                }
            }
            response.finish();
            if (request.body.remaining() > 0) {
                // Replied before reading the whole body (an error, e.g. the file exists).
                // Close our side, then swallow what's in flight for a moment so the
                // client reads the reply instead of a connection reset.
                socket.shutdownOutput();
                socket.setSoTimeout(LINGER_MS);
                request.body.drain(DRAIN_LIMIT, LINGER_MS);
            }
        } catch (HttpException e) {
            // Bad request line or headers
            try {
                new Response(socket.getOutputStream(), false).sendJsonError(e.status,
                        e.getMessage());
            } catch (IOException ignored) {
            }
        } catch (IOException ignored) {
        } finally {
            mClients.remove(socket);
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /** A parsed request. The body is limited to Content-Length. */
    static final class Request {
        final String method;
        /** Decoded path without the query, e.g. "/api/list". */
        final String path;
        final Map<String, String> query;
        /** Header names in lower case. */
        final Map<String, String> headers;
        final String client;
        /** Content-Length, -1 if not given. */
        final long contentLength;
        final Body body;

        private Request(String method, String path, Map<String, String> query,
                Map<String, String> headers, String client, long contentLength, Body body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.client = client;
            this.contentLength = contentLength;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        String param(String name) {
            return query.get(name);
        }

        String cookie(String name) {
            final String cookies = header("cookie");
            if (cookies == null) return null;
            for (String part : cookies.split(";")) {
                final int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equals(name)) {
                    return part.substring(eq + 1).trim();
                }
            }
            return null;
        }

        /** Reads the request line and headers; null if the client closed without sending. */
        static Request read(InputStream in, String client) throws IOException {
            String line = readLine(in);
            if (line == null) return null;
            // Tolerate stray empty lines between requests (RFC 7230 3.5)
            while (line.isEmpty()) {
                line = readLine(in);
                if (line == null) return null;
            }
            final String[] parts = line.split(" ");
            if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
                throw new HttpException(400, "bad request line");
            }
            final Map<String, String> headers = new HashMap<>();
            for (int i = 0; ; i++) {
                final String h = readLine(in);
                if (h == null) throw new HttpException(400, "headers cut off");
                if (h.isEmpty()) break;
                if (i >= MAX_HEADERS) throw new HttpException(431, "too many headers");
                final int colon = h.indexOf(':');
                if (colon <= 0) throw new HttpException(400, "bad header");
                headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        h.substring(colon + 1).trim());
            }
            if (headers.containsKey("transfer-encoding")) {
                // Browsers send Content-Length for files; chunked bodies aren't supported
                throw new HttpException(411, "chunked request bodies are not supported");
            }
            long length = -1;
            final String cl = headers.get("content-length");
            if (cl != null) {
                try {
                    length = Long.parseLong(cl);
                } catch (NumberFormatException e) {
                    throw new HttpException(400, "bad Content-Length");
                }
                if (length < 0) throw new HttpException(400, "bad Content-Length");
            }
            final String target = parts[1];
            final int q = target.indexOf('?');
            final String rawPath = q >= 0 ? target.substring(0, q) : target;
            final Map<String, String> query = new LinkedHashMap<>();
            if (q >= 0) {
                for (String pair : target.substring(q + 1).split("&")) {
                    if (pair.isEmpty()) continue;
                    final int eq = pair.indexOf('=');
                    final String k = eq >= 0 ? pair.substring(0, eq) : pair;
                    final String v = eq >= 0 ? pair.substring(eq + 1) : "";
                    query.put(decode(k), decode(v));
                }
            }
            return new Request(parts[0].toUpperCase(Locale.ROOT), decode(rawPath), query,
                    headers, client, length, new Body(in, Math.max(length, 0)));
        }

        private static String decode(String s) throws HttpException {
            try {
                return URLDecoder.decode(s, "UTF-8");
            } catch (IllegalArgumentException | IOException e) {
                throw new HttpException(400, "bad URL encoding");
            }
        }

        /** One CRLF (or LF) terminated ISO-8859-1 line, null at end of stream. */
        private static String readLine(InputStream in) throws IOException {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    final byte[] b = buf.toByteArray();
                    int n = b.length;
                    if (n > 0 && b[n - 1] == '\r') n--;
                    // Request lines and headers are ASCII; the URL is percent-encoded
                    return new String(b, 0, n, StandardCharsets.UTF_8);
                }
                if (buf.size() >= MAX_LINE) throw new HttpException(431, "line too long");
                buf.write(c);
            }
            if (buf.size() == 0) return null;
            throw new HttpException(400, "line cut off");
        }
    }

    /** Request body: at most Content-Length bytes of the connection. */
    static final class Body extends FilterInputStream {
        private long mLeft;

        Body(InputStream in, long length) {
            super(in);
            mLeft = length;
        }

        long remaining() {
            return mLeft;
        }

        @Override
        public int read() throws IOException {
            if (mLeft <= 0) return -1;
            final int c = in.read();
            if (c >= 0) mLeft--;
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (mLeft <= 0) return -1;
            final int n = in.read(b, off, (int) Math.min(len, mLeft));
            if (n > 0) mLeft -= n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            final long s = in.skip(Math.min(n, mLeft));
            if (s > 0) mLeft -= s;
            return s;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), mLeft);
        }

        @Override
        public void close() {
            // The connection is closed by the server
        }

        /** Reads a small body completely, e.g. a login form. */
        byte[] readSmall(int max) throws IOException {
            if (mLeft > max) throw new HttpException(413, "request too large");
            final byte[] b = new byte[(int) mLeft];
            int off = 0;
            while (off < b.length) {
                final int n = read(b, off, b.length - off);
                if (n < 0) throw new HttpException(400, "request cut off");
                off += n;
            }
            return b;
        }

        void drain(long limit, long millis) {
            final long end = System.currentTimeMillis() + millis;
            try {
                final byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while (total < limit && System.currentTimeMillis() < end
                        && (n = read(buf, 0, buf.length)) > 0) {
                    total += n;
                }
            } catch (IOException ignored) {
            }
        }
    }

    /** Builds the reply. Headers go out with the first body bytes. */
    static final class Response {
        private final OutputStream mOut;
        private final boolean mHeadOnly;
        private final Map<String, String> mHeaders = new LinkedHashMap<>();
        private boolean mCommitted;

        Response(OutputStream out, boolean headOnly) {
            mOut = out;
            mHeadOnly = headOnly;
        }

        boolean committed() {
            return mCommitted;
        }

        Response header(String name, String value) {
            mHeaders.put(name, value);
            return this;
        }

        /** Sends status and headers; the body follows on the returned stream. */
        OutputStream start(int status, String contentType, long length) throws IOException {
            if (mCommitted) throw new IllegalStateException("already sent");
            mCommitted = true;
            final StringBuilder sb = new StringBuilder(256);
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status))
                    .append("\r\n");
            if (contentType != null) sb.append("Content-Type: ").append(contentType)
                    .append("\r\n");
            if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n");
            sb.append("Connection: close\r\n");
            sb.append("X-Content-Type-Options: nosniff\r\n");
            sb.append("Referrer-Policy: no-referrer\r\n");
            for (Map.Entry<String, String> e : mHeaders.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            sb.append("\r\n");
            mOut.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            return mHeadOnly ? new OutputStream() {
                @Override
                public void write(int b) {
                }

                @Override
                public void write(byte[] b, int off, int len) {
                }
            } : mOut;
        }

        void send(int status, String contentType, byte[] body) throws IOException {
            start(status, contentType, body.length).write(body);
        }

        void sendJson(int status, String json) throws IOException {
            header("Cache-Control", "no-store");
            send(status, "application/json; charset=utf-8",
                    json.getBytes(StandardCharsets.UTF_8));
        }

        void sendJsonError(int status, String message) throws IOException {
            sendJson(status, "{\"error\":" + Json.quote(message) + "}");
        }

        void finish() throws IOException {
            mOut.flush();
        }

        private static String reason(int status) {
            switch (status) {
                case 200: return "OK";
                case 201: return "Created";
                case 204: return "No Content";
                case 206: return "Partial Content";
                case 400: return "Bad Request";
                case 401: return "Unauthorized";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 409: return "Conflict";
                case 411: return "Length Required";
                case 413: return "Payload Too Large";
                case 416: return "Range Not Satisfiable";
                case 429: return "Too Many Requests";
                case 431: return "Request Header Fields Too Large";
                case 507: return "Insufficient Storage";
                default: return status < 400 ? "OK" : "Error";
            }
        }
    }
}
