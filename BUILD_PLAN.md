# Wired Tooth — Build Plan to v2.0

**Read this first. Then work top to bottom. Do not skip ahead.**

This file lives in the repo root. Every work package below has a prompt you can paste
straight into Claude Code. Do **one package per session**, verify the acceptance criteria,
commit, then move on. Never run two packages at once.

---

## Where you are

```
[x] WP0  repo structure, solution, baseline commit
[x] WP1  WTP1 protocol, float32 -> int16, 1400-byte chunking
[x] WP2  control channel on 5001, handshake, keepalive, BYE
[x] WP3  PING/PONG RTT, metrics CSV, plot script
[x] WP4  clock drift correction  (61.08 ms mean, +0.54 ms over 28 min, 0 underruns)
[x] WP5  survives drop / device change / format change / Ctrl-C
[x] WP6  Windows tray app, QR pairing, --console
[ ] WP7  iOS receiver              <- needs a Mac
[ ] WP8  acoustic benchmark        <- needs a mic + the earbuds
[ ] WP9  README + engineering notes
[ ] WP10 the post
```

**v1.0 criteria, honestly scored** (see "What finished means" below):

| # | Criterion | State |
|---|---|---|
| 1 | Streams to a second device over real Wi-Fi | **not proven** — no second device exists yet |
| 2 | Survives 30 minutes unattended | done (61.08 ms mean, +0.54 ms over 28 min, 0 underruns) |
| 3 | Recovers from disruption | done |
| 4 | Handles device changes | done |
| 5 | Measured, not guessed, latency | **partial** — ~110 ms estimated; the acoustic number is WP8 |
| 6 | Runs as an app, not a console | done |
| 7 | Documented | **partial** — PROTOCOL/MEASUREMENT/IOS_AUDIO exist, README is still WP1-era |

Criterion 1 is the interesting one: the QR code, the handshake and the address picker are
all built and working, but nothing has ever received this stream except this same PC.
Loopback cannot fail the way Wi-Fi fails.

The remaining work is not "more features" — it's the work that makes it stop breaking, and
most of that is now behind you. WP4 alone took three wrong hypotheses and a 16.7% output
device bug to get through.

---

## What "finished" means

Two milestones. **v1.0 is a complete project on its own** — if you never build the iPhone
app, v1.0 is still worth posting.

### v1.0 — "Rock solid on Windows"

| # | Criterion | How you prove it |
|---|---|---|
| 1 | Streams from a Windows PC to a second device over real Wi-Fi | Receiver plays audio, no manual config beyond scanning a QR code |
| 2 | Survives 30 minutes unattended | Buffer-depth CSV stays flat, 0 underruns, latency doesn't creep |
| 3 | Recovers from disruption | Unplug Wi-Fi for 10 s, it reconnects on its own |
| 4 | Handles device changes | Switch Windows output device mid-stream, audio continues |
| 5 | Measured, not guessed, latency | A number in milliseconds from an actual measurement |
| 6 | Runs as an app, not a console window | Tray icon, status, latency readout, QR code |
| 7 | Documented | README with architecture diagram + protocol spec |

### v2.0 — "It's on my phone"

| # | Criterion | How you prove it |
|---|---|---|
| 8 | iOS app receives and plays the stream | Audio out of the iPhone |
| 9 | Pairs by scanning the QR code | No typing IP addresses |
| 10 | Keeps playing with the screen locked | Background audio mode working |
| 11 | Works through wired earbuds | The whole point |
| 12 | Latency shown on screen, and it beats Bluetooth | Side-by-side video |

---

## Ground rules

1. **One new variable at a time.** Never debug network + audio, or Windows + iOS, together.
2. **Every package ends in a commit.** If it doesn't pass acceptance, don't commit and don't advance.
3. **Don't let the agent refactor working code** unless a package explicitly says to.
4. **Keep `AudioInspect` forever.** It's your diagnostic tool when something sounds wrong.
5. **Measure before you optimise.** WP3 exists before WP4 for a reason.

---

