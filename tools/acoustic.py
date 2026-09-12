#!/usr/bin/env python3
"""
Acoustic end-to-end latency measurement for Wired Tooth.

This is the only measurement that includes every delay in the chain: the
application's own output buffer, WASAPI capture, the network, the jitter
buffer, the DAC and the earbud itself. Everything else the project reports is
an estimate that excludes at least one of those.

Method: play a click on the PC. One microphone hears it twice -- once directly
from the PC speaker, once from the earbud playing the streamed copy. The gap
between the two arrivals is the latency. One mic and one recording is the
whole point: two recordings would have to be synchronised with each other,
which reintroduces the clock problem the measurement exists to avoid.

    python tools/acoustic.py generate -o clicks.wav
    # play clicks.wav on the PC, record the room, save as recording.wav
    python tools/acoustic.py measure recording.wav
    python tools/acoustic.py selftest        # verify the analyser itself

Why a chirp and not a 1 kHz tone burst
--------------------------------------
docs/MEASUREMENT.md originally said "1 kHz click". A pure tone is periodic, so
cross-correlating against it produces a peak every 1 ms and the result is
ambiguous by whole milliseconds -- which is the same order as the thing being
measured. A short swept chirp has the energy of a tone burst, so it survives a
speaker and a room, but its autocorrelation is a single sharp peak. Finding the
onset by eye in Audacity has the same ambiguity problem and worse precision;
this measures it by correlation instead.
"""
import argparse
import math
import struct
import sys
import wave

try:
    import numpy as np
except ImportError:
    sys.exit("numpy is required:  pip install numpy")

RATE = 48000
CLICK_MS = 3.0           # short enough to be impulse-like, long enough to hear
F_START = 500.0
F_END = 8000.0
SPACING_S = 2.0          # long enough that room reverb has died away
DEFAULT_CLICKS = 12      # 10 usable plus margin
AMPLITUDE = 0.5

# A latency beyond this is treated as "not the pair" rather than a real
# measurement. Bluetooth is ~250ms; anything past 500ms is a second reflection
# or a missed click.
MAX_LATENCY_MS = 500.0
MIN_LATENCY_MS = 1.0


def click_template(rate=RATE):
    """Hann-windowed linear chirp. Sharp autocorrelation, decent energy."""
    n = int(rate * CLICK_MS / 1000.0)
    t = np.arange(n) / rate
    k = (F_END - F_START) / (CLICK_MS / 1000.0)
    phase = 2 * math.pi * (F_START * t + 0.5 * k * t * t)
    window = np.hanning(n)
    return (np.sin(phase) * window).astype(np.float64)


