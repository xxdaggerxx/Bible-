#!/usr/bin/env python3
"""
Writing sounds (INK-15): makes the looping sounds in app/src/main/assets/sounds/ from the recordings
in tools/sounds/ (free sounds from Pixabay, by freesound_community; Pixabay Content License).

For each tool it keeps the parts of the recording where the pen is moving, joins them with short
cross-fades, cross-fades the end into the start so the loop is seamless, and evens out the loudness
so the three tools sound balanced. Output: 16-bit mono WAV at 32 kHz.

The recordings aren't kept in the repository (Pixabay's licence doesn't allow sharing them as
files on their own). To rebuild, download them from pixabay.com/sound-effects into tools/sounds/:
"Pencil" (pencil-29272), "Marker circle" (marker-circle-6789), "Drawing" (drawing-62360).

Needs: pip install miniaudio numpy
"""
import math, os, struct, sys
import miniaudio
import numpy as np

RATE = 32000
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "sounds")

# tool: (recording, seconds of sound to keep, how loud the active parts must be vs. the loudest)
TOOLS = {
    "pen": ("pencil-29272.mp3", 6.0, 0.25),
    "highlighter": ("marker-circle-6789.mp3", 4.0, 0.35),
    "eraser": ("drawing-62360.mp3", 5.0, 0.45),
}
TARGET_RMS = 0.16   # about -16 dBFS while the pen moves
JOIN = int(0.025 * RATE)   # cross-fade between kept parts
LOOP = int(0.20 * RATE)    # cross-fade from the end of the loop into its start


def decode(path):
    d = miniaudio.decode_file(path, output_format=miniaudio.SampleFormat.FLOAT32, nchannels=1, sample_rate=RATE)
    return np.frombuffer(d.samples, dtype=np.float32).astype(np.float64)


def rms(a):
    return float(np.sqrt(np.mean(a * a))) if len(a) else 0.0


def curves(n):
    t = (np.arange(n) + 0.5) / n
    return np.cos(t * math.pi / 2), np.sin(t * math.pi / 2)  # equal-power cross-fade


def fade_join(a, b, n):
    """a then b, overlapping by n samples."""
    n = min(n, len(a), len(b))
    out_, in_ = curves(n)
    return np.concatenate([a[:len(a) - n], a[len(a) - n:] * out_ + b[:n] * in_, b[n:]])


def active_parts(s, keep, threshold):
    win = int(0.05 * RATE)
    levels = np.array([rms(s[i:i + win]) for i in range(0, len(s) - win, win)])
    top = np.quantile(levels, 0.95)  # ignore the odd click
    loud = np.append(levels >= top * threshold, False)
    # Runs of windows loud enough to be a moving pen, at least 0.25 s long.
    runs, start = [], None
    for i, on in enumerate(loud):
        if on and start is None:
            start = i
        elif not on and start is not None:
            if (i - start) * win >= 0.25 * RATE:
                runs.append((start * win, i * win))
            start = None
    out = None
    for a, b in runs:
        part = s[a:b]
        out = part if out is None else fade_join(out, part, JOIN)
        if len(out) >= keep * RATE + LOOP:
            break
    return out[:int(keep * RATE) + LOOP]


def make_loop(s):
    """Seamless: the last LOOP samples cross-fade into the first ones, which are then dropped."""
    head, body = s[:LOOP], s[LOOP:].copy()
    out_, in_ = curves(LOOP)
    body[-LOOP:] = body[-LOOP:] * out_ + head * in_
    return body


def write_wav(path, a):
    data = np.clip(np.round(a * 32767), -32767, 32767).astype("<i2").tobytes()
    with open(path, "wb") as f:
        f.write(b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, RATE, RATE * 2, 2, 16))
        f.write(b"data" + struct.pack("<I", len(data)) + data)


def main():
    os.makedirs(OUT, exist_ok=True)
    for tool, (src, keep, threshold) in TOOLS.items():
        s = decode(os.path.join(HERE, "sounds", src))
        s = s - s.mean()  # no DC offset
        loop = make_loop(active_parts(s, keep, threshold))
        # Raise to the target loudness, rounding off the odd sharp tick rather than clipping it.
        for _ in range(4):
            loop = 0.95 * np.tanh(loop * (TARGET_RMS / rms(loop)) / 0.95)
        write_wav(os.path.join(OUT, tool + ".wav"), loop)
        print(f"{tool}: {len(loop) / RATE:.1f} s from {src}, rms {rms(loop):.3f}, peak {float(np.abs(loop).max()):.2f}")


if __name__ == "__main__":
    sys.exit(main())