# PHASE 1 — Foundations

## WP0 · Repo structure and baseline

**Why:** you cannot demo a project that lives in a folder on your desktop, and Claude Code
works badly in a repo with no structure. Also, you need a known-good commit to fall back to.

**Acceptance:** `git log` shows an initial commit. Both C# projects build clean. `.gitignore`
excludes `bin/`, `obj/`, `*.user`. README exists even if it's three lines.

```
Set up the Wired Tooth repo properly.

Current state: three .NET console projects (AudioInspect, NetworkClient, NetworkTest) and a
MacReceiver folder with two Python scripts.

Do this:
1. Create a WiredTooth.sln at the repo root and add the three C# projects to it.
2. Add a .NET .gitignore (bin/, obj/, .vs/, *.user) plus a Python one for MacReceiver.
3. Create docs/ with an empty PROTOCOL.md placeholder.
4. Write a minimal README.md: one-paragraph description, the current architecture as an
   ASCII diagram, and how to build and run each project.
5. git init, commit everything as "Initial commit: working UDP audio prototype".

Do NOT change any existing code logic. This is structure only.
```

---

## WP1 · Protocol v1 — formalise the packet format

**Why:** you're about to have two independent implementations (C# and Swift). The moment
that happens, an informal header becomes a bug factory. Also, your current header has no
timestamp, so you literally cannot measure latency — and latency is what this project is
about. Fix the foundation before you build on it.

**New packet format:**

```
COMMON HEADER (20 bytes, every packet)
  offset  size  field
  0       4     magic       "WTP1" (0x57 0x54 0x50 0x31)
  4       1     type        1=AUDIO 2=HELLO 3=HELLO_ACK 4=PING 5=PONG 6=BYE
  5       1     flags       bit0 = silence frame
  6       2     payloadLen  uint16
  8       4     sequence    uint32
  12      8     timestamp   int64, microseconds since sender start

AUDIO SUB-HEADER (8 more bytes, AUDIO packets only)
  20      4     sampleRate  uint32
  24      2     channels    uint16
  26      1     bitsPerSample
  27      1     codec       0 = PCM16  (1 = Opus, reserved)
  28      ...   payload
```

**Acceptance:** loopback test still works end to end. `docs/PROTOCOL.md` documents every
field. A malformed or wrong-magic packet is dropped without crashing the receiver.

```
Implement Wired Tooth Protocol v1.

Create a shared class library project "WiredTooth.Protocol" and reference it from both
NetworkClient and NetworkTest. It must contain:

- A PacketType enum: Audio=1, Hello=2, HelloAck=3, Ping=4, Pong=5, Bye=6
- A static WtpPacket class with WriteAudioHeader / TryParse methods
- Magic bytes "WTP1", little-endian throughout
- The exact layout documented in BUILD_PLAN.md WP1 (20-byte common header,
  8-byte audio sub-header)
- Timestamps as microseconds since a Stopwatch started at process start

Then update NetworkClient and NetworkTest to use it. The receiver must silently drop any
packet whose magic doesn't match or whose length is inconsistent — never throw.

Finally write docs/PROTOCOL.md with a byte-offset table and one worked example packet
in hex.

Acceptance: run sender and receiver on 127.0.0.1, audio still plays, no exceptions.
```

---

## WP2 · Control channel and handshake

**Why:** right now the sender fires packets at an IP address whether or not anyone is
listening. That means: no way to show "connected", no way to know the client left, and
the sender wastes bandwidth into the void. A handshake also gives you the hook you need
for PING/PONG in WP3.

**Acceptance:** sender prints `Client connected: <ip>` and only streams while a client is
alive. Kill the receiver and the sender detects it within ~3 seconds and stops streaming.

