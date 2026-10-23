"""Synthesises tvos_style's interface sound packs into app/src/main/res/raw/snd_<pack>_<move|select|back>.wav.

No samples from anywhere: decaying sine partials, a pitch glide and a softened noise transient, so the files are
original and a few kilobytes each. Run from the launcher folder: python3 make_sounds.py
"""
import math, os, random, struct, wave

RATE = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "src", "main", "res", "raw")


def write(name, samples, gain=1.0):
    peak = max(1e-6, max(abs(s) for s in samples))
    scale = gain * 0.85 / peak
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s * scale)) * 32767)) for s in samples))


def tone(ms, partials, noise=0.0, noise_ms=2.0, attack_ms=0.6, lowpass=0.35, seed=7):
    """partials: (freq, amp, decay_ms, glide, glide_ms, delay_ms)."""
    rnd = random.Random(seed)
    n = int(RATE * ms / 1000)
    out, lp = [], 0.0
    for i in range(n):
        t = i / RATE
        s = 0.0
        for freq, amp, decay, glide, glide_ms, delay in partials:
            td = t - delay / 1000
            if td < 0:
                continue
            f = freq * (1 + glide * math.exp(-td * 1000 / glide_ms))
            s += amp * math.sin(2 * math.pi * f * td) * math.exp(-td * 1000 / decay) * min(1, td * 1000 / attack_ms)
        nz = rnd.uniform(-1, 1) * noise * math.exp(-t * 1000 / noise_ms)
        lp += lowpass * (nz - lp)
        out.append(s + lp)
    fade = int(RATE * 0.004)
    for i in range(fade):
        out[-1 - i] *= i / fade
    return out


P = lambda f, a, d, g=0.0, gm=6.0, dl=0.0: (f, a, d, g, gm, dl)

PACKS = {
    # The original: a light woody tick and a rounder tock.
    "glass": dict(
        move=tone(38, [P(2350, .30, 5.5, .04), P(4700, .06, 3), P(1180, .10, 7)], .22, 2),
        select=tone(85, [P(820, .42, 16, .18), P(1640, .12, 9, .1), P(410, .10, 22)], .30, 3),
        back=tone(70, [P(620, .40, 14, -.12), P(1240, .10, 8)], .20, 3)),
    # Soft rounded pops, like a bubble.
    "pop": dict(
        move=tone(45, [P(980, .5, 9, .45, 5)], .05, 1),
        select=tone(90, [P(620, .55, 20, .6, 8), P(1240, .12, 10, .6, 8)], .05, 1),
        back=tone(80, [P(520, .5, 18, -.3, 10)], .04, 1)),
    # Wood blocks.
    "wood": dict(
        move=tone(40, [P(1850, .35, 6), P(2900, .12, 4), P(950, .15, 8)], .35, 1.5, lowpass=.25),
        select=tone(110, [P(760, .5, 22), P(1180, .2, 14), P(380, .2, 30)], .45, 2.5, lowpass=.2),
        back=tone(100, [P(560, .45, 20), P(890, .18, 12)], .35, 2.5, lowpass=.2)),
    # Little bells.
    "chime": dict(
        move=tone(120, [P(2637, .25, 40), P(5274, .08, 20), P(3951, .05, 25)]),
        select=tone(380, [P(1319, .3, 120), P(1976, .22, 110, 0, 6, 70), P(2637, .1, 80, 0, 6, 70)]),
        back=tone(320, [P(1976, .25, 100), P(1319, .22, 110, 0, 6, 70)])),
    # High glassy pings.
    "crystal": dict(
        move=tone(90, [P(4186, .2, 25), P(6272, .06, 15)], .04, 1),
        select=tone(260, [P(2093, .25, 70), P(3136, .25, 70, 0, 6, 55), P(4186, .2, 60, 0, 6, 110)]),
        back=tone(220, [P(3136, .25, 60), P(2093, .25, 70, 0, 6, 55)])),
    # A plain mechanical click.
    "click": dict(
        move=tone(18, [P(3200, .08, 1.5)], .6, 1.2, lowpass=.6),
        select=tone(30, [P(2200, .12, 3)], .8, 2.2, lowpass=.5),
        back=tone(28, [P(1700, .12, 3)], .7, 2.0, lowpass=.4)),
}

def bell(notes, ms, gap_ms):
    """A very soft run of sine bells: slow 25 ms attack, long gentle decay, a quiet octave on top."""
    return tone(ms, [P(f, .22, 420, 0, 6, i * gap_ms) for i, f in enumerate(notes)]
                + [P(f * 2, .04, 220, 0, 6, i * gap_ms) for i, f in enumerate(notes)], attack_ms=25)


# Welcome: a rising C, E, G; goodbye: the same falling back down. Played quietly, apart from the packs.
GREETINGS = {
    "welcome": bell([523.25, 659.25, 783.99], 1500, 170),
    "goodbye": bell([783.99, 659.25, 523.25], 1500, 190),
}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, samples in GREETINGS.items():
        write("snd_" + name, samples, 0.55)
    os.makedirs(OUT, exist_ok=True)
    for pack, sounds in PACKS.items():
        for kind, samples in sounds.items():
            write("snd_%s_%s" % (pack, kind), samples, 0.7 if kind == "move" else 1.0)
    print("ok")
