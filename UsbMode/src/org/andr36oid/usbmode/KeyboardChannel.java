package org.andr36oid.usbmode;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class KeyboardChannel implements AutoCloseable {
    private final KeyboardModel mModel;
    private final KeyboardView mView;
    private volatile boolean mClosed;
    private volatile LocalSocket mSocket;
    private final Thread mThread;
    interface Frames { boolean received(byte[] frame); }
    private final Frames mFrames;

    KeyboardChannel(KeyboardModel model, KeyboardView view, Frames frames) {
        mFrames = frames;
        mModel = model; mView = view;
        mThread = new Thread(this::run, "Controller keyboard");
        mThread.start();
    }
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
                    synchronized (mModel) {
                        if (mFrames.received(frame)) { final byte[] neutral = new byte[16]; neutral[0] = 1; output.write(neutral); }
                        else output.write(mModel.update(frame, SystemClock.uptimeMillis()));
                        mView.setLeds(frame[12] & 255);
                    }
                    mView.postInvalidate();
                }
            } catch (IOException e) {
                synchronized (mModel) { mModel.reset(); }
                mView.setLeds(0); mView.postInvalidate();
                if (!mClosed) SystemClock.sleep(100);
            } finally { mSocket = null; }
        }
    }
    @Override public void close() {
        mClosed = true;
        final LocalSocket socket = mSocket;
        if (socket != null) try { socket.close(); } catch (IOException e) { }
        synchronized (mModel) { mModel.reset(); }
    }
}