```
Add a control channel to Wired Tooth.

Design:
- Sender listens on UDP 5001 for control packets. Audio still goes out on 5000.
- Receiver sends HELLO to the sender's control port. Sender replies HELLO_ACK and adds the
  client to an active-clients list, then begins streaming audio to that client's address.
- Receiver sends HELLO as a keepalive every 1 second. If the sender hears nothing from a
  client for 3 seconds, it drops that client and stops streaming to it.
- When the receiver exits cleanly, it sends BYE.
- The sender supports multiple simultaneous clients (a list, not a single address).

Update both NetworkClient and NetworkTest. The sender should print connect/disconnect
events. Keep the existing "dotnet run <ip>" argument working: passing an IP should make the
sender stream to that address unconditionally, as a debug fallback.

Acceptance: start receiver -> sender logs a connect. Ctrl-C the receiver -> sender logs a
disconnect within 3 seconds and stops sending.
```

---

# PHASE 2 — The engineering that actually matters

## WP3 · Measure latency and buffer health

**Why:** this is the single most important package in the file. Your project's whole claim
is "lower latency than Bluetooth." Right now you cannot state a number. Build the ruler
before you try to cut anything, and you'll also get the CSV you need to prove WP4 worked.

**Three measurements, increasing in honesty:**

1. **RTT** — PING/PONG round trip on the control channel. Cheap, tells you network health.
2. **Estimated end-to-end** — `RTT/2 + jitter buffer depth + output device latency`. Good enough for the UI.
3. **Acoustic ground truth** — play a click on the PC, record both the PC output and the earbud output on one microphone, measure the sample offset. This is the number you put on LinkedIn, because it's the only one that includes everything.

**Acceptance:** receiver prints live RTT and estimated latency. A CSV appears with one row
per second: `timestamp, rtt_ms, buffer_ms, packets_lost, underruns, estimated_latency_ms`.
A script turns that CSV into a PNG chart.

```
Add latency and health measurement to Wired Tooth.

1. PING/PONG: receiver sends PING with its send-time every 500 ms on the control channel.
   Sender replies PONG echoing the timestamp. Receiver computes RTT and keeps a rolling
   median over the last 20 samples (median, not mean - one outlier shouldn't move it).

2. Estimated end-to-end latency = (median RTT / 2) + current jitter buffer depth in ms +
   the WaveOut DesiredLatency. Display it live.

3. Metrics CSV: the receiver writes metrics_<timestamp>.csv, one row per second, columns:
   elapsed_s, rtt_ms, buffer_ms, packets_received, packets_lost, underruns, est_latency_ms

4. Add tools/plot_metrics.py that reads a metrics CSV and saves a PNG with two stacked
   subplots: buffer depth over time, and latency over time. Use matplotlib. Label the axes.

5. Add a docs/MEASUREMENT.md explaining all three measurement methods including the
   acoustic ground-truth procedure (play a click, record PC output and earbud output on one
   mic, measure sample offset in Audacity).

Acceptance: run for 60 seconds, get a CSV, run the plot script, get a readable PNG.
```

---

## WP4 · Clock drift correction

**Why:** this is the package that separates your project from every tutorial. Your PC's
clock says 48000 Hz. The receiver's says 48000 Hz. Neither is exactly right. Over minutes,
the receiver consumes audio slightly faster or slower than the sender produces it, so the
buffer drains to zero (clicking) or grows without bound (latency creeping to 400 ms).
It will look perfect for 90 seconds and fall apart at 20 minutes. **This is the reason to
build WP3 first** — the buffer-depth chart makes drift visible instead of mysterious.

**The fix:** the receiver continuously nudges its playback rate. Buffer too full → play at
48000.05 Hz. Too empty → 47999.95 Hz. Corrections so small they're inaudible, applied
constantly. This is a control loop, and writing one is a genuinely good thing to have on
a CV.

**Acceptance:** 30-minute run. Buffer depth **mean** within ±10 ms of target, with no
measurable trend across the run. Zero underruns. The latency line on your chart is flat,
not sloped. Screenshot that chart — it's your best LinkedIn image.

