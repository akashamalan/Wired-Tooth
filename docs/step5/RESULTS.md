# EQ step 5 — acceptance run

Pixel_7a emulator, Android 16 (API 36) x86_64, 1080x2400.
`Step5Test`, all five tests, run against `TestSender` on 127.0.0.1 inside the
guest. 48 kHz, 2 ch, 16-bit, stream 0, 1372-byte payloads.

Nothing here required UDP 5001 on the development machine. The sender lives in
the guest, which is why the run was possible while the tray app still held that
port on the host.

## a) Back does not stop the stream

Measured from the sender's side, because the receiver claiming to be fine
proves nothing if the sender has already dropped it.

| | before Back | after Back (+5 s) |
|---|---|---|
| sender client count | 1 | **1** |
| BYE packets received | 0 | **0** |
| PINGs received from receiver | 4 | **18** |
| audio packets sent | 260 | **1269** |

The client survives, no BYE is sent, and the receiver's control loop keeps
talking — which is what proves the process is alive rather than merely
un-notified.

## b) 30 s of silence keepalives

| | |
|---|---|
| keepalives sent in the window | 297 |
| underruns before | 20 |
| underruns after | **20** |

Zero new underruns across 30 seconds of silence-flagged, header-only
keepalives. The 20 are from pre-roll before the measurement window opened.

## c) and e) EQ on versus off

One continuous stream, one receiver, EQ off for 15 s then on for 15 s, with the
timers zeroed after a 5 s warm-up. The curve was the Bass preset with the Bass
macro at +6, which is the worst case the UI can reach.

| | quanta | underruns | avg per quantum | worst quantum |
|---|---|---|---|---|
| EQ bypassed | 1498 | **0** | 12,976 ns (13.0 us) | 584,800 ns (0.58 ms) |
| EQ on | 1499 | **0** | 173,569 ns (174 us) | 1,436,900 ns (1.44 ms) |

Cost of the equalizer: about **160 us per 10 ms quantum on average**, or 1.6% of
the quantum's wall-clock budget on this machine. Packet loss 0.0%, clipped
samples 0, filter resets 0, headroom -11.33 dB (which matches the -11.3 dB the
desktop JVM computed for the same curve, so the two implementations agree).

The worst-case figure is noisy: an earlier run of the identical test gave
4.31 ms against the same 174 us average. Both are scheduling noise on a shared
host CPU rather than DSP cost, which is exactly why the average is the number
worth quoting and the worst is worth watching rather than trusting.

Separately, the Diagnostics panel caught a real outlier: the first quanta after
the equalizer is switched on mid-stream can reach ~140 ms, because the filter
code is being JIT-compiled the first time it runs. It settles immediately. On a
phone this could be an audible glitch at the moment Sound is turned on, and it
is not covered by the steady-state numbers above.

## d) Screenshots

`00-now-playing.png`, `01-sound-flat.png`, `02-sound-bass.png`,
`03-sound-advanced.png`, `04-sound-reduce-motion.png`,
`05-sound-diagnostics.png`.

Driven entirely through the accessibility tree (`By.descContains`), so every
control the test touches is proven reachable by description — a tap that could
not find its target is a control TalkBack could not have found either.

A still frame cannot show the absence of motion, so `04` looks like `02`. What
it does show is that the sheet lays out and renders correctly with
`animator_duration_scale 0`, which is the part that breaks in practice when
animation is special-cased away.

## Bugs this run found

1. **Negative padding crash in both sheets.** `motionMomentum()` is a
   low-bouncy spring, so its progress overshoots past 1.0; `(1f - progress)`
   then goes negative and `Modifier.padding` throws
   "Padding must be non-negative". This took the app down the first time the
   Sound sheet was ever opened on a device, and `ConfirmSheet` had the
   identical line, so Disconnect would have done the same. Fixed by using
   `offset`, which can go negative and renders the overshoot as the slight
   over-travel it was meant to be.

2. **Invisible slider tracks.** `DbSlider` drew its track in
   `p.surfaceRaised` — which is exactly the Sound sheet's own background
   colour, so the track vanished. In the light palette `surfaceRaised` is pure
   white, so it would be invisible against almost anything. Fixed by deriving
   the track from the text colour at low alpha, which is an overlay and works
   on any surface in either palette. `VolumeSlider` had the same latent defect
   and was fixed the same way, though that one is not visually verified here
   because it only renders for a multi-stream sender.

## Not verified here

The emulator runs on the host's x86 CPU through the host's audio stack. These
numbers are not phone numbers and must not be converted into a phone CPU
percentage. Specifically still open on real hardware: actual CPU cost and
thermal behaviour, whether the equalizer sounds correct through wired earbuds,
whether the 0.5 dB headroom margin is enough against real music rather than
synthetic tones, the feel of TalkBack gestures on the sliders, and the
first-quantum JIT spike described above.
