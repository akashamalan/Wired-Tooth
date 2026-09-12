# Acoustic latency benchmark

The procedure for WP8. Produces the only latency number worth publishing,
because it is the only one that includes every delay in the chain — the
application's output buffer, WASAPI capture, the network, the jitter buffer,
the DAC, and the earbud itself. No API reports several of those.

Everything here needs hardware: a microphone, the earbuds, and a Bluetooth
headset to compare against. The tooling is done and self-tested; the recording
is not something a script can do.

## What you need

- A microphone. A phone voice-recorder app is fine — this measures a *time
  difference* within one recording, so the mic's own latency cancels out
  entirely and its quality barely matters.
- The wired earbuds.
- A Bluetooth headset, for the comparison row.
- A quiet room. Not silent, just no music or conversation.

## The idea

One microphone hears the same click twice: once directly from the PC's
speaker, once from the earbud playing the streamed copy. The gap between the
two arrivals is the end-to-end latency.

One mic and one recording is the whole point. Two recordings would have to be
synchronised with each other, which reintroduces exactly the clock problem the
measurement exists to avoid. Because both sounds land in the same file, on the
same clock, the difference between them is exact.

## Setup

1. Generate the click track:

   ```
   python tools/acoustic.py generate -o clicks.wav
   ```

   12 clicks, one every 2 seconds. Each click is a 3 ms chirp sweeping
   500 Hz to 8 kHz — not the 1 kHz tone the first draft of
   `docs/MEASUREMENT.md` called for. A pure tone is periodic, so correlating
   against it peaks every 1 ms and the answer is ambiguous by whole
   milliseconds, which is the same order as the thing being measured. A chirp
   has a tone burst's energy and a single sharp correlation peak.

2. Put the earbud right next to the PC's speaker, both a few centimetres from
   the microphone. Air travel is about 0.03 ms per centimetre, so a few cm of
   difference is far below the noise floor — but keep them close together
   anyway, so neither is obviously louder.

3. Start the receiver, then the sender, and confirm audio is actually flowing
   before recording anything.

## Record a reference first

**Do not skip this.** Record the click track with the earbud muted or
unplugged, so the mic hears only the PC speaker:

```
python tools/acoustic.py measure reference.wav
```

This captures the room's reflections. A click bouncing off a desk arrives
5–15 ms behind the direct sound, which is *the same range as a good latency
result*. Without a reference, the analyser cannot tell a desk echo from the
earbud, and it will say so rather than guess.

This is not hypothetical. An earlier version of the analyser paired each
direct sound with its own reflection and reported **7.99 ms** for a recording
whose true latency was **38.5 ms**. It looked entirely plausible. That is the
worst kind of wrong answer, and it is why the reference pass exists.

## Measure

With the earbud live, record the click track again, then:

```
python tools/acoustic.py measure recording.wav --reference reference.wav
```

The analyser cross-correlates against the known chirp, groups arrivals by
emitted click, discards every offset that also appears in the reference, and
reports the median, range and standard deviation of what is left.

If you have no reference recording, `--expect 110` will pick the arrival
closest to a latency you already believe — but that is choosing the answer you
expected, which is not a measurement. Use the reference.

**Verify the analyser itself first** if you want to trust it:

```
python tools/acoustic.py selftest
```

It synthesises recordings with known latencies — clean and reflective — and
checks that it recovers them. It also checks that it *refuses* to answer when
reflections are present and no reference was supplied.

## The three configurations

Run all three under the same conditions, back to back:

| # | Configuration | What it tells you |
|---|---|---|
| 1 | Wired Tooth over the Wi-Fi hotspot | the result |
| 2 | The same Bluetooth headset paired to the PC directly | what you are beating |
| 3 | Wired earbuds plugged into the PC | the floor — as good as it can get |

Row 3 matters most and is the easiest to skip. It is the irreducible delay of
the PC's own audio stack, and it is what row 1 should be compared against. If
row 1 is 40 ms and row 3 is 30 ms, Wired Tooth costs 10 ms, not 40.

## Also record, per configuration

From the receiver's final stats and metrics CSV:

- packet loss %
- buffer depth (mean and range)
- CPU usage of the sender — Task Manager is sufficient

## Reporting

Report the **median of 10** and the **full range**. A single measurement is not
a result. If the spread is wide, say so — variance is part of the finding, and
a project claiming a latency number should be honest about how stable it is.

State the conditions with the number, or it is not reproducible:

- router or hotspot, and which
- distance between the devices
- how many devices were on the network
- what else was running on the PC
- the PC's default output device — on this machine it is a virtual device
  (FxSound), which turned out to matter enormously (see
  `docs/ENGINEERING_NOTES.md`)

## Do not subtract anything

It is tempting to subtract the mic's distance to each source, or the PC
speaker's own output latency, to get a "purer" number. Don't. The measurement
answers "how far behind the PC is the earbud", and both paths reaching one mic
is what makes it self-consistent. Subtracting estimates replaces a measured
number with a partly-invented one.

## Sanity check

The estimated end-to-end figure from the tray app is currently ~110 ms on
loopback, most of which is the 60 ms jitter buffer plus 50 ms of requested
output latency. The acoustic number should be **larger** than that, because it
includes the capture-side delays the estimate ignores. If it comes out
dramatically smaller, something is being measured wrong — most likely a
reflection being mistaken for the earbud.