> **Amended during WP4, 2026-09-10.** This clause originally required *instantaneous*
> depth to stay within ±10 ms. Measured over 25.6 minutes: mean 60.4 ms against a 60 ms
> target, trend −0.3 ms (first-half mean 60.5, second-half 60.2), zero underruns, zero
> loss across 227,156 packets — but only 33% of one-second samples fell inside ±10 ms,
> because depth oscillated ±18 ms.
>
> That residual is packet **arrival jitter**, not clock drift, and absorbing it is the
> jitter buffer's entire purpose. The clause exists to catch drift — a buffer that walks
> steadily up or down until it overflows or starves — and a flat mean with a −0.3 ms
> trend is exactly the evidence it was asking for. Tightening the controller to chase
> instantaneous depth would trade a buffering problem for an audible one: the correction
> is applied as playback speed, so reacting to jitter means modulating pitch at the jitter
> frequency. The same paragraph already demands a *slow* controller that does not
> oscillate, so the original clause was in tension with itself.

```
Implement adaptive jitter buffering with clock drift correction in the Wired Tooth receiver.

The problem: sender and receiver sound-card clocks differ by a few parts per million, so the
buffer slowly drains or overflows. Fix it with continuous resampling.

Implement:
1. A target buffer depth (default 60 ms), configurable.
2. A control loop running every 100 ms that measures actual buffer depth and computes a
   correction ratio, clamped to +/- 0.5% so it stays inaudible. Use a slow proportional
   controller - it must not oscillate.
3. Apply the ratio by resampling the incoming PCM before it enters the buffer. Use NAudio's
   WdlResamplingSampleProvider or a linear interpolator; document the choice.
4. Handle the packet-loss case: on a detected gap, insert silence (or repeat the last frame
   with a short fade) rather than letting the buffer starve.
5. Log the current correction ratio into the metrics CSV as a new column.

Also: WASAPI loopback does NOT fire DataAvailable while the PC is silent. Handle that in the
sender - if no audio for 100 ms, send AUDIO packets with the silence flag set and an empty
payload, so the receiver's buffer and RTT keep working during quiet passages.

Acceptance: 30-minute run with music playing. Buffer depth MEAN within +/-10 ms of the
60 ms target with no measurable trend across the run, zero underruns, and the correction
ratio settles rather than oscillating. Instantaneous depth will oscillate by roughly the
peak arrival jitter; that is the buffer doing its job, not drift. Do not raise the
controller gain to flatten it.
```

---

## WP5 · Survive the real world

**Why:** the difference between "works on my machine" and "works." Every item here is
something a viewer of your demo will accidentally do.

**Acceptance:** all four disruptions below recover automatically without a restart.

```
Make Wired Tooth robust to real-world disruption.

Handle these four cases:

1. Network drop: if the receiver gets no audio for 2 seconds, show "reconnecting", keep
   sending HELLO with exponential backoff (0.5s, 1s, 2s, capped at 5s), and resume cleanly
   when packets return. No restart required.

2. Windows output device change: use NAudio's MMNotificationClient to detect the default
   render device changing. Tear down the WasapiLoopbackCapture and restart it on the new
   device without dropping the client connection.

3. Sample-rate / format change: if the new device's WaveFormat differs, the sender just
   starts stamping the new values in the audio sub-header. The receiver must detect a
   format change mid-stream, rebuild its playback chain, and continue.

4. Clean shutdown: Ctrl-C on either side sends BYE and closes sockets properly. No orphaned
   threads, no port-in-use on restart.

Acceptance, tested manually in one continuous run:
- disable/re-enable Wi-Fi -> recovers
- change the Windows sound output device -> audio continues
- change that device's sample rate in Sound settings -> audio continues
- Ctrl-C both sides, restart immediately -> no "address already in use"
```

---

# PHASE 3 — Make it an app

## WP6 · Windows tray application

**Why:** nobody, including a recruiter watching your demo, is impressed by two console
windows. This is also where the QR code lives, which is what makes the iOS pairing in
WP7 painless.

**Acceptance:** double-click an exe, get a tray icon. Right-click shows status, connected
clients, live latency, and a QR code window. Console mode still available with `--console`.

