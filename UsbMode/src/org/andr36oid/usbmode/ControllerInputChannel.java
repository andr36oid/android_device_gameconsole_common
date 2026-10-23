package org.andr36oid.usbmode;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

abstract class ControllerInputChannel implements AutoCloseable {
    private volatile boolean mClosed;
    private volatile LocalSocket mSocket;
    private final Thread mThread;

    ControllerInputChannel() {
        mThread = new Thread(this::run, "Controller input");
    }
    final void start() { mThread.start(); }
    abstract byte[] reply(byte[] frame);
    abstract void released();
    private void run() {
        while (!mClosed) {
            try (LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET)) {
                mSocket = socket;
                if (mClosed) return;
                socket.connect(new LocalSocketAddress("andr36oid-controller-keyboard"));
                socket.setSoTimeout(500);
                final InputStream input = socket.getInputStream();
                final OutputStream output = socket.getOutputStream();
                final byte[] frame = new byte[16];
                while (!mClosed) {
                    if (input.read(frame) != frame.length) throw new IOException("Keyboard frame");
                    output.write(reply(frame));
                }
            } catch (IOException e) {
                released();
                if (!mClosed) SystemClock.sleep(100);
            } finally { mSocket = null; }
        }
    }
    @Override public void close() {
        mClosed = true;
        final LocalSocket socket = mSocket;
        if (socket != null) try { socket.close(); } catch (IOException e) { }
        released();
    }
}
