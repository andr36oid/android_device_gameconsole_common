package org.andr36oid.usbmode;

import android.os.SystemClock;

final class KeyboardChannel extends ControllerInputChannel {
    interface Frames { int received(byte[] frame); }
    private final KeyboardModel mModel;
    private final KeyboardView mView;
    private final Frames mFrames;

    KeyboardChannel(KeyboardModel model, KeyboardView view, Frames frames) {
        mModel = model; mView = view; mFrames = frames;
        start();
    }
    @Override byte[] reply(byte[] frame) {
        synchronized (mModel) {
            final int action = mFrames.received(frame);
            final byte[] packet;
            if (action != 0) {
                packet = new byte[16]; packet[0] = 1; packet[14] = (byte) (action == 2 ? 1 : 0);
            } else packet = mModel.update(frame, SystemClock.uptimeMillis());
            mView.setLeds(frame[12] & 255); mView.postInvalidate();
            return packet;
        }
    }
    @Override void released() {
        synchronized (mModel) { mModel.reset(); }
        mView.setLeds(0); mView.postInvalidate();
    }
}