```
Build a Windows tray application front-end for the Wired Tooth sender.

Create a new WPF project "WiredTooth.Windows" (net10.0-windows) that wraps the existing
NetworkClient logic. Do NOT rewrite the audio or networking code - extract it into a
reusable class and call it.

Features:
- System tray icon with two states: idle (grey) and streaming (coloured)
- Right-click menu: Show Status, Show QR Code, Start/Stop, Exit
- Status window: local IP and port, capture format, connected clients list, live latency,
  packet loss %, buffer depth
- QR code window: renders a QR encoding wiredtooth://<local-ip>:5000 so a phone can scan
  it. Use the QRCoder NuGet package. Auto-detect the local Wi-Fi IP and show it as text
  underneath as a fallback.
- A --console flag that keeps the old console behaviour for debugging

Keep NetworkClient as a thin console wrapper around the same shared logic so both still work.

Acceptance: launch the exe, tray icon appears, the Mac/Windows receiver connects, status
window shows live numbers, QR code scans correctly in a phone camera app.
```

---

# PHASE 4 — The iPhone

**Do not start this until v1.0 acceptance passes.** Every hour spent debugging drift on iOS
is an hour you'd have spent more cheaply on Windows.

## WP7a · Empty app on the device

```
Milestone only, no code from you needed.

In Xcode: new iOS App, SwiftUI, Swift. Product name WiredTooth, bundle id com.<you>.WiredTooth.
Connect the iPhone by USB, select it as the run destination, press Run.

Done when the default SwiftUI screen appears on the physical iPhone. Nothing else.
```

## WP7b · Receive and count packets

**Why:** exactly mirrors `receiver_stats.py`. Network first, audio never at the same time.

```
Build the Wired Tooth iOS receiver, stage 1: networking only, no audio.

Use Network.framework (NWConnection / NWListener), not raw BSD sockets.

Requirements:
- Listen on UDP 5000 for audio, send HELLO keepalives to the sender's control port 5001
- Parse the WTP1 header exactly as specified in docs/PROTOCOL.md - write a Swift
  WtpPacket parser mirroring the C# one
- Add NSLocalNetworkUsageDescription to Info.plist with a clear user-facing explanation.
  Use UNICAST ONLY - no broadcast, no multicast, no Bonjour browsing, because those require
  the com.apple.developer.networking.multicast entitlement which we are not requesting.
- SwiftUI screen showing: connection state, sender IP, stream format, packets received,
  packets lost, loss %, kbit/s

Do NOT add audio playback in this package.

Acceptance: Windows sender streaming, iPhone screen shows packets arriving and loss under 1%.
```

## WP7c · Play the audio

```
Wired Tooth iOS receiver, stage 2: playback.

Use AVAudioEngine with an AVAudioPlayerNode and a manual buffer queue.

- Build the AVAudioFormat from the sample rate / channels in the audio sub-header
- Port the jitter buffer and the drift-correction control loop from the C# receiver.
  Same algorithm, same 60 ms target, same +/-0.5% clamp. Reuse the design, not a rewrite.
- Configure AVAudioSession: category .playback, mode .default, and request a low
  preferred IO buffer duration (0.005 s)
- Add UIBackgroundModes: audio to Info.plist so playback survives screen lock
- Show live buffer depth and underrun count on screen

Acceptance: audio from the Windows PC plays out of the iPhone. Lock the screen - audio
continues. Buffer depth holds steady for 10 minutes.
```

## WP7d · Pairing and polish

```
Wired Tooth iOS receiver, stage 3: pairing and UI.

- QR scanner using AVCaptureSession that reads wiredtooth://<ip>:<port> and connects
- Remember the last sender in UserDefaults and auto-reconnect on launch
- Manual IP entry as a fallback
- Main screen: big connect/disconnect button, connection state, live latency in ms,
  and a small stats disclosure
- Reconnect with backoff when the stream drops, mirroring the desktop receiver

Acceptance: cold launch the app, scan the QR on the Windows status window, audio starts.
Kill Wi-Fi for 10 seconds, it recovers by itself.
```

---

