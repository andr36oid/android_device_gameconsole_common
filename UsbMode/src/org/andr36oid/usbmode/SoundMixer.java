package org.andr36oid.usbmode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// Samples are stereo S16 at 48 kHz. Clip only after summing the active voices.
final class SoundMixer {
    private static final class Voice {
        final short[] pcm;
        int position;
        Voice(short[] pcm) { this.pcm = pcm; }
    }
    private final List<Voice> mVoices = new ArrayList<>();
    private int mVolume = 75;
    synchronized void volume(int volume) { mVolume = Math.max(0,Math.min(100,volume)); }
    synchronized void play(short[] pcm) {
        if (pcm.length == 0 || pcm.length % 2 != 0) return;
        if (mVoices.size() >= 24) mVoices.remove(0);
        mVoices.add(new Voice(pcm));
    }
    synchronized void stop() { mVoices.clear(); }
    synchronized byte[] render(int frames, short[] microphone) {
        byte[] output = new byte[frames * 4];
        for (int sample = 0; sample < frames * 2; sample++) {
            int sum = microphone != null && sample < microphone.length ? microphone[sample] : 0;
            for (Voice voice : mVoices) if (voice.position < voice.pcm.length) sum += voice.pcm[voice.position++];
            int value = Math.max(-32768,Math.min(32767,sum * mVolume / 100));
            output[2 * sample] = (byte) value; output[2 * sample + 1] = (byte) (value >> 8);
        }
        for (Iterator<Voice> it = mVoices.iterator(); it.hasNext();) {
            Voice voice = it.next(); if (voice.position >= voice.pcm.length) it.remove();
        }
        return output;
    }
}
