#!/usr/bin/env python3
"""Synthesises the retro UI sound set (original, square/pulse waves like old
consoles) and encodes it to Ogg Vorbis with ffmpeg.

Needs numpy and ffmpeg (libvorbis). Run from anywhere:  python3 make-sounds.py
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'audio')
RATE = 44100


def pulse(freq, ms, duty=0.25, start=None, end=None):
    """Pulse wave; freq may slide from start to end (Hz)."""
    n = int(RATE * ms / 1000)
    f = np.linspace(start or freq, end or freq, n)
    phase = np.cumsum(f / RATE) % 1.0
    return np.where(phase < duty, 1.0, -1.0)


def env(sig, attack_ms=1, decay=True):
    n = len(sig)
    a = max(1, int(RATE * attack_ms / 1000))
    e = np.ones(n)
    e[:a] = np.linspace(0, 1, a)
    if decay:
        e[a:] = np.linspace(1, 0, n - a) ** 1.5
    return sig * e


def soften(sig, cutoff=5000):
    """One-pole low-pass: takes the fizz off the square edges."""
    k = 1 - np.exp(-2 * np.pi * cutoff / RATE)
    out = np.empty_like(sig)
    acc = 0.0
    for i, s in enumerate(sig):
        acc += k * (s - acc)
        out[i] = acc
    return out


def seq(*parts):
    return np.concatenate(parts)


def gap(ms):
    return np.zeros(int(RATE * ms / 1000))


SOUNDS = {
    # d-pad moves the focus: tiny blip
    'move': lambda: env(pulse(1320, 28, duty=0.125)) * 0.5,
    # A / click: two-step rising blip
    'select': lambda: seq(env(pulse(880, 30), decay=False) * 0.8, env(pulse(1320, 45))) * 0.6,
    # on-screen keyboard
    'key': lambda: env(pulse(1000, 22, duty=0.5)) * 0.45,
    'key_space': lambda: env(pulse(700, 26, duty=0.5)) * 0.45,
    'key_delete': lambda: env(pulse(0, 60, start=900, end=450)) * 0.5,
    'key_return': lambda: seq(env(pulse(784, 35), decay=False) * 0.8, env(pulse(1175, 60))) * 0.55,
    'key_invalid': lambda: env(pulse(120, 110, duty=0.5)) * 0.5,
    # charger plugged in: rising arpeggio
    'charging': lambda: seq(*[env(pulse(f, 70), decay=(i == 3)) * (0.8 if i < 3 else 1.0)
                              for i, f in enumerate((523, 659, 784, 1047))],
                            gap(20)) * 0.55,
}


# Stock Effect_Tick peaks at about -15 dBFS and ChargingStarted at -22 dBFS;
# square waves sound much louder than their peak, so these sit a bit below.
GAIN = {'charging': 0.13}
DEFAULT_GAIN = 0.2


def write_wav(path, sig, gain):
    sig = soften(sig) * gain
    pcm = (np.clip(sig, -1, 1) * 32767 * 0.9).astype('<i2')
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def main():
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, make in SOUNDS.items():
            wav = os.path.join(tmp, name + '.wav')
            write_wav(wav, make(), GAIN.get(name, DEFAULT_GAIN))
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', wav, '-c:a', 'libvorbis',
                            '-q:a', '3', '-map_metadata', '-1', '-fflags', '+bitexact',
                            '-flags:a', '+bitexact', os.path.join(OUT, name + '.ogg')],
                           check=True)


if __name__ == '__main__':
    main()
