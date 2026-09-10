# Measuring Wired Tooth

The project's claim is a latency number, so the number has to be defensible.
There are three ways to measure it here, in increasing order of honesty. Only
the third one is the number worth publishing.

## 1. Round trip time (PING/PONG)

**What it measures:** how long a small packet takes to reach the sender and
come back, on the control channel.

The receiver sends a `PING` every 500 ms. Its send time is the packet's own
header timestamp. The sender copies that value verbatim into the `PONG`
payload and returns it. The receiver subtracts:

    rtt = receiver_now_us - echoed_timestamp_us

Both ends of that subtraction are the receiver's own clock, so the unknown
offset between the two machines' clocks cancels. This is why the echo travels
in the payload rather than being recomputed by the sender: the sender's own
clock is useless for this and must not enter the arithmetic.

**Reported as a median over the last 20 samples, not a mean.** One Wi-Fi
retransmission produces a 200 ms outlier; a mean over 20 samples would carry a
tenth of that into the displayed figure for the next ten seconds. A median
ignores it until it becomes the common case.

**What it does not tell you:** anything about audio. RTT is a network health
number. A 2 ms RTT with a 500 ms jitter buffer is still 500 ms of latency.

## 2. Estimated end-to-end

**What it measures:** a live estimate good enough for a status display.

    estimated = median_rtt / 2 + buffer_depth_ms + output_device_latency

Three assumptions, each of which can be wrong:

- **`rtt / 2` assumes a symmetric path.** Wi-Fi uplink and downlink are often
  not symmetric, particularly on a congested access point.
- **Buffer depth is exact**, computed from `BufferedBytes / AverageBytesPerSecond`.
  This is the only term that is genuinely measured.
- **Output device latency is what we asked for, not what we got.**
  `WaveOutEvent.DesiredLatency` is a request. On iOS the equivalent is
  `AVAudioSession.outputLatency`, which does report the real value.

It also excludes everything before the packet leaves: WASAPI's own capture
buffer, and the delay between an application producing a sample and loopback
capture handing it to us. Those are real and they are not small.

So this number is a useful trend line and a bad headline.

### Transit jitter

The receiver also records a per-packet transit figure, derived from the
sender's timestamp in each audio header:

    offset = receiver_now_us - packet_timestamp_us

The absolute value is meaningless, because the two `Stopwatch` clocks share no
origin and the offset contains an unknown constant. What is meaningful is how
much it moves. The receiver tracks the smallest offset it has ever seen as a
floor — the fastest transit observed — and reports everything above that floor
as jitter, logging the worst value per second.

Rising jitter with a flat RTT means the audio path is queueing while the
control path is not, which usually means the sender is producing faster than
the link drains. That is exactly the condition that drives the buffer to its
ceiling.

## 3. Acoustic ground truth

**This is the number that goes in the README.** It is the only one that
includes every delay in the chain, including the ones no API reports: the
application's own output buffer, WASAPI capture, the network, the jitter
buffer, the DAC, and the earbud itself.

Procedure:

1. Play a short, sharp click on the Windows PC — a single-sample impulse or a
   1 kHz tone burst of a few milliseconds. Sharp attack matters; a fade-in
   makes the onset ambiguous and you are trying to measure an onset.
2. Position one microphone so it picks up **both** sound sources at once: the
   PC's own speaker, and the earbud playing the streamed copy. Put the earbud
   right next to the PC speaker, both close to the mic.
3. Record with that single microphone. One mic and one recording is the whole
   point — two recordings would need to be synchronised with each other, which
   reintroduces the clock problem the measurement is meant to avoid.
4. Open the recording in Audacity. You will see the click twice: the direct
   sound from the PC speaker, then the streamed copy from the earbud.
5. Select from the start of the first transient to the start of the second.
   Set the selection toolbar to **Samples** rather than seconds, and read the
   selection length.
6. Convert: `latency_ms = samples / sample_rate * 1000`. At 48 kHz, 4800
   samples is 100 ms.

Repeat **10 times** and report the median and the full range. A single
measurement is not a result. If the spread is wide, say so — variance is part
of the finding, and a project claiming a latency number should be honest about
how stable it is.

**Subtract nothing.** It is tempting to subtract the microphone's distance to
each source, or the PC speaker's own output latency. Do not. The measurement
is "how far behind the PC is the earbud", and both paths reaching one mic is
what makes it self-consistent. Air propagation over a few centimetres is
roughly 0.03 ms per centimetre, which is below the noise floor of this method.

Record the conditions with the number: router or hotspot, distance, how many
devices were on the network, and what else was running on the PC. A latency
figure without its conditions is not reproducible.

## Metrics CSV

The receiver writes `metrics_<timestamp>.csv`, one row per second:

| Column | Meaning |
|---|---|
| `elapsed_s` | seconds since the receiver started |
| `rtt_ms` | median RTT over the last 20 PINGs |
| `buffer_ms` | current jitter buffer depth |
| `packets_received` | cumulative |
| `packets_lost` | cumulative, from sequence gaps |
| `underruns` | cumulative, buffer hit empty during playback |
| `est_latency_ms` | the estimate from section 2 |
| `transit_jitter_ms` | worst transit jitter in that second |

The first seven columns and their order are fixed by `BUILD_PLAN.md` WP3.
`transit_jitter_ms` is appended after them.

Rows are flushed every second, so a run killed with Ctrl-C still leaves a
usable file.

Plot it:

```
python tools/plot_metrics.py metrics_20260906_014500.csv
```

Read the **buffer depth** panel first. Flat is correct. A slope in either
direction over tens of minutes is clock drift, and it is why a run has to be
30 minutes rather than 30 seconds — drift is invisible for the first couple of
minutes and fatal by minute twenty. Fixing it is WP4.
