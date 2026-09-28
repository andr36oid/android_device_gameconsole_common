package org.andr36oid.wifitransfer;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;

/**
 * Runs the transfer server on a PC for host-test.sh: two roots under a temp folder, the page
 * from assets/, PIN 1234. Prints the port, logs events, stops when stdin closes.
 */
public final class TestServer {

    public static void main(String[] args) throws Exception {
        final File base = new File(args[0]);
        final File assets = new File(args[1]);
        final Root roms = new Root("easyroms", "EASYROMS", new File(base, "roms"));
        final Root internal = new Root("internal", "Internal storage", new File(base, "internal"));
        roms.dir.mkdirs();
        internal.dir.mkdirs();
        final FileApi api = new FileApi(() -> Arrays.asList(roms, internal),
                name -> new FileInputStream(new File(assets, name)), new FileApi.Events() {
                    public void onSignedIn(String c) { log("signed in " + c); }
                    public void onWrongPin(String c, boolean l) { log("wrong pin " + c + " locked=" + l); }
                    public void onPinChanged(String p) { log("pin changed"); }
                    public void onUploadProgress(String c, String n, long d, long t) { }
                    public void onUploadDone(String c, Root r, String p, File f, long b) {
                        log("received " + r.id + "/" + p + " " + b);
                    }
                    public void onUploadFailed(String c, String n, String w) { log("failed " + n + ": " + w); }
                    public void onChanged(File f) { log("changed " + f); }
                });
        api.setPin("1234");
        final HttpServer server = new HttpServer(api);
        final int port = server.start(args.length > 2 ? Integer.parseInt(args[2]) : 18080);
        System.out.println("PORT " + port);
        System.out.flush();
        while (System.in.read() != -1) {
            // run until the test closes stdin
        }
        server.stop();
        log("stopped, running=" + server.isRunning());
    }

    private static void log(String s) {
        System.err.println("[server] " + s);
    }
}
