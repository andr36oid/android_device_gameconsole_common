package org.andr36oid.usbmode;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import java.io.IOException;

final class UsbAudio implements AutoCloseable {
    private volatile boolean mClosed;
    private volatile LocalSocket mSocket;
    private final Soundboard mBoard;
    UsbAudio(Soundboard board) { mBoard = board; new Thread(this::run,"USB sound card").start(); }
    private void run() {
        while (!mClosed) {
            try (LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET)) {
                mSocket = socket; if (mClosed) return;
                socket.connect(new LocalSocketAddress("andr36oid-controller-audio")); socket.setSoTimeout(0);
                final Thread capture = mBoard == null ? null : new Thread(() -> {
                    long next = SystemClock.uptimeMillis();
                    try {
                        while (!mClosed && mSocket == socket) {
                            socket.getOutputStream().write(mBoard.mixer.render(192,mBoard.microphone(192)));
                            next += 4; long wait = next - SystemClock.uptimeMillis();
                            if (wait > 0) SystemClock.sleep(wait); else if (wait < -20) next = SystemClock.uptimeMillis();
                        }
                    } catch (IOException e) { try { socket.close(); } catch (IOException ignored) { } }
                },"USB microphone");
                if (capture != null) capture.start();
                final AudioTrack track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(Math.max(3072,AudioTrack.getMinBufferSize(48000,
                                AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT))).build();
                try {
                    track.play(); final byte[] buffer = new byte[768];
                    while (!mClosed) {
                        final int size = socket.getInputStream().read(buffer);
                        if (size <= 0 || size % 4 != 0) throw new IOException("Audio packet");
                        int position = 0;
                        while (!mClosed && position < size) {
                            int written = track.write(buffer,position,size - position,AudioTrack.WRITE_NON_BLOCKING);
                            if (written < 0) throw new IOException("Audio player");
                            position += written;
                            if (written == 0) SystemClock.sleep(2);
                        }
                    }
                } finally { track.pause(); track.flush(); track.release(); }
            } catch (IOException | IllegalStateException | IllegalArgumentException e) {
                if (!mClosed) SystemClock.sleep(100);
            } finally { mSocket = null; }
        }
    }
    @Override public void close() {
        mClosed = true; final LocalSocket socket = mSocket;
        if (socket != null) try { socket.close(); } catch (IOException e) { }
    }
}
