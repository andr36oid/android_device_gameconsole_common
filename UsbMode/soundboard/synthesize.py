#!/usr/bin/env python3
"""Generate the example pads using only tones and seeded noise, at 48 kHz."""
import argparse
import io
import math
from pathlib import Path
import random
import struct
import wave

RATE = 48000
NAMES = ('airhorn', 'rimshot', 'sad-trombone', 'crickets', 'drumroll', 'applause',
         'victory', 'buzzer', 'laser', 'coin', 'power-up', 'level-complete')
DURATIONS = (1.0, 1.4, 1.4, 1.4, 1.4, 1.4, 1.4, 0.7, 0.7, 0.6, 1.0, 1.4)


def sound(index):
    rng = random.Random(3600 + index)
    duration = DURATIONS[index]
    samples = []
    for n in range(round(duration * RATE)):
        t = n / RATE
        noise = rng.uniform(-1, 1)
        if index == 0:
            value = sum(math.sin(2 * math.pi * f * t) / (j + 1) for j, f in enumerate((220, 277.18, 440, 554.36, 660)))
        elif index == 1:
            value = 0
            for start, f in ((0, 110), (0.28, 165)):
                u = t - start
                if 0 <= u < 0.22:
                    value += math.sin(2 * math.pi * f * u) * math.exp(-22 * u)
            if t > 0.6:
                value += noise * math.exp(-6 * (t - 0.6)) * 0.7
        elif index == 2:
            f = 220 - 80 * t / duration
            phase = 2 * math.pi * (220 * t - 40 * t * t / duration)
            value = sum(math.sin(j * phase) / j for j in (1, 2, 3, 4, 5)) * (0.6 + 0.4 * math.sin(6 * math.pi * t))
        elif index == 3:
            pulse = max(0, math.sin(2 * math.pi * 7 * t)) ** 8
            value = math.sin(2 * math.pi * 4800 * t) * pulse
        elif index == 4:
            u = t % 0.065
            value = (noise * 0.7 + math.sin(2 * math.pi * 180 * u) * 0.3) * math.exp(-65 * u) * (0.3 + t / duration)
        elif index == 5:
            value = noise * (0.5 + 0.5 * math.sin(2 * math.pi * 17 * t) ** 2) * math.sin(math.pi * t / duration)
        elif index in (6, 11):
            notes = (523.25, 659.25, 783.99, 1046.5, 783.99, 1046.5) if index == 6 else (392, 523.25, 659.25, 783.99, 1046.5, 1046.5)
            step = min(5, int(t / (duration / 6)))
            f = notes[step]
            value = (1 if math.sin(2 * math.pi * f * t) >= 0 else -1) * (0.75 + 0.25 * math.cos(2 * math.pi * t / (duration / 6)))
        elif index == 7:
            value = math.sin(2 * math.pi * 110 * t) + 0.6 * math.sin(2 * math.pi * 117 * t) + noise * 0.1
        elif index == 8:
            phase = 2 * math.pi * (1800 * t - 1150 * t * t)
            value = math.sin(phase) * math.exp(-4 * t)
        elif index == 9:
            f = 988 if t < 0.09 else 1319
            value = math.sin(2 * math.pi * f * t) * math.exp(-5 * t)
        else:
            phase = 2 * math.pi * (220 * t + 660 * t * t)
            value = math.sin(phase) + 0.25 * math.sin(2 * phase)
        fade = min(1, t / 0.006, (duration - t) / 0.025)
        samples.append(value * max(0, fade))
    gain = 0.85 * 32767 / max(abs(x) for x in samples)
    pcm = b''.join(struct.pack('<h', round(x * gain)) for x in samples)
    output = io.BytesIO()
    with wave.open(output, 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(pcm)
    return output.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'assets' / 'soundboard')
    args = parser.parse_args()
    if not args.check:
        args.output.mkdir(parents=True, exist_ok=True)
    for index, name in enumerate(NAMES):
        data = sound(index)
        assert len(data) < 150000
        path = args.output / (name + '.wav')
        if args.check:
            assert path.read_bytes() == data, path
        else:
            path.write_bytes(data)
    print('12 synthesized 48 kHz example sounds match' if args.check else 'Generated 12 example sounds')


if __name__ == '__main__':
    main()