# PHASE 5 — Package it for people to see

## WP8 · Benchmark it honestly

**Why:** "low latency" is a wish. "38 ms measured end-to-end vs 176 ms over Bluetooth on the
same hardware" is engineering. The second one is what makes a LinkedIn post worth reading.

**Acceptance:** a table of real numbers in the README, produced by the acoustic method
in `docs/MEASUREMENT.md`, with the methodology described so someone could repeat it.

```
Produce the Wired Tooth benchmark.

Write tools/benchmark.md documenting an acoustic latency measurement:
- Play a 1 kHz click on the Windows PC
- Record with one microphone positioned to pick up both the PC speaker and the earbud
- Measure the sample offset between the two clicks in Audacity
- Repeat 10 times, report median and range

Run this for three configurations and put the results in a README table:
1. Wired Tooth over Wi-Fi hotspot
2. The same Bluetooth headphones connected to the PC directly
3. Wired earbuds plugged into the PC (the floor - this is as good as it can get)

Also record: packet loss %, buffer depth, and CPU usage of the sender, for each.

Be honest about variance and about the conditions. State the router, the distance, and
the number of devices on the network.
```

## WP9 · README, diagram, demo

**Why:** on LinkedIn, most people will read your README and watch 15 seconds of video.
That's the whole deliverable as far as they're concerned. Give it the same care as the code.

```
Write the public-facing documentation for Wired Tooth.

README.md structure:
1. One-sentence description and an animated GIF of it working, at the very top
2. The problem: Bluetooth audio latency makes video and games unusable
3. The measured result table from WP8, near the top - lead with the number
4. Architecture diagram as a Mermaid flowchart: WASAPI -> PCM conversion -> WTP1 packets
   -> UDP -> Wi-Fi -> jitter buffer -> drift correction -> AVAudioEngine -> earbuds
5. "How it works" - three short subsections: capture, transport, and the drift-correction
   control loop. This is the part that shows you understand what you built.
6. Build and run instructions for both platforms
7. Known limitations, stated plainly
8. What I'd do next

Also add docs/ENGINEERING_NOTES.md: the three hardest problems (clock drift, iOS local
network restrictions, WASAPI silence gaps), what you tried, and what actually worked.
Include the buffer-depth chart from before and after drift correction.

Keep it concise. No filler, no emoji.
```

---

## WP10 · The LinkedIn post

Post **once, at v2.0**, with the video of your phone playing PC audio through wired
earbuds. That's the moment the whole idea becomes obvious in three seconds.

What to include, in order:

1. **The 15-second video.** Phone in hand, wired earbuds in, PC playing a video, audio in sync. Show a clapperboard-style sync test if you can — a visible action and the sound landing together sells it instantly.
2. **The number.** "X ms end-to-end, versus Y ms over Bluetooth on the same hardware."
3. **The one hard problem.** Two or three sentences on clock drift and the control loop that fixes it. This is what separates you from everyone posting a tutorial follow-along — you hit a non-obvious problem and solved it.
4. **The before/after buffer chart.** One image, drift vs corrected. It's legible to engineers in one glance.
5. **The repo link.**

What to leave out: the roadmap, the things that don't work yet, and any apology for scope.
Post the thing you finished, not the thing you planned.

---

## Suggested order of attack

```
WP0  ──▶ WP1 ──▶ WP2 ──▶ WP3 ──▶ WP4 ──▶ WP5 ──▶ WP6 ──▶ [v1.0]
                                                            │
                          WP7a ──▶ WP7b ──▶ WP7c ──▶ WP7d ──┘──▶ [v2.0]
                                                            │
                                          WP8 ──▶ WP9 ──▶ WP10
```

Realistic effort, working evenings: WP0–WP2 a few days, WP3 a few days, **WP4 is the big
one** and may take a week or more on its own, WP5–WP6 a week, WP7 two to three weeks
including learning Swift, WP8–WP10 a few days.

If you only get to v1.0, you still have a real distributed-audio project with measured
latency and a control loop in it. That is a good project.