def generate(path, clicks, rate):
    tpl = click_template(rate)
    gap = int(rate * SPACING_S)
    total = gap * clicks + len(tpl) + rate      # trailing second of silence
    buf = np.zeros(total, dtype=np.float64)

    for i in range(clicks):
        start = gap * i + rate // 2             # half a second of lead-in
        buf[start:start + len(tpl)] += tpl * AMPLITUDE

    pcm = np.clip(buf, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype(np.int16)

    # Stereo, because the capture path is stereo and a mono file would be
    # upmixed somewhere unpredictable.
    stereo = np.repeat(pcm[:, None], 2, axis=1).flatten()

    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(stereo.tobytes())

    print(f"wrote {path}")
    print(f"  {clicks} clicks, {CLICK_MS:.0f} ms chirp {F_START:.0f}-{F_END:.0f} Hz")
    print(f"  spacing {SPACING_S:.1f} s, total {total / rate:.1f} s")


def read_wav_mono(path):
    import os
    if not os.path.exists(path):
        sys.exit(f"{path}: no such file")
    try:
        probe = wave.open(path, "rb")
        probe.close()
    except (wave.Error, EOFError) as exc:
        sys.exit(f"{path}: not a readable WAV ({exc}). Export as WAV, not "
                 f"m4a/mp3 -- a lossy codec smears the click onset, which is "
                 f"the thing being measured.")
    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        ch = w.getnchannels()
        width = w.getsampwidth()
        frames = w.readframes(w.getnframes())

    if width == 2:
        data = np.frombuffer(frames, dtype="<i2").astype(np.float64) / 32768.0
    elif width == 4:
        data = np.frombuffer(frames, dtype="<i4").astype(np.float64) / 2147483648.0
    elif width == 1:
        data = (np.frombuffer(frames, dtype=np.uint8).astype(np.float64) - 128) / 128.0
    else:
        sys.exit(f"{path}: unsupported sample width {width * 8}-bit")

    if ch > 1:
        data = data.reshape(-1, ch).mean(axis=1)
    return data, rate


def correlate(signal, template):
    """FFT cross-correlation. np.correlate is O(n*m) and far too slow here."""
    n = len(signal) + len(template) - 1
    size = 1 << (n - 1).bit_length()
    S = np.fft.rfft(signal, size)
    T = np.fft.rfft(template[::-1], size)
    full = np.fft.irfft(S * T, size)[:n]
    # Align so index i is the template starting at signal[i].
    return full[len(template) - 1:]


def find_peaks(env, threshold, min_sep):
    """Greedy peak picking: take the largest, blank its neighbourhood, repeat."""
    work = env.copy()
    peaks = []
    while True:
        i = int(np.argmax(work))
        if work[i] < threshold:
            break
        peaks.append(i)
        lo = max(0, i - min_sep)
        hi = min(len(work), i + min_sep)
        work[lo:hi] = 0.0
    return sorted(peaks)


def group_clicks(peaks, env, rate):
    """
    Split peaks into one group per emitted click.

    Every emitted click produces several arrivals: the direct sound, the
    earbud, and a reflection of each off whatever is nearby. Clicks are spaced
    seconds apart, so a gap larger than the maximum plausible latency means a
    new click rather than another arrival of the same one.
    """
    max_gap = int(rate * MAX_LATENCY_MS / 1000.0)
    groups, current = [], []
    for p in peaks:
        if current and p - current[-1] > max_gap:
            groups.append(current)
            current = []
        current.append(p)
    if current:
        groups.append(current)
    return [g for g in groups if len(g) >= 2]


def consistent_offsets(groups, env, rate, tol_ms=0.5):
    """
    Offsets that appear in the same place after every click.

    A real second source is at a fixed delay behind the direct sound on every
    single click. So is a reflection -- which is exactly why simply taking the
    first two peaks is wrong -- but listing all of them lets the reflections be
    identified and excluded instead of silently mistaken for the answer.
    """
    buckets = {}
    for g in groups:
        base = g[0]
        for p in g[1:]:
            ms = (p - base) / rate * 1000.0
            key = round(ms / tol_ms) * tol_ms
            b = buckets.setdefault(key, {"ms": [], "amp": []})
            b["ms"].append(ms)
            b["amp"].append(float(env[p]))

    out = []
    for _, b in buckets.items():
        out.append({
            "median_ms": float(np.median(b["ms"])),
            "min_ms": float(np.min(b["ms"])),
            "max_ms": float(np.max(b["ms"])),
            "count": len(b["ms"]),
            "amp": float(np.mean(b["amp"])),
            "all": list(b["ms"]),
        })
    return sorted(out, key=lambda o: o["median_ms"])


def analyse(path, threshold_frac=0.25):
    signal, rate = read_wav_mono(path)
    tpl = click_template(rate)
    env = np.abs(correlate(signal, tpl))
    if env.max() <= 0:
        sys.exit("no correlation energy at all - is this the right recording?")
    env = env / env.max()

    min_sep = int(rate * MIN_LATENCY_MS / 1000.0)
    peaks = find_peaks(env, threshold_frac, min_sep)
    groups = group_clicks(peaks, env, rate)
    offsets = consistent_offsets(groups, env, rate)
    return signal, rate, env, peaks, groups, offsets


def measure(path, threshold_frac=0.25, verbose=False, reference=None,
            expect_ms=None):
    signal, rate, env, peaks, groups, offsets = analyse(path, threshold_frac)

    print(f"file            : {path}  ({rate} Hz, {len(signal) / rate:.1f} s)")
    print(f"clicks detected : {len(peaks)} arrivals in {len(groups)} click groups")
    if not groups:
        sys.exit("no click groups found - lower --threshold, or check the "
                 "recording actually contains the click track")
    print()

    ref_offsets = []
    if reference:
        _, _, _, _, _, ref_offsets = analyse(reference, threshold_frac)
        print(f"reference       : {reference}")
        for o in ref_offsets:
            print(f"  speaker-only arrival at {o['median_ms']:7.2f} ms "
                  f"(x{o['count']}) - treated as a reflection")
        print()

    def is_reflection(o):
        return any(abs(o["median_ms"] - r["median_ms"]) < 1.0 for r in ref_offsets)

    print("consistent arrivals after the direct sound:")
    print(f"  {'offset':>10}  {'seen':>5}  {'rel amp':>8}  {'spread':>8}")
    candidates = []
    for o in offsets:
        if o["count"] < max(2, len(groups) // 2):
            continue                      # not consistent enough to be a source
        tag = ""
        if is_reflection(o):
            tag = "  <- reflection (in reference)"
        else:
            candidates.append(o)
        print(f"  {o['median_ms']:9.2f}ms  {o['count']:5d}  {o['amp']:8.3f}  "
              f"{o['max_ms'] - o['min_ms']:7.2f}ms{tag}")
    print()

    if reference and candidates:
        best = max(candidates, key=lambda o: o["amp"])
    elif expect_ms is not None:
        best = min(offsets, key=lambda o: abs(o["median_ms"] - expect_ms))
    else:
        print("Cannot tell the earbud from a reflection without help. Either:")
        print("  - record a speaker-only pass (earbud silent) and pass it with")
        print("    --reference, which marks every reflection automatically, or")
        print("  - pass --expect <ms> if you already know roughly what to look")
        print("    for, from the estimate in the tray app")
        print()
        print("A room reflection off a desk typically lands 5-15 ms behind the")
        print("direct sound, which is the same range as a good result. This is")
        print("the one way this measurement quietly lies.")
        return None

    ms = np.array(best["all"])
    print(f"earbud arrival  : {len(ms)} measurements")
    print()
    print(f"median          : {np.median(ms):7.2f} ms   <- report this one")
    print(f"mean            : {ms.mean():7.2f} ms")
    print(f"min             : {ms.min():7.2f} ms")
    print(f"max             : {ms.max():7.2f} ms")
    print(f"range           : {ms.max() - ms.min():7.2f} ms")
    print(f"std dev         : {ms.std(ddof=0):7.2f} ms")
    print()
    if verbose:
        print("every measurement: " + ", ".join(f"{v:.2f}" for v in ms))
        print()
    if len(ms) < 10:
        print(f"NOTE: {len(ms)} measurements. docs/MEASUREMENT.md asks for 10; "
              f"a single measurement is not a result.")
    if ms.max() - ms.min() > 20:
        print("NOTE: range over 20 ms. Report it -- variance is part of the "
              "finding, not something to hide behind the median.")
    return float(np.median(ms))


def _synth(path, rate, latency_ms, reflections, seed):
    """Build a fake room recording: direct sound, optional reflections, earbud."""
    tpl = click_template(rate)
    rng = np.random.default_rng(seed)
    gap = int(rate * SPACING_S)
    n = gap * 10 + rate
    sig = np.zeros(n)
    delay = int(round(rate * latency_ms / 1000.0))

    for i in range(10):
        s0 = gap * i + rate // 2
        sig[s0:s0 + len(tpl)] += tpl * 0.60                   # direct
        for off_ms, amp in reflections:
            r = s0 + int(rate * off_ms / 1000.0)
            sig[r:r + len(tpl)] += tpl * amp                  # speaker echo
        if latency_ms is not None:
            d = s0 + delay
            sig[d:d + len(tpl)] += tpl * 0.35                 # earbud
            for off_ms, amp in reflections:
                r = d + int(rate * off_ms / 1000.0)
                sig[r:r + len(tpl)] += tpl * amp * 0.6        # earbud echo

    sig += rng.normal(0, 0.004, n)
    sig += 0.01 * np.sin(2 * math.pi * 50 * np.arange(n) / rate)
    pcm = (np.clip(sig, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def _synth_reference(path, rate, reflections, seed):
    """Speaker only, earbud silent. What --reference is recorded from."""
    tpl = click_template(rate)
    rng = np.random.default_rng(seed)
    gap = int(rate * SPACING_S)
    n = gap * 10 + rate
    sig = np.zeros(n)
    for i in range(10):
        s0 = gap * i + rate // 2
        sig[s0:s0 + len(tpl)] += tpl * 0.60
        for off_ms, amp in reflections:
            r = s0 + int(rate * off_ms / 1000.0)
            sig[r:r + len(tpl)] += tpl * amp
    sig += rng.normal(0, 0.004, n)
    pcm = (np.clip(sig, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def selftest():
    """
    Synthesise recordings with latencies we chose and see if the analyser
    recovers them. Proves the tool before any hardware is involved, which is
    the difference between a measurement and a number.

    The reflection cases matter most. An earlier version of this analyser
    paired each direct sound with its own desk echo and confidently reported
    7.99 ms for a recording whose true latency was 38.5 ms. A wrong answer
    that looks plausible is the worst possible failure for a benchmark.
    """
    import os
    rate = RATE
    ok = True
    reflections = [(7.0, 0.30), (9.0, 0.18)]

    print("=== clean room, no reflections ===")
    for known in (12.0, 38.5, 110.0, 176.0):
        _synth("_st.wav", rate, known, [], 7)
        got = measure("_st.wav", expect_ms=known)
        err = abs(got - known) if got else 999
        print(f"--- true {known:6.1f} ms -> {got:6.2f} ms  err {err:.3f}  "
              f"{'PASS' if err < 0.2 else 'FAIL'}\n")
        ok &= err < 0.2

    print("=== reflective room, WITH --reference ===")
    _synth_reference("_stref.wav", rate, reflections, 11)
    for known in (38.5, 110.0):
        _synth("_st.wav", rate, known, reflections, 3)
        got = measure("_st.wav", reference="_stref.wav")
        err = abs(got - known) if got else 999
        print(f"--- true {known:6.1f} ms -> {got:6.2f} ms  err {err:.3f}  "
              f"{'PASS' if err < 0.2 else 'FAIL'}\n")
        ok &= err < 0.2

    print("=== reflective room, NO reference: must refuse, not guess ===")
    _synth("_st.wav", rate, 38.5, reflections, 3)
    got = measure("_st.wav")
    refused = got is None
    print(f"--- refused to guess: {'PASS' if refused else 'FAIL (it guessed)'}\n")
    ok &= refused

    for f in ("_st.wav", "_stref.wav"):
        try:
            os.remove(f)
        except OSError:
            pass

    print("SELFTEST PASS" if ok else "SELFTEST FAIL")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    g = sub.add_parser("generate", help="write the click track to play")
    g.add_argument("-o", "--out", default="clicks.wav")
    g.add_argument("-n", "--clicks", type=int, default=DEFAULT_CLICKS)
    g.add_argument("--rate", type=int, default=RATE)

    m = sub.add_parser("measure", help="analyse a recording of the room")
    m.add_argument("recording")
    m.add_argument("--threshold", type=float, default=0.25,
                   help="peak threshold as a fraction of the strongest "
                        "correlation (default 0.25; lower it if clicks are missed)")
    m.add_argument("-v", "--verbose", action="store_true",
                   help="list every measurement, not just the summary")
    m.add_argument("--reference", default=None,
                   help="a speaker-only recording (earbud silent). Its arrivals "
                        "are room reflections, and are excluded automatically")
    m.add_argument("--expect", type=float, default=None, dest="expect_ms",
                   help="roughly the latency you expect, in ms, to pick the "
                        "right arrival when you have no reference recording")

    sub.add_parser("selftest", help="verify the analyser against known delays")

    a = ap.parse_args()
    if a.cmd == "generate":
        generate(a.out, a.clicks, a.rate)
        return 0
    if a.cmd == "measure":
        measure(a.recording, threshold_frac=a.threshold, verbose=a.verbose,
                reference=a.reference, expect_ms=a.expect_ms)
        return 0
    return selftest()


if __name__ == "__main__":
    sys.exit(main())
